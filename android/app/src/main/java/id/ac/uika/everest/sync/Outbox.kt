package id.ac.uika.everest.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import id.ac.uika.everest.data.local.AppDatabase
import id.ac.uika.everest.data.local.OutboxEntity
import java.util.concurrent.TimeUnit

/** Jenis item antrean beserta prioritas kirim (angka kecil = lebih dulu). */
enum class OutKind(val priority: Int) {
    DEVICE(0),
    CONSENT(0),
    PANIC(1),
    PANIC_STATUS(1),
    TRACK_START(2),
    POINTS(3),
    TRACK_STOP(4),
    REPORT(5),
    CALENDAR(6),
    NOTE(7),
}

/**
 * Antrean kirim offline-first. Semua aksi ditulis ke Room dulu, lalu didaftarkan di sini;
 * SyncWorker mengirimnya otomatis begitu ada jaringan (juga setelah HP dinyalakan ulang).
 */
class Outbox(private val context: Context, private val db: AppDatabase) {

    suspend fun enqueue(kind: OutKind, refId: String, payload: String? = null) {
        val id = db.outbox().insertIgnore(OutboxEntity(kind = kind.name, refId = refId, priority = kind.priority, payload = payload))
        if (id == -1L) db.outbox().bump(kind.name, refId, payload) // sudah ada: jadikan segera
        SyncScheduler.kick(context)
    }

    suspend fun has(kind: OutKind, refId: String) = db.outbox().exists(kind.name, refId) > 0
}

object SyncScheduler {
    private const val NOW = "everest-sync"
    private const val LATER = "everest-sync-later"
    private const val PERIODIC = "everest-sync-periodic"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Jalankan sinkron sesegera mungkin; WorkManager menunggu sampai ada jaringan. */
    fun kick(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** Coba lagi nanti untuk item yang gagal (jeda makin panjang). */
    fun later(context: Context, delayMs: Long) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setInitialDelay(delayMs.coerceAtLeast(5_000), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(LATER, ExistingWorkPolicy.REPLACE, req)
    }

    /** Cadangan berkala: tarik perubahan catatan dan kirim sisa antrean. */
    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Begitu sinyal kembali saat aplikasi/servis hidup, langsung kirim tanpa menunggu WorkManager. */
    fun watchNetwork(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = kick(context)
        })
    }
}
