package id.ac.uika.everest.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

val ID_LOCALE: Locale = Locale.forLanguageTag("id-ID")
val ZONE: ZoneId get() = ZoneId.systemDefault()

fun newId(): String = UUID.randomUUID().toString()
fun now(): Long = System.currentTimeMillis()

fun Long.toIso(): String = Instant.ofEpochMilli(this).toString()
fun String.parseIso(): Long = runCatching { Instant.parse(this).toEpochMilli() }
    .recoverCatching { java.time.OffsetDateTime.parse(this).toInstant().toEpochMilli() }
    .getOrDefault(0L)

private val dateFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", ID_LOCALE)
private val timeFmt = DateTimeFormatter.ofPattern("HH.mm", ID_LOCALE)
private val dateTimeFmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH.mm", ID_LOCALE)

fun Long.formatDate(): String = dateFmt.format(Instant.ofEpochMilli(this).atZone(ZONE))
fun Long.formatTime(): String = timeFmt.format(Instant.ofEpochMilli(this).atZone(ZONE))
fun Long.formatDateTime(): String = dateTimeFmt.format(Instant.ofEpochMilli(this).atZone(ZONE))

fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatOffset(ms: Long): String = formatDuration((ms / 1000).toInt())

fun batteryPercent(context: Context): Int? {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    val v = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: Int.MIN_VALUE
    if (v in 0..100) return v
    val i: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    return if (level >= 0 && scale > 0) level * 100 / scale else null
}

fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

fun mapsUrl(lat: Double, lng: Double) = "https://maps.google.com/?q=$lat,$lng"
