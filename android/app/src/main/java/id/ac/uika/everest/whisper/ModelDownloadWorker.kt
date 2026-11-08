package id.ac.uika.everest.whisper

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import id.ac.uika.everest.Locator
import id.ac.uika.everest.record.TranscriptionScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Mengunduh model whisper dari server kampus (GET /api/models → /models/<file>).
 * Model tidak dibundel di APK agar ukuran aplikasi kecil dan model bisa dipilih per HP.
 */
class ModelDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val wanted = inputData.getString(KEY_NAME) ?: Models.chosen(applicationContext)
        val models = runCatching { Locator.api.models() }.getOrElse { return@withContext Result.retry() }
        val m = models.firstOrNull { it.name == wanted }
            ?: models.firstOrNull { it.name == Models.BASE }
            ?: models.firstOrNull()
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Server belum menyediakan model whisper"))

        val target = Models.file(applicationContext, m.name)
        val part = File(target.parentFile, "${m.name}.part")
        val req = Request.Builder().url(m.url).build()
        try {
            Locator.client.http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext Result.retry()
                val body = res.body ?: return@withContext Result.retry()
                val total = body.contentLength().takeIf { it > 0 } ?: m.size
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            if (isStopped) return@withContext Result.retry()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                setProgress(workDataOf(KEY_PROGRESS to pct))
                            }
                        }
                    }
                }
            }
            if (part.length() < m.size * 0.98) return@withContext Result.retry()
            if (!part.renameTo(target)) return@withContext Result.retry()
            Locator.prefs.modelName = m.name
            TranscriptionScheduler.kick(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE = "everest-model-download"
        const val KEY_NAME = "name"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"

        fun enqueue(context: Context, name: String? = null) {
            val network = if (Locator.prefs.largeFilesWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).setRequiresStorageNotLow(true).build())
                .setInputData(workDataOf(KEY_NAME to (name ?: Models.chosen(context))))
                .build()
            val policy = if (name != null) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, policy, req)
        }

        /** Progres unduhan (0–100) atau null bila tidak sedang mengunduh. */
        fun progress(context: Context): Flow<Int?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE).map { infos ->
                infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                    ?.let { if (it.state == WorkInfo.State.RUNNING) it.progress.getInt(KEY_PROGRESS, 0) else 0 }
            }
    }
}
