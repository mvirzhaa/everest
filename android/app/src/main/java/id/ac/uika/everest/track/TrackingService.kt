package id.ac.uika.everest.track

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import id.ac.uika.everest.Locator
import id.ac.uika.everest.R
import id.ac.uika.everest.data.local.LocationPointEntity
import id.ac.uika.everest.data.local.OutboxEntity
import id.ac.uika.everest.data.remote.PointDto
import id.ac.uika.everest.data.remote.PointsReq
import id.ac.uika.everest.panic.PanicManager
import id.ac.uika.everest.push.Notifications
import id.ac.uika.everest.sync.OutKind
import id.ac.uika.everest.util.batteryPercent
import id.ac.uika.everest.util.isOnline
import id.ac.uika.everest.util.newId
import id.ac.uika.everest.util.now
import id.ac.uika.everest.util.toIso
import id.ac.uika.everest.widget.WidgetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mencatat lokasi setiap 10 detik sampai dimatikan. Titik selalu disimpan di HP dulu;
 * saat online langsung dikirim, saat offline dikirim sekaligus begitu sinyal kembali.
 */
class TrackingService : LifecycleService() {
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var sessionId: String? = null
    private var kind: String = "track"
    private val sendLock = Mutex()
    private var warnedBattery = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val sid = sessionId ?: return
            result.locations.forEach { onLocation(sid, it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopTracking()
            ACTION_SAFE -> lifecycleScope.launch {
                PanicManager.markSafe(this@TrackingService)
                stopTracking()
            }
            else -> {
                val sid = intent?.getStringExtra(EXTRA_SESSION) ?: Locator.prefs.trackSessionId
                if (sid == null) {
                    stopSelf()
                } else if (sid != sessionId) {
                    startTracking(sid, intent?.getStringExtra(EXTRA_KIND) ?: "track")
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    @SuppressLint("MissingPermission")
    private fun startTracking(sid: String, k: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "izin lokasi belum diberikan")
            stopSelf()
            return
        }
        // Ganti sesi (mis. panic saat sedang track biasa): tutup sesi lama
        sessionId?.let { old -> lifecycleScope.launch { closeSession(old) } }
        sessionId = sid
        kind = k
        ServiceCompat.startForeground(
            this,
            Notifications.ID_TRACKING,
            notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        Locator.prefs.trackSessionId = sid
        WidgetState.refresh(this)

        lifecycleScope.launch(Dispatchers.IO) {
            // Pastikan ada item antrean POINTS agar titik offline terkirim walau servis sudah mati
            Locator.db.outbox().insertIgnore(OutboxEntity(kind = OutKind.POINTS.name, refId = sid, priority = OutKind.POINTS.priority))
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()
        fused.removeLocationUpdates(callback)
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    private fun onLocation(sid: String, loc: Location) {
        val battery = batteryPercent(this)
        lifecycleScope.launch(Dispatchers.IO) {
            Locator.db.tracks().insertPoint(
                LocationPointEntity(
                    id = newId(),
                    sessionId = sid,
                    lat = loc.latitude,
                    lng = loc.longitude,
                    accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                    speed = if (loc.hasSpeed()) loc.speed else null,
                    battery = battery,
                    recordedAt = loc.time.takeIf { it > 0 } ?: now(),
                ),
            )
            if (battery != null && battery < 20 && !warnedBattery) {
                warnedBattery = true
                Notifications.simple(this@TrackingService, "Baterai di bawah 20%", "Berbagi lokasi tetap berjalan. Penerima melihat sisa baterai Anda.")
            }
            sendNow(sid)
        }
    }

    /** Kirim langsung bila online dan sesi sudah terdaftar di server; selain itu biarkan antrean yang menangani. */
    private suspend fun sendNow(sid: String) {
        if (!isOnline(this)) return
        val s = Locator.db.tracks().session(sid) ?: return
        if (s.kind == "track" && Locator.outbox.has(OutKind.TRACK_START, sid)) return
        if (s.kind == "panic" && s.panicId != null && Locator.outbox.has(OutKind.PANIC, s.panicId)) return
        if (!sendLock.tryLock()) return
        try {
            val batch = Locator.db.tracks().unsentPoints(sid, 500)
            if (batch.isEmpty()) return
            runCatching {
                Locator.api.points(sid, PointsReq(batch.map { PointDto(it.id, it.lat, it.lng, it.accuracyM, it.speed, it.battery, it.recordedAt.toIso()) }))
            }.onSuccess { Locator.db.tracks().markSent(batch.map { it.id }) }
        } finally {
            sendLock.unlock()
        }
    }

    private suspend fun closeSession(sid: String) {
        Locator.db.tracks().end(sid, now())
        Locator.outbox.enqueue(OutKind.POINTS, sid)
        Locator.outbox.enqueue(OutKind.TRACK_STOP, sid)
    }

    private fun stopTracking() {
        fused.removeLocationUpdates(callback)
        val sid = sessionId ?: Locator.prefs.trackSessionId
        sessionId = null
        lifecycleScope.launch(Dispatchers.IO) {
            sid?.let { closeSession(it) }
            Locator.prefs.trackSessionId = null
            WidgetState.refresh(this@TrackingService)
            ServiceCompat.stopForeground(this@TrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        super.onDestroy()
    }

    private fun notification() = NotificationCompat.Builder(this, Notifications.CH_TRACKING)
        .setSmallIcon(R.drawable.ic_track)
        .setContentTitle(if (kind == "panic") "PANIC aktif · lokasi dikirim tiap 10 detik" else "Membagikan lokasi live")
        .setContentText(if (kind == "panic") "Kontak darurat dan satpam memantau posisi Anda" else "Lokasi dikirim tiap 10 detik ke kontak pilihan")
        .setOngoing(true)
        .setContentIntent(Notifications.openApp(this, "keamanan"))
        .apply {
            if (kind == "panic") {
                color = 0xFFC0352B.toInt()
                addAction(R.drawable.ic_stop, "Saya aman", PendingIntent.getService(this@TrackingService, 3, intent(this@TrackingService, ACTION_SAFE), PendingIntent.FLAG_IMMUTABLE))
            } else {
                addAction(R.drawable.ic_stop, "Berhenti", PendingIntent.getService(this@TrackingService, 2, intent(this@TrackingService, ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
            }
        }
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    companion object {
        private const val TAG = "EverestTrack"
        private const val INTERVAL_MS = 10_000L
        const val EXTRA_SESSION = "session"
        const val EXTRA_KIND = "kind"
        private const val ACTION_STOP = "id.ac.uika.everest.track.STOP"
        private const val ACTION_SAFE = "id.ac.uika.everest.track.SAFE"

        private fun intent(ctx: Context, action: String? = null) = Intent(ctx, TrackingService::class.java).apply { action?.let { setAction(it) } }

        /** Mulai berbagi lokasi untuk sesi yang sudah disimpan di Room. Panggil dari activity yang terlihat. */
        fun start(ctx: Context, sessionId: String, kind: String) {
            ContextCompat.startForegroundService(ctx, intent(ctx).putExtra(EXTRA_SESSION, sessionId).putExtra(EXTRA_KIND, kind))
        }

        fun stop(ctx: Context) {
            if (Locator.prefs.trackSessionId == null) return
            runCatching { ctx.startService(intent(ctx, ACTION_STOP)) }
        }

        fun isTracking() = Locator.prefs.trackSessionId != null

        /** Lokasi saat ini (akurasi tinggi, maks. [timeoutMs]); jatuh ke lokasi terakhir yang diketahui. */
        @SuppressLint("MissingPermission")
        suspend fun currentLocation(ctx: Context, timeoutMs: Long = 5_000): Location? {
            val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!granted) return null
            val client = LocationServices.getFusedLocationProviderClient(ctx)
            val cts = CancellationTokenSource()
            val fresh = withTimeoutOrNull(timeoutMs) {
                runCatching { client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await() }.getOrNull()
            }
            if (fresh == null) cts.cancel()
            return fresh ?: runCatching { client.lastLocation.await() }.getOrNull()
        }
    }
}
