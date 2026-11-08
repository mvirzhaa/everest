package id.ac.uika.everest.panic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import id.ac.uika.everest.Locator
import id.ac.uika.everest.data.local.OutboxEntity
import id.ac.uika.everest.data.local.PanicEntity
import id.ac.uika.everest.data.local.TrackSessionEntity
import id.ac.uika.everest.data.remote.PanicReq
import id.ac.uika.everest.sync.OutKind
import id.ac.uika.everest.sync.SyncScheduler
import id.ac.uika.everest.track.TrackingService
import id.ac.uika.everest.util.batteryPercent
import id.ac.uika.everest.util.formatTime
import id.ac.uika.everest.util.isOnline
import id.ac.uika.everest.util.mapsUrl
import id.ac.uika.everest.util.newId
import id.ac.uika.everest.util.now
import id.ac.uika.everest.util.toIso
import kotlinx.coroutines.withTimeoutOrNull

data class PanicOutcome(val deliveredOnline: Boolean, val recipients: Int, val smsSent: Int, val hasLocation: Boolean)

/**
 * Alur panic: ambil lokasi → simpan → mulai tracking 10 detik → kirim ke server
 * (kontak darurat + satpam). Bila gagal/offline: SMS langsung ke kontak darurat
 * dan sinyal tetap diantrekan untuk dikirim begitu online.
 */
object PanicManager {
    private const val TAG = "EverestPanic"

    /** Harus dipanggil dari activity yang terlihat (memulai servis lokasi). */
    suspend fun trigger(context: Context): PanicOutcome {
        val db = Locator.db
        val id = newId()
        val trackId = newId()
        val createdAt = now()
        val loc = TrackingService.currentLocation(context, 5_000)
        val battery = batteryPercent(context)

        db.tracks().upsertSession(TrackSessionEntity(id = trackId, kind = "panic", panicId = id, startedAt = createdAt))
        var panic = PanicEntity(
            id = id,
            createdAt = createdAt,
            lat = loc?.latitude,
            lng = loc?.longitude,
            accuracyM = loc?.takeIf { it.hasAccuracy() }?.accuracy,
            battery = battery,
            trackSessionId = trackId,
        )
        db.panics().upsert(panic)
        Locator.prefs.activePanicId = id
        TrackingService.start(context, trackId, "panic")
        // Catat di antrean dulu (tanpa memicu sinkron) supaya tidak hilang bila aplikasi mati di tengah jalan
        db.outbox().insertIgnore(OutboxEntity(kind = OutKind.PANIC.name, refId = id, priority = OutKind.PANIC.priority))

        // Coba kirim langsung (lebih cepat dari antrean); maksimal 8 detik
        var recipients = 0
        val delivered = if (isOnline(context)) {
            withTimeoutOrNull(8_000) {
                runCatching {
                    Locator.api.panic(PanicReq(id, panic.lat, panic.lng, panic.accuracyM, battery, createdAt.toIso(), trackId, false))
                }.onFailure { Log.w(TAG, "kirim panic langsung gagal: ${it.message}") }.getOrNull()
            }
        } else null

        if (delivered != null) {
            recipients = delivered.recipients
            panic = panic.copy(sent = true, liveUrl = delivered.liveUrl)
            db.panics().upsert(panic)
            db.tracks().setLiveUrl(trackId, delivered.liveUrl)
            db.outbox().deleteByRef(OutKind.PANIC.name, id)
            Locator.outbox.enqueue(OutKind.POINTS, trackId)
            return PanicOutcome(true, recipients, 0, loc != null)
        }

        // Offline / server tak terjangkau: SMS cadangan ke kontak darurat yang punya nomor HP
        val sms = sendSms(context, panic)
        if (sms > 0) db.panics().upsert(panic.copy(smsSent = true))
        SyncScheduler.kick(context) // kirim ke server begitu sinyal kembali
        return PanicOutcome(false, 0, sms, loc != null)
    }

    private suspend fun sendSms(context: Context, p: PanicEntity): Int {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return 0
        val phones = Locator.db.contacts().all().mapNotNull { it.phone?.filter { c -> c.isDigit() || c == '+' } }.filter { it.length >= 6 }.distinct()
        if (phones.isEmpty()) return 0
        val name = Locator.prefs.user?.name ?: "Pengguna Everest"
        val where = if (p.lat != null && p.lng != null) {
            "Lokasi: ${mapsUrl(p.lat, p.lng)}" + (p.accuracyM?.let { " (±${it.toInt()} m)" } ?: "")
        } else {
            "Lokasi belum terdeteksi."
        }
        val text = "DARURAT! $name menekan tombol panic Everest pukul ${p.createdAt.formatTime()}. $where" +
            (p.battery?.let { " Baterai $it%." } ?: "")
        val sm: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        var sent = 0
        for (phone in phones) {
            runCatching {
                sm.sendMultipartTextMessage(phone, null, sm.divideMessage(text), null, null)
                sent++
            }.onFailure { Log.w(TAG, "SMS ke $phone gagal: ${it.message}") }
        }
        return sent
    }

    /** Pengguna menyatakan aman: tutup kejadian (diantrekan bila offline) dan hentikan tracking. */
    suspend fun markSafe(context: Context) {
        val id = Locator.prefs.activePanicId ?: return
        Locator.db.panics().setStatus(id, "aman")
        Locator.outbox.enqueue(OutKind.PANIC_STATUS, id, "aman")
        Locator.prefs.activePanicId = null
        TrackingService.stop(context)
    }

    /** Batal dalam hitung mundur tidak pernah membuat data apa pun. */
    fun activePanicId(): String? = Locator.prefs.activePanicId
}
