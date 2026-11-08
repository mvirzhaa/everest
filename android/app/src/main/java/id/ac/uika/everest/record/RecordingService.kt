package id.ac.uika.everest.record

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import id.ac.uika.everest.Locator
import id.ac.uika.everest.R
import id.ac.uika.everest.data.local.ChunkEntity
import id.ac.uika.everest.data.local.NoteEntity
import id.ac.uika.everest.push.Notifications
import id.ac.uika.everest.util.formatDateTime
import id.ac.uika.everest.util.newId
import id.ac.uika.everest.util.now
import id.ac.uika.everest.widget.WidgetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Merekam suara 16 kHz mono ke potongan WAV 30 detik. Tiap potongan langsung masuk antrean
 * transkripsi, jadi teks sudah tersedia sebagian saat rekaman masih berjalan.
 */
class RecordingService : LifecycleService() {
    private var job: Job? = null
    @Volatile private var running = false
    private var noteId: String? = null

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            else -> if (!running) startRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "izin mikrofon belum diberikan")
            stopSelf()
            return
        }
        val id = newId()
        val startedAt = now()
        val dir = File(filesDir, "notes/$id").apply { mkdirs() }
        noteId = id
        running = true

        ServiceCompat.startForeground(
            this,
            Notifications.ID_RECORDING,
            notification(startedAt),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        Locator.prefs.recordingNoteId = id
        WidgetState.refresh(this)

        job = lifecycleScope.launch(Dispatchers.IO) {
            Locator.db.notes().upsert(
                NoteEntity(
                    id = id,
                    title = "Rekaman ${startedAt.formatDateTime()}",
                    startedAt = startedAt,
                    status = "merekam",
                    audioDir = dir.absolutePath,
                    updatedAt = startedAt,
                ),
            )
            captureLoop(id, dir)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureLoop(id: String, dir: File) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, SAMPLE_RATE / 5) // ±200 ms
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize * 2,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord gagal diinisialisasi")
            recorder.release()
            finish(id, 0, 0L)
            return
        }
        val chunkSamples = SAMPLE_RATE * CHUNK_SECONDS
        val buf = ShortArray(bufSize)
        var idx = 0
        var offsetMs = 0L
        var writer = WavWriter(File(dir, chunkName(idx)))
        recorder.startRecording()
        try {
            while (running && kotlin.coroutines.coroutineContext.isActive) {
                val n = recorder.read(buf, 0, buf.size)
                if (n <= 0) continue
                writer.write(buf, n)
                if (writer.samples >= chunkSamples) {
                    val durMs = writer.samples * 1000 / SAMPLE_RATE
                    writer.close()
                    saveChunk(id, idx, writer.file, offsetMs, durMs)
                    offsetMs += durMs
                    idx++
                    writer = WavWriter(File(dir, chunkName(idx)))
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            val durMs = writer.samples * 1000 / SAMPLE_RATE
            writer.close()
            if (durMs >= 500) {
                saveChunk(id, idx, writer.file, offsetMs, durMs)
                idx++
                offsetMs += durMs
            } else {
                writer.file.delete()
            }
            finish(id, idx, offsetMs)
        }
    }

    private suspend fun saveChunk(noteId: String, idx: Int, file: File, offsetMs: Long, durMs: Long) {
        Locator.db.chunks().insert(ChunkEntity(noteId, idx, file.absolutePath, offsetMs, durMs))
        TranscriptionScheduler.kick(applicationContext)
    }

    private suspend fun finish(id: String, chunks: Int, durationMs: Long) {
        val note = Locator.db.notes().get(id) ?: return
        Locator.db.notes().upsert(
            note.copy(status = "memproses", durationS = (durationMs / 1000).toInt(), chunkCount = chunks, updatedAt = now()),
        )
        TranscriptionScheduler.kick(applicationContext)
    }

    private fun stopRecording() {
        running = false
        val j = job
        // Tunggu loop menutup potongan terakhir dengan rapi sebelum servis berhenti
        lifecycleScope.launch(Dispatchers.IO) {
            j?.join()
            Locator.prefs.recordingNoteId = null
            WidgetState.refresh(this@RecordingService)
            ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (running) {
            running = false
            runBlocking { job?.join() }
            Locator.prefs.recordingNoteId = null
            WidgetState.refresh(this)
        }
        super.onDestroy()
    }

    private fun notification(startedAt: Long) = NotificationCompat.Builder(this, Notifications.CH_RECORDING)
        .setSmallIcon(R.drawable.ic_mic)
        .setContentTitle("Everest sedang merekam")
        .setContentText("Transkrip dibuat otomatis di HP")
        .setWhen(startedAt)
        .setUsesChronometer(true)
        .setOngoing(true)
        .setContentIntent(Notifications.openApp(this, "catatan"))
        .addAction(R.drawable.ic_stop, "Berhenti", PendingIntent.getService(this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE))
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    companion object {
        private const val TAG = "EverestRecord"
        private const val CHUNK_SECONDS = 30
        const val ACTION_START = "id.ac.uika.everest.record.START"
        const val ACTION_STOP = "id.ac.uika.everest.record.STOP"

        private fun chunkName(i: Int) = "chunk_%04d.wav".format(i)

        private fun stopIntent(ctx: Context) = Intent(ctx, RecordingService::class.java).setAction(ACTION_STOP)

        /** Harus dipanggil dari activity yang terlihat (aturan Android 14 untuk mikrofon). */
        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RecordingService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            if (Locator.prefs.recordingNoteId == null) return
            runCatching { ctx.startService(stopIntent(ctx)) }
        }

        fun isRecording() = Locator.prefs.recordingNoteId != null
    }
}
