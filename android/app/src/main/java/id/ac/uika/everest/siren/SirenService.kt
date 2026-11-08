package id.ac.uika.everest.siren

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import id.ac.uika.everest.Locator
import id.ac.uika.everest.R
import id.ac.uika.everest.push.Notifications
import id.ac.uika.everest.widget.WidgetState

/**
 * Membunyikan sirine sekeras mungkin lewat jalur ALARM (tetap berbunyi saat HP mode senyap/getar),
 * sampai dimatikan dari widget, notifikasi, atau aplikasi. Lampu senter ikut berkedip bila ada.
 * Volume alarm dikembalikan seperti semula setelah sirine mati.
 */
class SirenService : Service() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var savedVolume = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSiren()
            else -> if (!running) startSiren()
        }
        return START_NOT_STICKY
    }

    private fun startSiren() {
        ServiceCompat.startForeground(
            this,
            Notifications.ID_SIREN,
            notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        running = true
        active = true
        Locator.prefs.sirenOn = true
        WidgetState.refresh(this)

        val am = getSystemService(AudioManager::class.java)
        runCatching {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }

        val rate = 44_100
        val buf = ShortArray(rate / 20) // 50 ms
        val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBytes, buf.size * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        val tone = SirenTone(rate)
        thread = Thread({
            val torch = Torch(this)
            var n = 0
            try {
                t.play()
                while (running) {
                    tone.fill(buf)
                    t.write(buf, 0, buf.size)
                    if (n++ % 4 == 0) torch.toggle() // kedip ±5× per detik
                }
            } catch (e: Exception) {
                Log.e(TAG, "sirine berhenti: ${e.message}")
            } finally {
                torch.off()
                runCatching { t.stop() }
                t.release()
            }
        }, "everest-siren").also { it.start() }
    }

    private fun stopSiren() {
        running = false
        thread?.join(500)
        thread = null
        track = null
        restoreVolume()
        active = false
        Locator.prefs.sirenOn = false
        WidgetState.refresh(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun restoreVolume() {
        if (savedVolume < 0) return
        runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
        savedVolume = -1
    }

    override fun onDestroy() {
        if (running) {
            running = false
            thread?.join(500)
            restoreVolume()
        }
        active = false
        Locator.prefs.sirenOn = false
        WidgetState.refresh(this)
        super.onDestroy()
    }

    private fun notification() = NotificationCompat.Builder(this, Notifications.CH_SIREN)
        .setSmallIcon(R.drawable.ic_siren)
        .setColor(0xFFFFB300.toInt())
        .setContentTitle("Sirine menyala")
        .setContentText("Tekan tombol Sirine di widget atau Matikan di sini")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setContentIntent(Notifications.openApp(this, "beranda"))
        .addAction(R.drawable.ic_stop, "Matikan sirine", PendingIntent.getService(this, 7, stopIntent(this), PendingIntent.FLAG_IMMUTABLE))
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    /** Kedipan lampu senter (abaikan bila HP tidak punya flash atau kamera sedang dipakai). */
    private class Torch(context: Context) {
        private val cm = context.getSystemService(CameraManager::class.java)
        private val id: String? = runCatching {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull()
        private var on = false

        fun toggle() {
            val cam = id ?: return
            on = !on
            runCatching { cm.setTorchMode(cam, on) }
        }

        fun off() {
            val cam = id ?: return
            on = false
            runCatching { cm.setTorchMode(cam, false) }
        }
    }

    companion object {
        private const val TAG = "EverestSiren"
        private const val ACTION_START = "id.ac.uika.everest.siren.START"
        private const val ACTION_STOP = "id.ac.uika.everest.siren.STOP"

        private fun stopIntent(ctx: Context) = Intent(ctx, SirenService::class.java).setAction(ACTION_STOP)

        /** Panggil dari activity yang terlihat (aturan Android 14 untuk foreground service). */
        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SirenService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(stopIntent(ctx)) }
        }

        /** Benar hanya bila servis sirine berjalan di proses ini (hilang otomatis bila proses mati). */
        @Volatile var active = false
            private set

        fun isOn() = active
    }
}
