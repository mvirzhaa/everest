package id.ac.uika.everest.record

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import id.ac.uika.everest.Locator
import id.ac.uika.everest.data.local.ChunkEntity
import id.ac.uika.everest.data.local.NoteEntity
import id.ac.uika.everest.data.local.SegmentEntity
import id.ac.uika.everest.push.Notifications
import id.ac.uika.everest.sync.OutKind
import id.ac.uika.everest.whisper.ModelDownloadWorker
import id.ac.uika.everest.whisper.Models
import id.ac.uika.everest.whisper.WhisperEngine
import id.ac.uika.everest.util.newId
import id.ac.uika.everest.util.now
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Potongan per catatan di atas angka ini (≈30 menit) dianggap rekaman panjang. */
private const val LONG_RECORDING_CHUNKS = 60

/**
 * Menjalankan whisper.cpp di HP atas potongan 30 detik secara berurutan.
 * Satu eksekusi dibatasi ±8 menit (batas WorkManager 10 menit); sisanya dilanjutkan eksekusi berikutnya.
 */
class TranscriptionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val db = Locator.db

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        // Catatan yang sudah berhenti dan tidak punya potongan tersisa (mis. rekaman sangat pendek)
        for (n in db.notes().pending()) if (db.chunks().remaining(n.id) == 0) finalize(n)

        val model = Models.available(applicationContext)
        if (model == null) {
            // Tandai catatan menunggu model, lalu unduh. Unduhan selesai akan memicu transkripsi lagi.
            db.chunks().allPending().map { it.noteId }.distinct().forEach { id ->
                val n = db.notes().get(id)
                if (n != null && n.status != "merekam") db.notes().setStatus(id, "menunggu_model")
            }
            ModelDownloadWorker.enqueue(applicationContext)
            return@withContext Result.success()
        }

        val deadline = now() + 8 * 60_000
        var skippedForCharging = false
        var outOfTime = false
        var engine: WhisperEngine? = null
        try {
            while (!isStopped && now() < deadline) {
                val pending = db.chunks().allPending()
                if (pending.isEmpty()) break
                var progressed = false
                for (chunk in pending) {
                    if (isStopped || now() >= deadline) break
                    val note = db.notes().get(chunk.noteId)
                    if (note == null || note.deleted) {
                        db.chunks().markDone(chunk.noteId, chunk.idx)
                        continue
                    }
                    if (note.status != "merekam" && note.chunkCount > LONG_RECORDING_CHUNKS &&
                        Locator.prefs.processLongOnCharge && !isCharging()
                    ) {
                        skippedForCharging = true
                        continue
                    }
                    if (engine == null) engine = WhisperEngine.load(model)
                    processChunk(engine, chunk)
                    progressed = true
                    maybeFinalize(chunk.noteId)
                }
                if (!progressed) break
            }
            outOfTime = now() >= deadline || isStopped
        } catch (e: Throwable) {
            Log.e(TAG, "transkripsi gagal", e)
            return@withContext Result.retry()
        } finally {
            engine?.close()
        }

        if (skippedForCharging) TranscriptionScheduler.whenCharging(applicationContext)
        // Waktu eksekusi habis tapi masih ada potongan: lanjutkan di eksekusi berikutnya
        if (outOfTime && db.chunks().allPending().isNotEmpty()) return@withContext Result.retry()
        Result.success()
    }

    private suspend fun processChunk(engine: WhisperEngine, chunk: ChunkEntity) {
        val file = File(chunk.path)
        if (!file.exists()) {
            db.chunks().markDone(chunk.noteId, chunk.idx)
            return
        }
        if (file.extension == "wav") {
            val pcm = readWavAsFloats(file)
            if (speechRatio(pcm) >= 0.02f) {
                val segs = engine.transcribe(pcm, "id")
                // Hapus hasil lama potongan ini bila diproses ulang
                db.segments().deleteRange(chunk.noteId, chunk.offsetMs, chunk.offsetMs + chunk.durationMs)
                db.segments().insertAll(
                    segs.map {
                        SegmentEntity(
                            id = newId(),
                            noteId = chunk.noteId,
                            startMs = chunk.offsetMs + it.startMs.coerceAtMost(chunk.durationMs),
                            endMs = chunk.offsetMs + it.endMs.coerceAtMost(chunk.durationMs),
                            text = it.text,
                        )
                    },
                )
            }
            // Simpan audio sebagai AAC (lebih hemat ruang), hapus WAV bila berhasil
            val m4a = File(file.parentFile, file.nameWithoutExtension + ".m4a")
            val ok = runCatching { encodeWavToM4a(file, m4a) }.isSuccess && m4a.length() > 0
            if (ok) {
                db.chunks().insert(chunk.copy(path = m4a.absolutePath, done = true))
                file.delete()
                return
            }
        }
        db.chunks().markDone(chunk.noteId, chunk.idx)
    }

    private suspend fun maybeFinalize(noteId: String) {
        val note = db.notes().get(noteId) ?: return
        if (note.status == "merekam" || note.status == "selesai") return
        if (db.chunks().remaining(noteId) > 0) return
        finalize(note)
    }

    private suspend fun finalize(note: NoteEntity) {
        val segs = db.segments().forNote(note.id)
        val text = segs.joinToString(" ") { it.text }
        val keywords = Keywords.extract(text)
        val updated = note.copy(
            status = "selesai",
            keywords = keywords.joinToString(","),
            summary = note.summary ?: text.take(280).takeIf { it.isNotBlank() },
            updatedAt = now(),
            synced = false,
        )
        db.notes().upsert(updated)
        Locator.outbox.enqueue(OutKind.NOTE, note.id)
        Notifications.simple(
            applicationContext,
            "Transkrip selesai",
            if (segs.isEmpty()) "${note.title}: tidak ada ucapan yang terdeteksi." else "${note.title} · ${segs.size} bagian · ${keywords.take(3).joinToString(", ")}",
            route = "catatan/${note.id}",
            id = note.id.hashCode(),
        )
    }

    private fun isCharging(): Boolean {
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }

    private companion object {
        const val TAG = "EverestTranscribe"
    }
}

object TranscriptionScheduler {
    private const val NOW = "everest-transcribe"
    private const val CHARGING = "everest-transcribe-charging"

    fun kick(context: Context) {
        val req = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun whenCharging(context: Context) {
        val req = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(CHARGING, ExistingWorkPolicy.KEEP, req)
    }

    /** Dipanggil saat aplikasi mulai: lanjutkan potongan yang belum diproses. */
    suspend fun resumePending(context: Context) {
        val db = Locator.db
        // Rekaman yang terputus (servis mati saat merekam): lanjutkan sebagai catatan biasa
        if (Locator.prefs.recordingNoteId == null) {
            for (n in db.notes().orphanRecordings()) {
                val chunks = db.chunks().forNote(n.id)
                val durS = ((chunks.lastOrNull()?.let { it.offsetMs + it.durationMs } ?: 0L) / 1000).toInt()
                db.notes().upsert(n.copy(status = "memproses", durationS = durS, chunkCount = chunks.size, updatedAt = System.currentTimeMillis()))
            }
        }
        if (db.chunks().allPending().isNotEmpty() || db.notes().pending().isNotEmpty()) kick(context)
    }
}
