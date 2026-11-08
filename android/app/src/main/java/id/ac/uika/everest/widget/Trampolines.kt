package id.ac.uika.everest.widget

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import id.ac.uika.everest.Locator
import id.ac.uika.everest.record.RecordingService
import id.ac.uika.everest.track.TrackStartActivity
import id.ac.uika.everest.track.TrackingService
import id.ac.uika.everest.ui.MainActivity

/**
 * Activity tak terlihat yang dibuka tombol widget. Android 14 hanya mengizinkan servis
 * mikrofon/lokasi dimulai saat aplikasi punya activity yang terlihat, jadi tombol widget
 * memulai servis dari sini lalu langsung menutup diri.
 */
class RecordToggleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when {
            !Locator.prefs.isLoggedIn -> openApp()
            RecordingService.isRecording() -> {
                RecordingService.stop(this)
                Toast.makeText(this, "Rekaman disimpan, transkrip sedang dibuat", Toast.LENGTH_SHORT).show()
            }
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> openApp()
            else -> {
                if (!Locator.prefs.recordConsentShown) {
                    Locator.prefs.recordConsentShown = true
                    Toast.makeText(this, "Ingat: rekam orang lain dengan sepengetahuan mereka", Toast.LENGTH_LONG).show()
                }
                RecordingService.start(this)
            }
        }
        finish()
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class TrackToggleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when {
            !Locator.prefs.isLoggedIn -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            TrackingService.isTracking() -> {
                TrackingService.stop(this)
                Toast.makeText(this, "Berbagi lokasi dihentikan", Toast.LENGTH_SHORT).show()
            }
            else -> startActivity(Intent(this, TrackStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
