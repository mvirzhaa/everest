package id.ac.uika.everest.farhan

import android.content.Context
import android.location.Geocoder
import android.os.Build
import id.ac.uika.everest.Locator
import id.ac.uika.everest.data.local.FarhanMessageEntity
import id.ac.uika.everest.data.local.LocationPointEntity
import id.ac.uika.everest.data.local.SegmentHit
import id.ac.uika.everest.data.remote.CalendarReq
import id.ac.uika.everest.data.remote.FarhanReq
import id.ac.uika.everest.data.remote.LocalContext
import id.ac.uika.everest.record.Keywords
import id.ac.uika.everest.record.RecordingService
import id.ac.uika.everest.doa.PrayerBook
import id.ac.uika.everest.doa.PrayerMatcher
import id.ac.uika.everest.siren.SirenActivity
import id.ac.uika.everest.siren.SirenService
import id.ac.uika.everest.sync.OutKind
import id.ac.uika.everest.util.formatDate
import id.ac.uika.everest.util.formatDateTime
import id.ac.uika.everest.util.formatTime
import id.ac.uika.everest.util.isOnline
import id.ac.uika.everest.util.mapsUrl
import id.ac.uika.everest.util.newId
import id.ac.uika.everest.util.parseIso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Kartu hasil yang bisa ditap: membuka catatan di detik tertentu, atau peta. */
data class FarhanCard(
    val title: String,
    val subtitle: String,
    val noteId: String? = null,
    val startMs: Long = 0,
    val url: String? = null,
    /** Rute di aplikasi (mis. "doa/baca/naik-kendaraan"). */
    val route: String? = null,
)

data class PendingEvent(val title: String, val start: ZonedDateTime, val end: ZonedDateTime?, val location: String?, val description: String? = null)

data class FarhanReply(
    /** Kalimat pendek untuk diucapkan (1–2 kalimat). */
    val speech: String,
    val cards: List<FarhanCard> = emptyList(),
    val pending: PendingEvent? = null,
)

/**
 * Otak Farhan: perintah & pencarian dijalankan di HP (offline), pertanyaan yang butuh penalaran
 * dikirim ke LLM cloud lewat server kampus saat online.
 */
class FarhanEngine(private val context: Context) {
    private val parser = IntentParser()
    private val db = Locator.db
    private val idLocale = Locale.forLanguageTag("id-ID")
    private val dayTime = DateTimeFormatter.ofPattern("EEEE d MMMM 'pukul' HH.mm", idLocale)

    suspend fun handle(utterance: String): FarhanReply {
        db.farhan().insert(FarhanMessageEntity(role = "pengguna", text = utterance))
        val reply = runCatching { route(parser.parse(utterance), utterance) }
            .getOrElse { FarhanReply("Maaf, ada kendala: ${Locator.client.errorMessage(it)}") }
        db.farhan().insert(
            FarhanMessageEntity(
                role = "farhan",
                text = reply.speech + if (reply.cards.isNotEmpty()) "\n" + reply.cards.joinToString("\n") { "• ${it.title} — ${it.subtitle}" } else "",
                sources = reply.cards.mapNotNull { c -> c.noteId?.let { "$it:${c.startMs}" } }.joinToString(","),
            ),
        )
        return reply
    }

    private suspend fun route(intent: FarhanIntent, utterance: String): FarhanReply = when (intent) {
        is FarhanIntent.StartRecording -> {
            if (RecordingService.isRecording()) FarhanReply("Rekaman sudah berjalan.")
            else {
                RecordingService.start(context)
                FarhanReply("Baik, saya mulai merekam. Ucapkan \"berhenti merekam\" atau tekan tombol Rekam untuk berhenti.")
            }
        }

        is FarhanIntent.StopRecording -> {
            if (!RecordingService.isRecording()) FarhanReply("Tidak ada rekaman yang sedang berjalan.")
            else {
                RecordingService.stop(context)
                FarhanReply("Rekaman disimpan. Transkripnya sedang dibuat di HP.")
            }
        }

        is FarhanIntent.StartSiren -> {
            if (SirenService.isOn()) FarhanReply("Sirine sudah menyala. Katakan \"matikan sirine\" untuk mematikan.")
            else {
                context.startActivity(android.content.Intent(context, SirenActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                FarhanReply("Sirine akan berbunyi setelah hitung mundur 5 detik. Tekan Batal bila tidak jadi.")
            }
        }

        is FarhanIntent.StopSiren -> {
            if (!SirenService.isOn()) FarhanReply("Sirine tidak sedang menyala.")
            else {
                SirenService.stop(context)
                FarhanReply("Sirine dimatikan.")
            }
        }

        is FarhanIntent.OpenPrayer -> openPrayer(intent.query)
        is FarhanIntent.SearchRecordings -> searchRecordings(intent, utterance)
        is FarhanIntent.LocationHistory -> locationHistory(intent)
        is FarhanIntent.CreateEvent -> proposeEvent(PendingEvent(intent.title, intent.start, intent.end, intent.location))
        is FarhanIntent.ListEvents -> listEvents(intent)
        is FarhanIntent.Summarize -> askCloud(utterance, localHits(intent.range, intent.keywords), offlineFallback = summarizeOffline(intent))
        is FarhanIntent.Ask -> askCloud(utterance, localHits(null, Keywords.ftsQuery(utterance)?.let { utterance }), offlineFallback = null)
    }

    // ---------------- Doa ----------------

    private fun openPrayer(query: String): FarhanReply {
        val data = PrayerBook.load(context)
        return when (val m = PrayerMatcher.match(query, PrayerBook.refs(context))) {
            is PrayerMatcher.Match.Item -> {
                val p = PrayerBook.find(context, m.id) ?: return FarhanReply("Maaf, bacaan itu tidak ditemukan.")
                // Teks Arab tidak dibacakan TTS bahasa Indonesia; ucapkan judul dan arti singkat
                val gist = (p.meaning ?: p.verses.firstOrNull()?.meaning).orEmpty().take(140)
                FarhanReply(
                    "Ini ${p.title.replaceFirstChar { it.lowercase() }}." + if (gist.isNotBlank()) " Artinya: $gist" else "",
                    listOf(FarhanCard(p.title, p.`when` ?: p.source, route = "doa/baca/${p.id}")),
                )
            }
            is PrayerMatcher.Match.Category -> {
                val c = data.categories.first { it.id == m.id }
                FarhanReply(
                    "Ada ${c.items.size} bacaan di ${c.title}. Silakan pilih.",
                    listOf(FarhanCard(c.title, c.subtitle, route = "doa?kategori=${c.id}")) +
                        c.items.take(4).map { FarhanCard(it.title, it.`when` ?: it.source, route = "doa/baca/${it.id}") },
                )
            }
            PrayerMatcher.Match.None -> FarhanReply(
                "Di menu Doa ada doa perjalanan dan bepergian, Ayat Kursi, dan ayat-ayat ruqyah. Doa apa yang Anda cari?",
                data.categories.map { FarhanCard(it.title, it.subtitle, route = "doa?kategori=${it.id}") },
            )
        }
    }

    // ---------------- Rekaman ----------------

    private suspend fun localHits(range: TimeRange?, keywords: String?): List<SegmentHit> {
        val from = range?.start?.toInstant()?.toEpochMilli() ?: 0L
        val to = range?.end?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE
        val q = keywords?.let { Keywords.ftsQuery(it) }
        return if (q != null) runCatching { db.segments().search(q, from, to, 30) }.getOrDefault(emptyList())
        else if (range != null) db.segments().inRange(from, to, 60)
        else emptyList()
    }

    private suspend fun searchRecordings(i: FarhanIntent.SearchRecordings, utterance: String): FarhanReply {
        val hits = localHits(i.range, i.keywords)
        val byNote = hits.groupBy { it.noteId }
        if (byNote.isEmpty()) {
            // Mungkin ada di server (direkam di HP lain); tanyakan ke cloud bila online
            if (canUseCloud()) return askCloud(utterance, emptyList(), offlineFallback = null)
            val where = i.range?.label?.let { " $it" } ?: ""
            val about = i.keywords?.let { " tentang \"$it\"" } ?: ""
            return FarhanReply("Saya tidak menemukan rekaman$where$about.")
        }
        val cards = byNote.values.take(8).map { list ->
            val first = list.first()
            FarhanCard(
                title = first.title,
                subtitle = "${first.startedAt.formatDateTime()} · “${first.text.take(90)}”",
                noteId = first.noteId,
                startMs = first.startMs,
            )
        }
        val label = i.range?.label?.let { " $it" } ?: ""
        val about = i.keywords?.let { " yang menyebut \"$it\"" } ?: ""
        val names = byNote.values.take(3).joinToString(", ") { "${it.first().title} pukul ${it.first().startedAt.formatTime()}" }
        val speech = if (byNote.size == 1) "Ada satu rekaman$label$about: $names."
        else "Ada ${byNote.size} rekaman$label$about, di antaranya $names."
        return FarhanReply(speech, cards)
    }

    private suspend fun summarizeOffline(i: FarhanIntent.Summarize): FarhanReply {
        val hits = localHits(i.range, i.keywords)
        val notes = hits.map { it.noteId }.distinct().mapNotNull { db.notes().get(it) }
        if (notes.isEmpty()) return FarhanReply("Saya tidak menemukan rekaman yang cocok untuk diringkas.")
        val n = notes.first()
        val kw = n.keywords.split(',').filter { it.isNotBlank() }.take(5).joinToString(", ")
        return FarhanReply(
            "Untuk ringkasan lengkap saya butuh internet. Kata kunci ${n.title}: ${kw.ifEmpty { "belum ada" }}.",
            listOf(FarhanCard(n.title, n.summary?.take(160) ?: "Buka transkrip", noteId = n.id)),
        )
    }

    // ---------------- Lokasi ----------------

    private suspend fun locationHistory(i: FarhanIntent.LocationHistory): FarhanReply {
        val pts = db.tracks().pointsBetween(i.range.start.toInstant().toEpochMilli(), i.range.end.toInstant().toEpochMilli())
        if (pts.isEmpty()) {
            return FarhanReply("Tidak ada riwayat lokasi ${i.range.label}. Lokasi hanya tercatat saat tombol Track atau Panic aktif.")
        }
        val stops = stopsOf(pts)
        val cards = stops.take(6).map { s ->
            FarhanCard(
                title = placeName(s.lat, s.lng) ?: "%.5f, %.5f".format(Locale.US, s.lat, s.lng),
                subtitle = "${s.from.formatTime()}–${s.to.formatTime()}",
                url = mapsUrl(s.lat, s.lng),
            )
        }
        val speech = when (stops.size) {
            0 -> "Anda bergerak terus ${i.range.label}, tanpa berhenti lama di satu tempat."
            1 -> "${i.range.label.replaceFirstChar { it.titlecase(idLocale) }} Anda berada di ${cards[0].title} pukul ${cards[0].subtitle}."
            else -> "${i.range.label.replaceFirstChar { it.titlecase(idLocale) }} Anda singgah di ${stops.size} tempat, mulai dari ${cards[0].title} pukul ${stops[0].from.formatTime()}."
        }
        return FarhanReply(speech, cards)
    }

    private data class Stop(val lat: Double, val lng: Double, val from: Long, val to: Long)

    /** Kelompokkan titik berurutan dalam radius 150 m selama ≥ 5 menit sebagai satu persinggahan. */
    private fun stopsOf(pts: List<LocationPointEntity>): List<Stop> {
        val stops = mutableListOf<Stop>()
        var group = mutableListOf(pts.first())
        fun flush() {
            if (group.last().recordedAt - group.first().recordedAt >= 5 * 60_000) {
                stops += Stop(group.map { it.lat }.average(), group.map { it.lng }.average(), group.first().recordedAt, group.last().recordedAt)
            }
        }
        for (p in pts.drop(1)) {
            val c = group.first()
            if (meters(c.lat, c.lng, p.lat, p.lng) <= 150) group += p else {
                flush()
                group = mutableListOf(p)
            }
        }
        flush()
        return stops
    }

    private fun meters(a: Double, b: Double, c: Double, d: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(c - a)
        val dLng = Math.toRadians(d - b)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a)) * cos(Math.toRadians(c)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    @Suppress("DEPRECATION")
    private suspend fun placeName(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            val a = Geocoder(context, idLocale).getFromLocation(lat, lng, 1)?.firstOrNull() ?: return@runCatching null
            listOfNotNull(a.featureName?.takeIf { f -> f.any { it.isLetter() } }, a.thoroughfare, a.subLocality).distinct().take(2).joinToString(", ").ifEmpty { null }
        }.getOrNull()
    }

    // ---------------- Jadwal ----------------

    private fun proposeEvent(e: PendingEvent): FarhanReply {
        val whenText = e.start.format(dayTime)
        val endText = e.end?.let { " sampai ${it.format(DateTimeFormatter.ofPattern("HH.mm", idLocale))}" } ?: ""
        val loc = e.location?.let { " di $it" } ?: ""
        return FarhanReply("Saya jadwalkan \"${e.title}\" $whenText$endText$loc. Benar?", pending = e)
    }

    /** Pengguna mengonfirmasi usulan jadwal. Terkirim lewat antrean (aman saat offline). */
    suspend fun confirmEvent(e: PendingEvent): FarhanReply {
        val user = Locator.prefs.user
        if (user?.googleLinked != true) {
            return FarhanReply("Akun Google belum terhubung. Hubungkan Google Calendar di menu Profil, lalu ulangi perintahnya.")
        }
        val req = CalendarReq(
            id = newId(),
            title = e.title,
            start = e.start.toOffsetDateTime().toString(),
            end = e.end?.toOffsetDateTime()?.toString(),
            location = e.location,
            description = e.description,
            tz = ZoneId.systemDefault().id,
        )
        Locator.outbox.enqueue(OutKind.CALENDAR, req.id, Locator.client.json.encodeToString(req))
        val reply = if (isOnline(context)) FarhanReply("Siap, \"${e.title}\" saya masukkan ke Google Calendar.")
        else FarhanReply("Siap. Belum ada internet, jadi \"${e.title}\" akan masuk ke Google Calendar begitu online.")
        db.farhan().insert(FarhanMessageEntity(role = "farhan", text = reply.speech))
        return reply
    }

    suspend fun cancelEvent(): FarhanReply {
        val r = FarhanReply("Baik, tidak jadi dijadwalkan.")
        db.farhan().insert(FarhanMessageEntity(role = "farhan", text = r.speech))
        return r
    }

    private suspend fun listEvents(i: FarhanIntent.ListEvents): FarhanReply {
        if (!isOnline(context)) return FarhanReply("Saya perlu internet untuk membuka Google Calendar.")
        if (Locator.prefs.user?.googleLinked != true) return FarhanReply("Akun Google belum terhubung. Hubungkan Google Calendar di menu Profil.")
        val events = Locator.api.events(i.range.start.toOffsetDateTime().toString(), i.range.end.toOffsetDateTime().toString())
        if (events.isEmpty()) return FarhanReply("Tidak ada jadwal ${i.range.label}.")
        val cards = events.take(10).map { ev ->
            val start = ev.start?.let { s -> if (s.length > 10) s.parseIso().formatDateTime() else s }.orEmpty()
            FarhanCard(ev.title, listOfNotNull(start, ev.location).joinToString(" · "))
        }
        val first = events.take(3).joinToString(", ") { ev ->
            val t = ev.start?.takeIf { it.length > 10 }?.parseIso()?.formatTime()
            if (t != null) "${ev.title} pukul $t" else ev.title
        }
        return FarhanReply("Ada ${events.size} jadwal ${i.range.label}: $first.", cards)
    }

    // ---------------- Cloud ----------------

    private fun canUseCloud() = Locator.prefs.farhanCloud && isOnline(context)

    private suspend fun askCloud(question: String, local: List<SegmentHit>, offlineFallback: FarhanReply?): FarhanReply {
        if (!canUseCloud()) {
            return offlineFallback ?: FarhanReply(
                if (!Locator.prefs.farhanCloud) "Farhan cloud dimatikan di Profil. Di HP saya bisa mencari rekaman per tanggal atau kata kunci, riwayat lokasi, dan membuat jadwal."
                else "Saya perlu internet untuk pertanyaan itu. Tanpa internet saya tetap bisa mencari rekaman per tanggal atau kata kunci, riwayat lokasi, dan membuat jadwal.",
            )
        }
        val ctx = local.take(15).map { LocalContext(it.title, it.startedAt.formatDate(), it.text) }
        val res = Locator.api.askFarhan(FarhanReq(question, ZoneId.systemDefault().id, ctx))
        val cards = res.sources.distinctBy { it.noteId }.take(5).map { FarhanCard(it.title.ifBlank { "Rekaman" }, "Buka di detik ${it.startMs / 1000}", noteId = it.noteId, startMs = it.startMs) }
        val pending = res.action?.takeIf { it.type == "buat_jadwal" }?.let { a ->
            runCatching {
                PendingEvent(
                    a.title,
                    ZonedDateTime.parse(a.start).withZoneSameInstant(ZoneId.systemDefault()),
                    a.end?.let { ZonedDateTime.parse(it).withZoneSameInstant(ZoneId.systemDefault()) },
                    a.location,
                    a.description,
                )
            }.getOrNull()
        }
        return if (pending != null) proposeEvent(pending).copy(cards = cards) else FarhanReply(res.answer, cards)
    }

    companion object {
        private val YES = Regex("""^(ya|iya|yap|yup|betul|benar|bener|oke|ok|okay|boleh|lanjut|setuju|simpan|jadi|sip)\b""")
        private val NO = Regex("""^(tidak|nggak|ngga|gak|enggak|batal|batalkan|jangan|bukan|salah|ga)\b""")

        /** Jawaban konfirmasi lisan: true = ya, false = tidak, null = tidak jelas. */
        fun confirmation(text: String): Boolean? {
            val t = text.lowercase(Locale.ROOT).trim()
            return when {
                YES.containsMatchIn(t) -> true
                NO.containsMatchIn(t) -> false
                else -> null
            }
        }
    }
}
