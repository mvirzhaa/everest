package id.ac.uika.everest.siren

import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Dibuka dari tombol Sirine. Bila sirine mati: hitung mundur 5 detik (bisa dibatalkan) lalu
 * sirine menyala. Bila sirine sedang menyala: langsung dimatikan (cukup sekali tekan).
 */
class SirenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SirenService.isOn()) {
            SirenService.stop(this)
            Toast.makeText(this, "Sirine dimatikan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            var seconds by rememberSaveable { mutableIntStateOf(COUNTDOWN) }
            LaunchedEffect(Unit) {
                while (seconds > 0) {
                    vibrate()
                    delay(1000)
                    seconds--
                }
                SirenService.start(this@SirenActivity) // activity masih terlihat → servis boleh dimulai
                finish()
            }
            Box(Modifier.fillMaxSize().background(Color(0xFFFFB300)).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("SIRINE", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text("Sirine berbunyi keras dalam", color = Ink.copy(alpha = 0.85f), fontSize = 18.sp)
                    Box(Modifier.size(180.dp).background(Ink.copy(alpha = 0.10f), CircleShape), contentAlignment = Alignment.Center) {
                        Text("$seconds", color = Ink, fontSize = 96.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Berbunyi walau HP mode senyap, lampu senter ikut berkedip. Tekan tombol Sirine sekali lagi untuk mematikan.",
                        color = Ink,
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = { finish() },
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color(0xFFFFB300)),
                    ) { Text("BATAL", fontSize = 24.sp, fontWeight = FontWeight.Black) }
                }
            }
        }
    }

    private fun vibrate() {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        v?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private companion object {
        const val COUNTDOWN = 5
        val Ink = Color(0xFF2B1700)
    }
}
