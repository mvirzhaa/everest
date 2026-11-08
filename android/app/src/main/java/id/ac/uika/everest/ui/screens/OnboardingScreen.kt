package id.ac.uika.everest.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.ac.uika.everest.widget.EverestWidgetReceiver
import id.ac.uika.everest.whisper.ModelDownloadWorker
import id.ac.uika.everest.whisper.Models

private fun Context.has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

/** Satu langkah izin: alasan yang jelas, status, dan tombol. */
private data class Step(val title: String, val why: String, val done: Boolean, val action: String, val onClick: () -> Unit)

@SuppressLint("BatteryLife")
@Composable
fun OnboardingScreen(isGuard: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) } // paksa hitung ulang status saat kembali dari Setelan
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    val pm = context.getSystemService(PowerManager::class.java)
    val nm = context.getSystemService(NotificationManager::class.java)
    // Dihitung ulang setiap kali kembali ke layar ini (tick berubah)
    val steps = remember(tick) { buildList {
        if (Build.VERSION.SDK_INT >= 33) add(
            Step("Notifikasi", "Agar sinyal panic dan status laporan bisa muncul.", context.has(Manifest.permission.POST_NOTIFICATIONS), "Izinkan") {
                request.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            },
        )
        add(Step("Mikrofon", "Untuk tombol Rekam dan perintah suara Farhan.", context.has(Manifest.permission.RECORD_AUDIO), "Izinkan") {
            request.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        })
        add(Step("Lokasi", "Untuk Panic, Track, dan lokasi laporan. Hanya dipakai saat tombol itu aktif.", context.has(Manifest.permission.ACCESS_FINE_LOCATION), "Izinkan") {
            request.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        })
        add(Step("Kamera", "Untuk memotret laporan.", context.has(Manifest.permission.CAMERA), "Izinkan") {
            request.launch(arrayOf(Manifest.permission.CAMERA))
        })
        add(Step("SMS", "Cadangan panic saat tidak ada internet: SMS lokasi ke kontak darurat.", context.has(Manifest.permission.SEND_SMS), "Izinkan") {
            request.launch(arrayOf(Manifest.permission.SEND_SMS))
        })
        add(Step("Jangan batasi baterai", "Agar perekaman dan berbagi lokasi tidak dimatikan sistem (penting di Xiaomi, Oppo, Vivo, Realme).", pm.isIgnoringBatteryOptimizations(context.packageName), "Buka") {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            }
        })
        if (isGuard && Build.VERSION.SDK_INT >= 34) add(
            Step("Alarm layar penuh (satpam)", "Agar panic tampil seperti panggilan masuk walau layar terkunci.", nm.canUseFullScreenIntent(), "Buka") {
                runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))) }
            },
        )
        add(Step("Model transkripsi", "Unduh model ${Models.chosen(context).removePrefix("ggml-").removeSuffix(".bin")} sekali agar rekaman bisa ditranskrip tanpa internet.", Models.available(context) != null, "Unduh") {
            ModelDownloadWorker.enqueue(context)
        })
        add(Step("Pasang widget", "Enam tombol (Rekam, Panic, Track, Farhan, Report, Sirine) di layar utama.", false, "Pasang") {
            val awm = context.getSystemService(AppWidgetManager::class.java)
            if (awm.isRequestPinAppWidgetSupported) awm.requestPinAppWidget(ComponentName(context, EverestWidgetReceiver::class.java), null, null)
        })
    } }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Siapkan Everest", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Beberapa izin diperlukan agar keenam tombol bekerja, termasuk saat layar terkunci dan tanpa sinyal.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        steps.forEach { s ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (s.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (s.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(s.title, fontWeight = FontWeight.SemiBold)
                        Text(s.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!s.done) TextButton(onClick = s.onClick) { Text(s.action) }
                }
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Selesai") }
        Text("Izin bisa diubah kapan saja di Profil atau Setelan HP.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
