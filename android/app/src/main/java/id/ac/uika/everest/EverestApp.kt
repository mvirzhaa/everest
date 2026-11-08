package id.ac.uika.everest

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import id.ac.uika.everest.push.Notifications
import id.ac.uika.everest.record.TranscriptionScheduler
import id.ac.uika.everest.sync.OutKind
import id.ac.uika.everest.sync.SyncScheduler
import kotlinx.coroutines.launch

class EverestApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(Log.INFO).build()

    override fun onCreate() {
        super.onCreate()
        Locator.init(this)
        if (Locator.prefs.sirenOn) Locator.prefs.sirenOn = false // sisa status dari proses yang sudah mati
        Notifications.createChannels(this)
        initFirebase()
        SyncScheduler.watchNetwork(this)
        if (Locator.prefs.isLoggedIn) {
            SyncScheduler.schedulePeriodic(this)
            SyncScheduler.kick(this)
            // Lanjutkan transkripsi yang tertunda (mis. HP sempat mati)
            Locator.appScope.launch { runCatching { TranscriptionScheduler.resumePending(this@EverestApp) } }
        }
    }

    /** FCM diinisialisasi manual dari everest.properties, jadi build tetap jalan tanpa google-services.json. */
    private fun initFirebase() {
        if (BuildConfig.FCM_APP_ID.isBlank() || BuildConfig.FCM_API_KEY.isBlank()) {
            Log.i("Everest", "FCM belum dikonfigurasi: notifikasi push dimatikan")
            return
        }
        runCatching {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(
                    this,
                    FirebaseOptions.Builder()
                        .setProjectId(BuildConfig.FCM_PROJECT_ID)
                        .setApplicationId(BuildConfig.FCM_APP_ID)
                        .setApiKey(BuildConfig.FCM_API_KEY)
                        .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                        .build(),
                )
            }
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (token != Locator.prefs.fcmToken) {
                    Locator.prefs.fcmToken = token
                    if (Locator.prefs.isLoggedIn) Locator.appScope.launch { Locator.outbox.enqueue(OutKind.DEVICE, "fcm") }
                }
            }
        }.onFailure { Log.w("Everest", "Inisialisasi FCM gagal: ${it.message}") }
    }

    companion object {
        fun firebaseReady() = runCatching { FirebaseApp.getInstance() }.isSuccess
    }
}
