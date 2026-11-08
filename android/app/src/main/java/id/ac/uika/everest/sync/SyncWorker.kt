package id.ac.uika.everest.sync

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import id.ac.uika.everest.Locator
import id.ac.uika.everest.R
import id.ac.uika.everest.data.local.NoteEntity
import id.ac.uika.everest.data.local.OutboxEntity
import id.ac.uika.everest.data.local.SegmentEntity
import id.ac.uika.everest.data.remote.CalendarReq
import id.ac.uika.everest.data.remote.ConsentReq
import id.ac.uika.everest.data.remote.DeviceReq
import id.ac.uika.everest.data.remote.Failure
import id.ac.uika.everest.data.remote.NoteDto
import id.ac.uika.everest.data.remote.PanicReq
import id.ac.uika.everest.data.remote.PointDto
import id.ac.uika.everest.data.remote.PointsReq
import id.ac.uika.everest.data.remote.ReportMeta
import id.ac.uika.everest.data.remote.SegmentDto
import id.ac.uika.everest.data.remote.StatusReq
import id.ac.uika.everest.data.remote.StopReq
import id.ac.uika.everest.data.remote.SyncReq
import id.ac.uika.everest.data.remote.TrackReq
import id.ac.uika.everest.data.remote.classify
import id.ac.uika.everest.push.Notifications
import id.ac.uika.everest.util.now
import id.ac.uika.everest.util.parseIso
import id.ac.uika.everest.util.toIso
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlin.math.min

/**
 * Menguras antrean kirim berdasarkan prioritas, lalu menarik perubahan catatan dari server.
 * Item tidak pernah dihapus sebelum server mengonfirmasi.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val prefs = Locator.prefs
    private val db = Locator.db
    private val api get() = Locator.api
    private val json get() = Locator.client.json

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // Hanya dipakai saat pekerjaan expedited berjalan sebagai servis (Android 11 ke bawah)
        val n = NotificationCompat.Builder(applicationContext, Notifications.CH_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Mengirim data Everest")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(Notifications.ID_SYNC, n)
    }

    override suspend fun doWork(): Result {
        if (!prefs.isLoggedIn) return Result.success()
        var unauthorized = false
        var rounds = 0
        while (rounds++ < 20) {
            val due = db.outbox().due(now())
            if (due.isEmpty()) break
            for (item in due) {
                val outcome = runCatching { handle(item) }
                if (outcome.isSuccess) {
                    if (outcome.getOrDefault(true)) {
                        db.outbox().delete(item.id)
                    } else {
                        // Ditunda (menunggu item lain / Wi-Fi): coba lagi sebentar lagi
                        db.outbox().fail(item.id, now() + 30_000, "ditunda")
                    }
                    continue
                }
                val err = outcome.exceptionOrNull()!!
                when (classify(err)) {
                    Failure.UNAUTHORIZED -> unauthorized = true
                    Failure.DROP -> {
                        Log.w(TAG, "item ${item.kind}/${item.refId} ditolak server, dibuang: ${Locator.client.errorMessage(err)}")
                        onDropped(item)
                        db.outbox().delete(item.id)
                    }
                    Failure.RETRY -> {
                        val delay = backoff(item.attempts)
                        db.outbox().fail(item.id, now() + delay, Locator.client.errorMessage(err).take(300))
                    }
                }
                if (unauthorized) break
            }
            if (unauthorized) break
        }
        if (unauthorized) return Result.success() // UI akan mengarahkan ke login

        runCatching { pullNotes() }.onFailure { Log.w(TAG, "tarik catatan gagal: ${it.message}") }

        db.outbox().nextDue()?.let { next -> SyncScheduler.later(applicationContext, next - now()) }
        return Result.success()
    }

    private fun backoff(attempts: Int): Long = min(30_000L * (1L shl min(attempts, 6)), 30 * 60_000L)

    /** @return true bila item selesai dan boleh dihapus; false bila ditunda (mis. menunggu Wi-Fi). */
    private suspend fun handle(item: OutboxEntity): Boolean {
        when (OutKind.valueOf(item.kind)) {
            OutKind.DEVICE -> {
                val token = prefs.fcmToken ?: return true
                api.registerDevice(DeviceReq(token, "${Build.MANUFACTURER} ${Build.MODEL}"))
            }

            OutKind.CONSENT -> {
                val version = prefs.consentVersion ?: return true
                api.consent(ConsentReq(version, prefs.consentAt.takeIf { it > 0 }?.toIso() ?: now().toIso(), prefs.consentScopes.sorted()))
            }

            OutKind.PANIC -> {
                val p = db.panics().get(item.refId) ?: return true
                val res = api.panic(
                    PanicReq(p.id, p.lat, p.lng, p.accuracyM, p.battery, p.createdAt.toIso(), p.trackSessionId, p.smsSent),
                )
                db.panics().upsert(p.copy(sent = true, liveUrl = res.liveUrl, status = res.panic.status))
                p.trackSessionId?.let { db.tracks().setLiveUrl(it, res.liveUrl) }
                Notifications.panicDelivered(applicationContext, res.recipients)
            }

            OutKind.PANIC_STATUS -> api.panicStatus(item.refId, StatusReq(item.payload ?: return true))

            OutKind.TRACK_START -> {
                val s = db.tracks().session(item.refId) ?: return true
                val ids = s.recipientIds.split(',').filter { it.isNotBlank() }
                val res = api.startTrack(TrackReq(s.id, s.startedAt.toIso(), ids))
                db.tracks().setLiveUrl(s.id, res.liveUrl)
            }

            OutKind.POINTS -> {
                val s = db.tracks().session(item.refId) ?: return true
                // Sesi harus terdaftar dulu (TRACK_START/PANIC berprioritas lebih tinggi)
                if (s.kind == "track" && Locator.outbox.has(OutKind.TRACK_START, s.id)) return false
                if (s.kind == "panic" && s.panicId != null && Locator.outbox.has(OutKind.PANIC, s.panicId)) return false
                while (true) {
                    val batch = db.tracks().unsentPoints(s.id, 500)
                    if (batch.isEmpty()) break
                    api.points(
                        s.id,
                        PointsReq(batch.map { PointDto(it.id, it.lat, it.lng, it.accuracyM, it.speed, it.battery, it.recordedAt.toIso()) }),
                    )
                    db.tracks().markSent(batch.map { it.id })
                }
            }

            OutKind.TRACK_STOP -> {
                val s = db.tracks().session(item.refId) ?: return true
                if (db.tracks().unsentPoints(s.id, 1).isNotEmpty()) {
                    Locator.outbox.enqueue(OutKind.POINTS, s.id)
                    return false
                }
                api.stopTrack(s.id, StopReq((s.endedAt ?: now()).toIso()))
            }

            OutKind.REPORT -> {
                if (prefs.largeFilesWifiOnly && isMetered()) return false
                val r = db.reports().get(item.refId) ?: return true
                val file = File(r.photoPath)
                if (!file.exists()) return true
                val meta = json.encodeToString(ReportMeta(r.id, r.description, r.category, r.lat, r.lng, r.createdAt.toIso()))
                val res = api.sendReport(
                    meta.toRequestBody("application/json".toMediaType()),
                    MultipartBody.Part.createFormData("photo", file.name, file.asRequestBody("image/jpeg".toMediaType())),
                )
                db.reports().setStatus(r.id, res.report.status, null)
                Notifications.simple(applicationContext, "Laporan terkirim", r.description.take(80))
            }

            OutKind.CALENDAR -> {
                val req = json.decodeFromString(CalendarReq.serializer(), item.payload ?: return true)
                val ev = api.createEvent(req)
                Notifications.simple(applicationContext, "Jadwal masuk Google Calendar", ev.title)
            }

            OutKind.NOTE -> pushNote(item.refId)
        }
        return true
    }

    private fun isMetered(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.isActiveNetworkMetered
    }

    private suspend fun onDropped(item: OutboxEntity) {
        if (item.kind == OutKind.REPORT.name) {
            db.reports().setStatus(item.refId, "ditolak", "Ditolak server: periksa isi laporan")
        }
    }

    // ---------------- Catatan ----------------

    private suspend fun noteDto(n: NoteEntity, segments: List<SegmentEntity>) = NoteDto(
        id = n.id,
        title = n.title,
        type = n.type,
        startedAt = n.startedAt.toIso(),
        durationS = n.durationS,
        summary = n.summary,
        keywords = n.keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        updatedAt = n.updatedAt.toIso(),
        deleted = n.deleted,
        segments = if (n.deleted) emptyList() else segments.map { SegmentDto(it.id, it.startMs, it.endMs, it.text) },
    )

    private suspend fun pushNote(noteId: String) {
        val n = db.notes().get(noteId) ?: return
        if (n.status == "merekam" || n.status == "memproses" || n.status == "menunggu_model") return
        val res = api.syncNotes(SyncReq(prefs.notesSince, listOf(noteDto(n, db.segments().forNote(n.id)))))
        if (n.id in res.accepted) db.notes().markSynced(n.id, n.updatedAt)
        applyRemote(res.notes)
    }

    /** Tarik catatan yang berubah di server (mis. dari HP lain milik pengguna yang sama). */
    private suspend fun pullNotes() {
        var guard = 0
        do {
            val res = api.syncNotes(SyncReq(prefs.notesSince, emptyList()))
            applyRemote(res.notes)
            res.nextSince?.let { prefs.notesSince = it }
        } while (res.hasMore && guard++ < 20)
    }

    private suspend fun applyRemote(notes: List<NoteDto>) {
        for (r in notes) {
            val local = db.notes().get(r.id)
            val remoteUpdated = r.updatedAt.parseIso()
            if (local != null && (local.updatedAt >= remoteUpdated || !local.synced)) continue // versi lokal lebih baru / belum terkirim
            val dir = local?.audioDir ?: File(applicationContext.filesDir, "notes/${r.id}").absolutePath
            db.notes().upsert(
                NoteEntity(
                    id = r.id,
                    title = r.title,
                    type = r.type,
                    startedAt = r.startedAt.parseIso(),
                    durationS = r.durationS,
                    status = "selesai",
                    summary = r.summary,
                    keywords = r.keywords.joinToString(","),
                    audioDir = dir,
                    chunkCount = local?.chunkCount ?: 0,
                    updatedAt = remoteUpdated,
                    deleted = r.deleted,
                    synced = true,
                ),
            )
            db.segments().deleteForNote(r.id)
            if (!r.deleted) db.segments().insertAll(r.segments.map { SegmentEntity(it.id, r.id, it.startMs, it.endMs, it.text) })
        }
    }

    private companion object {
        const val TAG = "EverestSync"
    }
}
