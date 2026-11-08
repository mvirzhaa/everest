package id.ac.uika.everest.farhan

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Rentang waktu [start, end) beserta label untuk diucapkan. */
data class TimeRange(val start: ZonedDateTime, val end: ZonedDateTime, val label: String)

sealed class FarhanIntent {
    data class SearchRecordings(val range: TimeRange?, val keywords: String?) : FarhanIntent()
    data class LocationHistory(val range: TimeRange) : FarhanIntent()
    data class CreateEvent(val title: String, val start: ZonedDateTime, val end: ZonedDateTime?, val location: String?) : FarhanIntent()
    data class ListEvents(val range: TimeRange) : FarhanIntent()
    data class Summarize(val range: TimeRange?, val keywords: String?) : FarhanIntent()
    data object StartRecording : FarhanIntent()
    data object StopRecording : FarhanIntent()
    data object StartSiren : FarhanIntent()
    data object StopSiren : FarhanIntent()
    /** Buka bacaan di menu Doa (query = kalimat asli, dicocokkan oleh PrayerMatcher). */
    data class OpenPrayer(val query: String) : FarhanIntent()
    /** Tidak dikenali di HP → ke jalur cloud (bila online). */
    data class Ask(val question: String) : FarhanIntent()
}

/**
 * Pengenal perintah suara bahasa Indonesia berbasis aturan (berjalan offline di HP).
 * Contoh: "jadwalkan rapat prodi besok jam 9 di ruang 204", "rekaman tanggal 3 November ada apa saja",
 * "kemarin sore saya di mana saja", "jadwal saya minggu depan", "ringkas rapat tadi pagi".
 */
class IntentParser(private val now: () -> ZonedDateTime = { ZonedDateTime.now() }) {

    private val id = Locale.forLanguageTag("id-ID")
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", id)

    fun parse(raw: String): FarhanIntent {
        val text = normalize(raw)
        if (text.isBlank()) return FarhanIntent.Ask(raw)

        if (Regex("""\b(berhenti|stop|hentikan|akhiri) (merekam|rekam|rekaman)\b""").containsMatchIn(text)) return FarhanIntent.StopRecording
        if (Regex("""^(tolong )?(mulai|mulai kan|nyalakan) (merekam|rekam|rekaman)\b|^rekam (sekarang|suara)\b""").containsMatchIn(text)) return FarhanIntent.StartRecording

        if (Regex("""\b(matikan|hentikan|stop|berhenti|diamkan) (bunyi )?(sirine|sirene|sirene|alarm)\b""").containsMatchIn(text)) return FarhanIntent.StopSiren
        if (Regex("""\b(nyalakan|bunyikan|hidupkan|aktifkan|mulai) (bunyi )?(sirine|sirene|alarm)\b""").containsMatchIn(text)) return FarhanIntent.StartSiren

        // Doa & ayat (bukan pencarian rekaman dan bukan membuat jadwal)
        val prayerAsk = Regex("""\b(doa|do'a|ayat kursi|ayatul kursi|ruqyah|ruqiyah|rukyah|surat (al ?fatihah|al ?ikhlas|al ?falaq|an ?nas))\b""").containsMatchIn(text)
        val aboutRec = Regex("""\b(rekaman|catatan|transkrip)\b""").containsMatchIn(text)
        val scheduling = Regex("""\b(jadwalkan|jadwal kan|ingatkan|kalender)\b""").containsMatchIn(text)
        if (prayerAsk && !aboutRec && !scheduling) return FarhanIntent.OpenPrayer(raw)

        val when_ = extractWhen(text)
        val rest = when_.rest

        // Membuat jadwal
        if (Regex("""\b(jadwalkan|jadwal kan|buat(kan)? (jadwal|acara|agenda|janji)|tambah(kan)? (jadwal|acara|agenda|ke kalender)|masuk(kan)? (ke )?(kalender|jadwal)|catat (jadwal|agenda)|ingatkan saya)\b""").containsMatchIn(text)) {
            val date = when_.date ?: when_.range?.start?.toLocalDate()
            if (date != null || when_.time != null) {
                val d = date ?: today().let { t -> if (when_.time != null && when_.time < now().toLocalTime()) t.plusDays(1) else t }
                val start = d.atTime(when_.time ?: LocalTime.of(9, 0)).atZone(now().zone)
                val end = when_.endTime?.let { d.atTime(it).atZone(now().zone).takeIf { e -> e.isAfter(start) } }
                    ?: when_.durationMin?.let { start.plusMinutes(it.toLong()) }
                val (title, location) = titleAndLocation(rest)
                return FarhanIntent.CreateEvent(title, start, end, location)
            }
            return FarhanIntent.Ask(raw) // waktu tidak jelas → biarkan cloud bertanya balik
        }

        // Melihat jadwal
        if (Regex("""\b(jadwal|agenda|acara)( saya| ku)?\b""").containsMatchIn(text) && !Regex("""\brekaman|catatan\b""").containsMatchIn(text)) {
            val r = when_.range ?: when_.date?.let { dayRange(it) } ?: dayRange(today())
            return FarhanIntent.ListEvents(r)
        }

        // Riwayat lokasi
        if (Regex("""\b(di ?mana|kemana|ke mana|lokasi|posisi|jejak)\b""").containsMatchIn(text) &&
            Regex("""\b(saya|aku|riwayat|jejak|tadi|kemarin|hari ini)\b""").containsMatchIn(text) &&
            !Regex("""\brekaman|catatan|meeting|rapat\b""").containsMatchIn(text)
        ) {
            val r = when_.range ?: when_.date?.let { dayRange(it) } ?: dayRange(today())
            return FarhanIntent.LocationHistory(r)
        }

        val range = when_.range ?: when_.date?.let { dayRange(it) }
        val keywords = keywordsFrom(rest)

        // Ringkasan → butuh LLM (cloud)
        if (Regex("""\b(ringkas|ringkaskan|rangkum|rangkuman|ringkasan|intisari|poin penting|kesimpulan)\b""").containsMatchIn(text)) {
            return FarhanIntent.Summarize(range, keywords)
        }

        // Pencarian rekaman/catatan
        val aboutRecordings = Regex("""\b(rekaman|catatan|transkrip|meeting|rapat|kuliah|obrolan|pembicaraan)\b""").containsMatchIn(text)
        val listy = Regex("""\b(ada apa|apa saja|apa aja|cari|carikan|temukan|tampilkan|daftar|yang (membahas|tentang|soal))\b""").containsMatchIn(text)
        if (aboutRecordings && (listy || range != null) && isSimpleLookup(text)) {
            return FarhanIntent.SearchRecordings(range, keywords)
        }
        return FarhanIntent.Ask(raw)
    }

    /** Pertanyaan "apa keputusan...", "siapa yang ..." butuh penalaran → cloud. */
    private fun isSimpleLookup(text: String) =
        !Regex("""\b(apa keputusan|kenapa|mengapa|bagaimana|siapa yang|berapa|jelaskan|apa (yang )?di ?(sepakati|putuskan|bahas))\b""").containsMatchIn(text)

    // ------------------------------------------------------------------ waktu

    private data class When(
        val date: LocalDate? = null,
        val range: TimeRange? = null,
        val time: LocalTime? = null,
        val endTime: LocalTime? = null,
        val durationMin: Int? = null,
        val rest: String,
    )

    private fun today() = now().toLocalDate()

    private fun dayRange(d: LocalDate, label: String? = null) = TimeRange(
        d.atStartOfDay(now().zone),
        d.plusDays(1).atStartOfDay(now().zone),
        label ?: labelFor(d),
    )

    private fun labelFor(d: LocalDate): String = when (d) {
        today() -> "hari ini"
        today().minusDays(1) -> "kemarin"
        today().plusDays(1) -> "besok"
        else -> d.format(dayFmt)
    }

    private fun extractWhen(input: String): When {
        var s = " $input "
        var date: LocalDate? = null
        var range: TimeRange? = null
        var part: Pair<Int, Int>? = null // rentang jam bagian hari (pagi/siang/sore/malam)
        var partName: String? = null

        fun cut(r: Regex, block: (MatchResult) -> Unit): Boolean {
            val m = r.find(s) ?: return false
            block(m)
            s = s.removeRange(m.range).let { it.substring(0, m.range.first) + " " + it.substring(m.range.first) }
            return true
        }

        // Tanggal eksplisit: "tanggal 3 november 2026", "3 nov", "tgl 3/11", "3-11-2026"
        cut(Regex("""\b(?:tanggal |tgl\.? )?(\d{1,2}) ($MONTH_RE)(?: (\d{4}))?\b""")) { m ->
            date = safeDate(m.groupValues[1].toInt(), monthOf(m.groupValues[2]), m.groupValues[3].toIntOrNull())
        } || cut(Regex("""\b(?:tanggal |tgl\.? )?(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?\b""")) { m ->
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            date = safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), y)
        } || cut(Regex("""\b(?:tanggal|tgl\.?) (\d{1,2})\b""")) { m ->
            date = safeDate(m.groupValues[1].toInt(), today().monthValue, null)
        }

        // Rentang relatif (urutan penting: frasa panjang lebih dulu, mis. "minggu kemarin" sebelum "kemarin")
        if (date == null) {
            cut(Regex("""\b(kemarin lusa|lusa kemarin)\b""")) { date = today().minusDays(2) } ||
                cut(Regex("""\bminggu ini\b""")) { range = weekRange(0, "minggu ini") } ||
                cut(Regex("""\bminggu (lalu|kemarin|kemaren|sebelumnya)\b""")) { range = weekRange(-1, "minggu lalu") } ||
                cut(Regex("""\bminggu (depan|berikutnya)\b""")) { range = weekRange(1, "minggu depan") } ||
                cut(Regex("""\bbulan ini\b""")) { range = monthRange(0, "bulan ini") } ||
                cut(Regex("""\bbulan (lalu|kemarin|kemaren)\b""")) { range = monthRange(-1, "bulan lalu") } ||
                cut(Regex("""\bbulan depan\b""")) { range = monthRange(1, "bulan depan") } ||
                cut(Regex("""\b(?:hari )?($DAY_RE) (depan|lalu|kemarin|kemaren)\b""")) { m ->
                    val dow = dayOf(m.groupValues[1])
                    val monday = today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    date = (if (m.groupValues[2] == "depan") monday.plusWeeks(1) else monday.minusWeeks(1)).with(TemporalAdjusters.nextOrSame(dow))
                } ||
                cut(Regex("""\b(besok lusa|lusa)\b""")) { date = today().plusDays(2) } ||
                cut(Regex("""\b(besok|esok)\b""")) { date = today().plusDays(1) } ||
                cut(Regex("""\b(kemarin|kemaren)\b""")) { date = today().minusDays(1) } ||
                cut(Regex("""\b(hari ini|tadi|barusan|sekarang)\b""")) { date = today() } ||
                cut(Regex("""\b(?:hari )?($DAY_RE)(?: ini)?\b""")) { m ->
                    val dow = dayOf(m.groupValues[1])
                    // Jadwal → hari itu yang akan datang; pencarian → hari itu yang terakhir lewat
                    date = if (SCHEDULE_HINT.containsMatchIn(input)) today().with(TemporalAdjusters.nextOrSame(dow))
                    else today().with(TemporalAdjusters.previousOrSame(dow))
                }
        }

        // Jam: "jam 9", "pukul 09.30", "jam 2 siang", "14:00", "sampai jam 11", "selama 2 jam"
        var end: LocalTime? = null
        var duration: Int? = null
        cut(Regex("""\b(?:sampai|hingga|s\.?d\.?|sd) (?:jam |pukul )?(\d{1,2})(?:[.:](\d{2}))?(?: (pagi|siang|sore|malam))?\b""")) { m ->
            end = toTime(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].ifEmpty { null })
        }
        cut(Regex("""\bselama (\d{1,3}|setengah|satu|dua|tiga) (jam|menit)\b""")) { m ->
            val n = when (m.groupValues[1]) { "setengah" -> 0; "satu" -> 1; "dua" -> 2; "tiga" -> 3; else -> m.groupValues[1].toInt() }
            duration = if (m.groupValues[2] == "jam") (if (m.groupValues[1] == "setengah") 30 else n * 60) else n
        }
        var time: LocalTime? = null
        cut(Regex("""\b(?:jam|pukul|pkl\.?) (\d{1,2})(?:[.:](\d{2}))?(?: (pagi|siang|sore|malam))?\b""")) { m ->
            time = toTime(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].ifEmpty { null })
        } || cut(Regex("""\b(\d{1,2})[.:](\d{2})(?: (pagi|siang|sore|malam))?\b""")) { m ->
            time = toTime(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { null })
        }

        // Bagian hari tanpa jam: "tadi pagi", "kemarin sore"
        cut(Regex("""\b(pagi|siang|sore|malam)\b""")) { m ->
            partName = m.groupValues[1]
            part = when (partName) { "pagi" -> 4 to 11; "siang" -> 11 to 15; "sore" -> 15 to 18; else -> 18 to 24 }
        }

        val d = date
        if (d != null && part != null && time == null) {
            val (a, b) = part!!
            val start = d.atTime(a, 0).atZone(now().zone)
            val stop = if (b == 24) d.plusDays(1).atStartOfDay(now().zone) else d.atTime(b, 0).atZone(now().zone)
            range = TimeRange(start, stop, "${labelFor(d)} $partName")
        } else if (d == null && part != null && time == null && range == null) {
            // "sore tadi" tanpa tanggal → hari ini
            val t = today()
            val (a, b) = part!!
            range = TimeRange(t.atTime(a, 0).atZone(now().zone), if (b == 24) t.plusDays(1).atStartOfDay(now().zone) else t.atTime(b, 0).atZone(now().zone), "hari ini $partName")
        } else if (time == null && part != null) {
            time = LocalTime.of(when (partName) { "pagi" -> 8; "siang" -> 13; "sore" -> 16; else -> 19 }, 0)
        }

        return When(date = d, range = range, time = time, endTime = end, durationMin = duration, rest = s.replace(Regex("""\s+"""), " ").trim())
    }

    private fun toTime(hRaw: Int, m: Int, part: String?): LocalTime? {
        if (hRaw > 24 || m > 59) return null
        var h = hRaw % 24
        when (part) {
            "pagi" -> if (h == 12) h = 0
            "siang" -> if (h in 1..5) h += 12
            "sore" -> if (h in 1..11) h += 12
            "malam" -> if (h in 1..11) h += 12 else if (h == 12) h = 0
            null -> if (h in 1..6) h += 12 // "rapat jam 2" biasanya pukul 14.00
        }
        return LocalTime.of(h % 24, m)
    }

    private fun weekRange(offset: Long, label: String): TimeRange {
        val monday = today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset)
        return TimeRange(monday.atStartOfDay(now().zone), monday.plusWeeks(1).atStartOfDay(now().zone), label)
    }

    private fun monthRange(offset: Long, label: String): TimeRange {
        val first = today().withDayOfMonth(1).plusMonths(offset)
        return TimeRange(first.atStartOfDay(now().zone), first.plusMonths(1).atStartOfDay(now().zone), label)
    }

    /** Tanggal tanpa tahun: ambil yang terdekat (tahun ini, atau tahun lalu bila jauh di depan). */
    private fun safeDate(day: Int, month: Int, year: Int?): LocalDate? = runCatching {
        if (year != null) return@runCatching LocalDate.of(year, month, day)
        val candidate = LocalDate.of(today().year, month, day)
        if (candidate.isAfter(today().plusMonths(6))) candidate.minusYears(1) else candidate
    }.getOrNull()

    // ------------------------------------------------------------------ teks

    private fun titleAndLocation(rest: String): Pair<String, String?> {
        var s = rest
        TRIGGERS.forEach { s = s.replace(it, " ") }
        var location: String? = null
        val m = Regex("""\b(?:di|lokasi(?: di)?|tempat(?:nya)?(?: di)?) ((?:ruang|ruangan|gedung|aula|lab|laboratorium|kantin|masjid|perpustakaan|kampus|kelas|lantai|lt|zoom|meet|google meet|kantor|rektorat|fakultas|prodi|rumah)\b.*)$""").find(s)
        if (m != null) {
            location = m.groupValues[1].trim().replaceFirstChar { it.titlecase(id) }
            s = s.substring(0, m.range.first)
        }
        val title = s.replace(Regex("""\b(untuk|tentang|acara|agenda|jadwal|pada|hari|di)\s*$"""), "")
            .replace(Regex("""^\s*(untuk|tentang|acara|agenda|jadwal)\b"""), "")
            .replace(Regex("""\s+"""), " ").trim()
            .ifEmpty { "Acara" }
            .replaceFirstChar { it.titlecase(id) }
        return title to location
    }

    private fun keywordsFrom(rest: String): String? {
        val cleaned = rest.replace(SEARCH_NOISE, " ").replace(Regex("""\s+"""), " ").trim()
        return cleaned.takeIf { it.length >= 3 }
    }

    private fun normalize(s: String) = s.lowercase(id)
        .replace("jum'at", "jumat").replace("jum’at", "jumat")
        .replace(Regex("""[?!,;"]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private companion object {
        const val MONTH_RE = "januari|jan|februari|feb|pebruari|maret|mar|april|apr|mei|juni|jun|juli|jul|agustus|agu|agt|ags|september|sept|sep|oktober|okt|november|nov|nopember|desember|des"
        const val DAY_RE = "senin|selasa|rabu|kamis|jumat|sabtu|minggu|ahad"

        val SCHEDULE_HINT = Regex("""\b(jadwalkan|jadwal|agenda|acara|ingatkan|kalender|janji)\b""")

        val TRIGGERS = listOf(
            Regex("""\b(tolong|dong|ya|farhan)\b"""),
            Regex("""\b(jadwalkan|jadwal kan|buat(kan)?|tambah(kan)?|masuk(kan)?|catat(kan)?|ingatkan saya|ingatkan)\b"""),
            Regex("""\b(ke )?(kalender|google calendar|calendar)( saya)?\b"""),
            Regex("""^\s*(jadwal|acara|agenda|janji)\b"""),
        )

        val SEARCH_NOISE = Regex(
            """\b(farhan|tolong|dong|cari|carikan|temukan|tampilkan|daftar|rekaman|rekamannya|catatan|transkrip|saya|aku|ku|ada|apa|saja|aja|yang|tentang|soal|membahas|bahas|dibahas|isi|isinya|di|pada|dari|dengan|ini|itu|mana|ringkas|ringkaskan|rangkum|rangkuman|ringkasan|intisari|poin|penting|kesimpulan|kasih|berikan|beri|tadi|apakah|nya|hari|tanggal|tgl)\b""",
        )

        fun monthOf(s: String): Int = when {
            s.startsWith("jan") -> 1
            s.startsWith("feb") || s.startsWith("peb") -> 2
            s.startsWith("mar") -> 3
            s.startsWith("apr") -> 4
            s.startsWith("mei") -> 5
            s.startsWith("jun") -> 6
            s.startsWith("jul") -> 7
            s.startsWith("ag") -> 8
            s.startsWith("sep") -> 9
            s.startsWith("okt") -> 10
            s.startsWith("nov") || s.startsWith("nop") -> 11
            else -> 12
        }

        fun dayOf(s: String): DayOfWeek = when (s) {
            "senin" -> DayOfWeek.MONDAY
            "selasa" -> DayOfWeek.TUESDAY
            "rabu" -> DayOfWeek.WEDNESDAY
            "kamis" -> DayOfWeek.THURSDAY
            "jumat" -> DayOfWeek.FRIDAY
            "sabtu" -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }
    }
}
