package id.ac.uika.everest.panic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import id.ac.uika.everest.Locator
import id.ac.uika.everest.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Layar panic dari widget: hitung mundur 5 detik yang bisa dibatalkan, lalu sinyal dikirim.
 * Batal dalam hitung mundur tidak membuat data apa pun.
 */
class PanicActivity : ComponentActivity() {

    private enum class Phase { COUNTDOWN, SENDING, DONE, ACTIVE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!Locator.prefs.isLoggedIn) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContent { Screen() }
    }

    @Composable
    private fun Screen() {
        val alreadyActive = PanicManager.activePanicId() != null
        var phase by rememberSaveable { mutableStateOf(if (alreadyActive) Phase.ACTIVE else Phase.COUNTDOWN) }
        var seconds by rememberSaveable { mutableIntStateOf(COUNTDOWN) }
        var outcome by rememberSaveable { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(phase) {
            if (phase != Phase.COUNTDOWN) return@LaunchedEffect
            while (seconds > 0) {
                vibrate(120)
                delay(1000)
                seconds--
            }
            phase = Phase.SENDING
            vibrate(600)
            lastOutcome.value = null
            // Jalankan di scope aplikasi: tidak boleh batal walau layar diputar atau ditutup
            val app = applicationContext
            Locator.appScope.launch {
                val o = PanicManager.trigger(app)
                lastOutcome.value = when {
                    o.deliveredOnline -> "Sinyal terkirim ke ${o.recipients} penerima (kontak darurat dan satpam kampus)."
                    o.smsSent > 0 -> "Tidak ada internet. SMS darurat terkirim ke ${o.smsSent} kontak. Sinyal ke satpam dikirim otomatis begitu ada sinyal data."
                    else -> "Tidak ada internet. Sinyal disimpan dan dikirim otomatis begitu ada sinyal. Tambahkan nomor HP kontak darurat agar SMS cadangan bisa dikirim."
                } + if (!o.hasLocation) "\n\nLokasi belum terdeteksi; posisi dikirim begitu GPS mendapat sinyal." else ""
            }
        }

        LaunchedEffect(phase) {
            if (phase != Phase.SENDING) return@LaunchedEffect
            val text = lastOutcome.filterNotNull().first()
            outcome = text
            phase = Phase.DONE
        }

        Box(Modifier.fillMaxSize().background(Color(0xFFC0352B)).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when (phase) {
                    Phase.COUNTDOWN -> {
                        Text("PANIC", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                        Text("Sinyal darurat terkirim dalam", color = Color.White.copy(alpha = 0.9f), fontSize = 18.sp)
                        Box(Modifier.size(180.dp).background(Color.White.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                            Text("$seconds", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Lokasi Anda akan dikirim ke kontak darurat dan satpam kampus, lalu dipantau tiap 10 detik.",
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = { finish() },
                            modifier = Modifier.fillMaxWidth().height(72.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFC0352B)),
                        ) { Text("BATAL", fontSize = 24.sp, fontWeight = FontWeight.Black) }
                    }

                    Phase.SENDING -> {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 5.dp, modifier = Modifier.size(72.dp))
                        Text("Mengirim sinyal darurat…", color = Color.White, fontSize = 20.sp)
                    }

                    Phase.DONE, Phase.ACTIVE -> {
                        Text(if (phase == Phase.DONE) "Sinyal panic aktif" else "Panic masih aktif", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text(
                            outcome ?: "Lokasi Anda terus dikirim tiap 10 detik ke kontak darurat dan satpam.",
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            fontSize = 16.sp,
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    PanicManager.markSafe(this@PanicActivity)
                                    finish()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF2F7D4F)),
                        ) { Text("Saya sudah aman", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                        OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Tutup layar (panic tetap aktif)", color = Color.White)
                        }
                        if (ContextCompat.checkSelfPermission(this@PanicActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                            Text(
                                "Izin lokasi belum diberikan. Buka aplikasi Everest untuk mengizinkan agar posisi bisa dikirim.",
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun vibrate(ms: Long) {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private companion object {
        const val COUNTDOWN = 5
        /** Hasil pengiriman terakhir, bertahan saat activity dibuat ulang. */
        val lastOutcome = MutableStateFlow<String?>(null)
    }
}
