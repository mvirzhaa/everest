package id.ac.uika.everest.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import id.ac.uika.everest.R
import id.ac.uika.everest.ui.MainActivity

object Notifications {
    const val CH_RECORDING = "recording"
    const val CH_TRACKING = "tracking"
    const val CH_PANIC = "panic_alert"
    const val CH_GENERAL = "general"
    const val CH_SYNC = "sync"
    const val CH_SIREN = "siren"

    const val ID_RECORDING = 1001
    const val ID_TRACKING = 1002
    const val ID_SYNC = 1003
    const val ID_PANIC_SENT = 1004
    const val ID_SIREN = 1005

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val panic = NotificationChannel(CH_PANIC, "Panic darurat", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Sinyal panic dari kontak atau warga kampus"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
            setSound(alarm, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannels(
            listOf(
                panic,
                NotificationChannel(CH_RECORDING, "Perekaman", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_TRACKING, "Berbagi lokasi", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_GENERAL, "Info", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_SYNC, "Sinkronisasi", NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(CH_SIREN, "Sirine", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) },
            ),
        )
    }

    fun canPost(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            android.os.Build.VERSION.SDK_INT < 33

    fun openApp(context: Context, route: String? = null): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) }
        }
        return PendingIntent.getActivity(context, route.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun openUrl(context: Context, url: String, requestCode: Int): PendingIntent {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun simple(context: Context, title: String, text: String, route: String? = null, id: Int = (title + text).hashCode()) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_GENERAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context, route))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(id, n)
    }

    fun panicDelivered(context: Context, recipients: Int) {
        simple(
            context,
            "Sinyal panic terkirim",
            if (recipients > 0) "Diterima server dan diteruskan ke $recipients penerima (kontak darurat dan satpam)." else "Diterima server.",
            route = "keamanan",
            id = ID_PANIC_SENT,
        )
    }
}
