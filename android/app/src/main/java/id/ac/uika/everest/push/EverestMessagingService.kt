package id.ac.uika.everest.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import id.ac.uika.everest.Locator
import id.ac.uika.everest.R
import id.ac.uika.everest.live.LiveMapActivity
import id.ac.uika.everest.sync.OutKind
import id.ac.uika.everest.util.formatTime
import id.ac.uika.everest.util.parseIso
import kotlinx.coroutines.launch

/** Menerima push dari server Everest (data message prioritas tinggi). */
class EverestMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Locator.prefs.fcmToken = token
        if (Locator.prefs.isLoggedIn) Locator.appScope.launch { Locator.outbox.enqueue(OutKind.DEVICE, "fcm") }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!Locator.prefs.isLoggedIn) return
        val d = message.data
        when (d["type"]) {
            "panic" -> PanicAlerts.show(this, PanicAlert.from(d))
            "panic_escalated" -> {
                // Untuk PENGIRIM panic: belum ada petugas yang merespons, panic sedang diteruskan
                if (!Notifications.canPost(this)) return
                val text = d["message"].orEmpty().ifBlank { "Panic Anda belum direspons dan sedang diteruskan ke petugas lain." }
                val n = NotificationCompat.Builder(this, Notifications.CH_PANIC)
                    .setSmallIcon(R.drawable.ic_panic)
                    .setColor(0xFFC0352B.toInt())
                    .setContentTitle(if (d["level"] == "2") "Panic diteruskan ke admin keamanan" else "Panic diteruskan ke semua satpam")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setContentIntent(Notifications.openApp(this, "keamanan"))
                    .addAction(R.drawable.ic_panic, "Telepon 112", PendingIntent.getActivity(this, 112, Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:112")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
                    .setAutoCancel(true)
                    .build()
                @Suppress("MissingPermission")
                NotificationManagerCompat.from(this).notify(Notifications.ID_PANIC_SENT, n)
            }
            "panic_status" -> {
                PanicAlerts.cancel(this, d["panic_id"].orEmpty())
                val status = d["status"].orEmpty()
                val note = d["note"].orEmpty()
                Notifications.simple(
                    this,
                    when (status) {
                        "diterima" -> "Satpam menuju lokasi"
                        "ditangani" -> "Kejadian ditangani"
                        "aman" -> "Dinyatakan aman"
                        "dibatalkan" -> "Panic dibatalkan"
                        else -> "Status panic: $status"
                    },
                    listOf("Oleh ${d["by_name"].orEmpty()}", note).filter { it.isNotBlank() }.joinToString(" · "),
                    route = "keamanan",
                )
                Locator.appScope.launch { Locator.db.panics().setStatus(d["panic_id"].orEmpty(), status) }
            }
            "track_start" -> {
                val name = d["user_name"].orEmpty()
                val live = d["live_url"].orEmpty()
                if (!Notifications.canPost(this)) return
                val n = NotificationCompat.Builder(this, Notifications.CH_GENERAL)
                    .setSmallIcon(R.drawable.ic_track)
                    .setContentTitle("$name membagikan lokasi live")
                    .setContentText("Ketuk untuk melihat posisinya di peta")
                    .setContentIntent(LiveMapActivity.pending(this, live, name, live.hashCode()))
                    .setAutoCancel(true)
                    .build()
                @Suppress("MissingPermission")
                NotificationManagerCompat.from(this).notify(d["session_id"].hashCode(), n)
            }
            "track_stop" -> Notifications.simple(this, "${d["user_name"].orEmpty()} berhenti membagikan lokasi", "Sesi berbagi lokasi selesai", id = d["session_id"].hashCode())
            "report_status" -> {
                val status = d["status"].orEmpty()
                val note = d["note"].orEmpty().ifBlank { null }
                Locator.appScope.launch { Locator.db.reports().setStatus(d["report_id"].orEmpty(), status, note) }
                Notifications.simple(
                    this,
                    when (status) {
                        "diproses" -> "Laporan Anda sedang diproses"
                        "selesai" -> "Laporan Anda selesai ditangani"
                        "ditolak" -> "Laporan Anda ditolak"
                        else -> "Status laporan: $status"
                    },
                    note ?: "Ketuk untuk melihat detail",
                    route = "laporan/${d["report_id"].orEmpty()}",
                )
            }
        }
    }
}

data class PanicAlert(
    val panicId: String,
    val userName: String,
    val userPhone: String,
    val lat: Double?,
    val lng: Double?,
    val battery: String,
    val createdAt: Long,
    val mapsUrl: String,
    val liveUrl: String,
    val isGuard: Boolean,
    /** 0 = panic biasa; 1/2 = diteruskan karena belum ada yang menanggapi. */
    val escalation: Int = 0,
    val escalationText: String = "",
) {
    fun toIntent(ctx: Context) = Intent(ctx, PanicAlertActivity::class.java).apply {
        putExtra("panic_id", panicId)
        putExtra("user_name", userName)
        putExtra("user_phone", userPhone)
        putExtra("lat", lat ?: Double.NaN)
        putExtra("lng", lng ?: Double.NaN)
        putExtra("battery", battery)
        putExtra("created_at", createdAt)
        putExtra("maps_url", mapsUrl)
        putExtra("live_url", liveUrl)
        putExtra("is_guard", isGuard)
        putExtra("escalation", escalation)
        putExtra("escalation_text", escalationText)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        fun from(d: Map<String, String>) = PanicAlert(
            panicId = d["panic_id"].orEmpty(),
            userName = d["user_name"].orEmpty().ifBlank { "Seseorang" },
            userPhone = d["user_phone"].orEmpty(),
            lat = d["lat"]?.toDoubleOrNull(),
            lng = d["lng"]?.toDoubleOrNull(),
            battery = d["battery"].orEmpty(),
            createdAt = d["created_at"]?.parseIso()?.takeIf { it > 0 } ?: System.currentTimeMillis(),
            mapsUrl = d["maps_url"].orEmpty(),
            liveUrl = d["live_url"].orEmpty(),
            isGuard = d["role"] == "guard",
            escalation = d["escalation"]?.toIntOrNull() ?: 0,
            escalationText = d["escalation_text"].orEmpty(),
        )

        fun from(i: Intent) = PanicAlert(
            panicId = i.getStringExtra("panic_id").orEmpty(),
            userName = i.getStringExtra("user_name").orEmpty(),
            userPhone = i.getStringExtra("user_phone").orEmpty(),
            lat = i.getDoubleExtra("lat", Double.NaN).takeIf { !it.isNaN() },
            lng = i.getDoubleExtra("lng", Double.NaN).takeIf { !it.isNaN() },
            battery = i.getStringExtra("battery").orEmpty(),
            createdAt = i.getLongExtra("created_at", System.currentTimeMillis()),
            mapsUrl = i.getStringExtra("maps_url").orEmpty(),
            liveUrl = i.getStringExtra("live_url").orEmpty(),
            isGuard = i.getBooleanExtra("is_guard", false),
            escalation = i.getIntExtra("escalation", 0),
            escalationText = i.getStringExtra("escalation_text").orEmpty(),
        )
    }
}

object PanicAlerts {
    private fun notifId(panicId: String) = 5000 + (panicId.hashCode() and 0xFFF)

    fun show(ctx: Context, a: PanicAlert) {
        if (!Notifications.canPost(ctx)) {
            // Tanpa izin notifikasi, tetap coba tampilkan layar peringatan
            runCatching { ctx.startActivity(a.toIntent(ctx)) }
            return
        }
        val full = PendingIntent.getActivity(ctx, notifId(a.panicId), a.toIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(ctx, Notifications.CH_PANIC)
            .setSmallIcon(R.drawable.ic_panic)
            .setColor(0xFFC0352B.toInt())
            .setContentTitle(if (a.escalation > 0) "ESKALASI PANIC: ${a.userName}" else "PANIC: ${a.userName} butuh bantuan")
            .setContentText(
                (if (a.escalation > 0) "${a.escalationText.ifBlank { "Belum ada yang menanggapi" }}. " else "") +
                    "Pukul ${a.createdAt.formatTime()}${if (a.battery.isNotBlank()) " · baterai ${a.battery}%" else ""}. Ketuk untuk melihat lokasi.",
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(full)
        // Layar penuh seperti panggilan masuk bila diizinkan (Android 14+: perlu izin khusus)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()) b.setFullScreenIntent(full, true)
        if (a.mapsUrl.isNotBlank()) b.addAction(R.drawable.ic_track, "Buka Maps", Notifications.openUrl(ctx, a.mapsUrl, notifId(a.panicId) + 1))
        if (a.liveUrl.isNotBlank()) b.addAction(R.drawable.ic_track, "Peta live", LiveMapActivity.pending(ctx, a.liveUrl, "PANIC — ${a.userName}", notifId(a.panicId) + 2))
        if (a.isGuard) b.addAction(R.drawable.ic_panic, "Terima", NotificationActionReceiver.acceptIntent(ctx, a.panicId))
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(ctx).notify(notifId(a.panicId), b.build())
    }

    fun cancel(ctx: Context, panicId: String) = NotificationManagerCompat.from(ctx).cancel(notifId(panicId))
}
