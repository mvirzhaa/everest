package id.ac.uika.everest

import android.content.Context
import id.ac.uika.everest.data.DbKey
import id.ac.uika.everest.data.Prefs
import id.ac.uika.everest.data.local.AppDatabase
import id.ac.uika.everest.data.remote.ApiClient
import id.ac.uika.everest.realtime.Realtime
import id.ac.uika.everest.sync.Outbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Wadah dependensi sederhana (tanpa framework DI). */
object Locator {
    lateinit var app: Context
        private set
    val prefs: Prefs by lazy { Prefs(app) }
    val db: AppDatabase by lazy { AppDatabase.build(app, DbKey.get(prefs)) }
    val client: ApiClient by lazy { ApiClient(prefs) }
    val api get() = client.api
    val outbox: Outbox by lazy { Outbox(app, db) }
    val realtime: Realtime by lazy { Realtime(prefs, client.http) }

    /** Scope aplikasi untuk kerja singkat yang tidak boleh batal saat layar ditutup. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        app = context.applicationContext
    }
}
