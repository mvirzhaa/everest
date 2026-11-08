package id.ac.uika.everest.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.ac.uika.everest.BuildConfig
import id.ac.uika.everest.Locator
import id.ac.uika.everest.auth.AuthManager
import id.ac.uika.everest.auth.Repo
import id.ac.uika.everest.privacy.Privacy
import id.ac.uika.everest.sync.OutKind
import id.ac.uika.everest.ui.theme.ThemeMode
import id.ac.uika.everest.util.formatDateTime
import id.ac.uika.everest.whisper.ModelDownloadWorker
import id.ac.uika.everest.whisper.Models
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen() {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val user by Locator.prefs.userFlow.collectAsState()
    val download by remember { ModelDownloadWorker.progress(context) }.collectAsState(initial = null)
    var phone by remember { mutableStateOf(user?.phone.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var onCharge by remember { mutableStateOf(Locator.prefs.processLongOnCharge) }
    var wifiOnly by remember { mutableStateOf(Locator.prefs.largeFilesWifiOnly) }
    var cloud by remember { mutableStateOf(Locator.prefs.farhanCloud) }
    var model by remember { mutableStateOf(Models.chosen(context)) }
    var confirmLogout by remember { mutableStateOf(false) }
    val theme by Locator.prefs.themeFlow.collectAsState()

    val calendarConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch {
            runCatching { AuthManager.linkCalendar(AuthManager.calendarResultFromIntent(activity, res.data)) }
                .onSuccess { message = "Google Calendar terhubung" }
                .onFailure { message = "Gagal menghubungkan: ${Locator.client.errorMessage(it)}" }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Profil", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(user?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
        Text("${user?.email.orEmpty()} · ${when (user?.role) { "admin" -> "Admin"; "guard" -> "Satpam"; else -> "Pengguna" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(phone, { phone = it }, label = { Text("Nomor HP (dilihat penerima panic)") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                scope.launch { runCatching { Repo.updatePhone(phone) }.onSuccess { message = "Nomor disimpan" }.onFailure { message = Locator.client.errorMessage(it) } }
            }) { Text("Simpan") }
        }

        HorizontalDivider()
        Text("Google Calendar", fontWeight = FontWeight.SemiBold)
        if (user?.googleLinked == true) {
            Text("Terhubung. Farhan bisa membuat dan membaca jadwal Anda.", color = MaterialTheme.colorScheme.tertiary)
        } else if (!AuthManager.calendarConfigured) {
            Text("Belum dikonfigurasi di aplikasi ini (GOOGLE_SERVER_CLIENT_ID).", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Button(onClick = {
                scope.launch {
                    runCatching {
                        val r = AuthManager.requestCalendar(activity)
                        val pi = r.pendingIntent
                        if (r.hasResolution() && pi != null) calendarConsent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        else {
                            AuthManager.linkCalendar(r)
                            message = "Google Calendar terhubung"
                        }
                    }.onFailure { message = "Gagal: ${Locator.client.errorMessage(it)}" }
                }
            }) { Text("Hubungkan Google Calendar") }
        }

        HorizontalDivider()
        Text("Transkripsi offline", fontWeight = FontWeight.SemiBold)
        val have = Models.available(context)
        Text(
            have?.let { "Model terpasang: ${it.name.removePrefix("ggml-").removeSuffix(".bin")} (${it.length() / 1_000_000} MB)" } ?: "Model belum terunduh",
            style = MaterialTheme.typography.bodySmall,
        )
        listOf(Models.BASE to "Base — cepat, untuk HP RAM ≤ 4 GB", Models.SMALL to "Small — lebih akurat, untuk HP RAM ≥ 6 GB").forEach { (name, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = model == name, onClick = {
                    model = name
                    Locator.prefs.modelName = name
                })
                Text(label + if (name == Models.recommended(context)) " (disarankan)" else "", style = MaterialTheme.typography.bodyMedium)
            }
        }
        download?.let {
            Text("Mengunduh… $it%", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth())
        } ?: OutlinedButton(onClick = { ModelDownloadWorker.enqueue(context, model) }, enabled = have?.name != model) {
            Text(if (have?.name == model) "Model sudah terpasang" else "Unduh model ini")
        }
        Toggle("Rekaman panjang (> 30 menit) diproses saat dicas", onCharge) { onCharge = it; Locator.prefs.processLongOnCharge = it }

        HorizontalDivider()
        Text("Data & sinkronisasi", fontWeight = FontWeight.SemiBold)
        Toggle("Foto & model hanya dikirim/diunduh lewat Wi-Fi", wifiOnly) { wifiOnly = it; Locator.prefs.largeFilesWifiOnly = it }
        Toggle("Farhan cloud (ringkasan & tanya-jawab, butuh internet)", cloud) {
            cloud = it
            Locator.prefs.farhanCloud = it
            // Menyalakan/mematikan = memberi/menarik persetujuan cakupan itu: catat ke server
            Locator.prefs.consentScopes = Privacy.scopes(it)
            Locator.prefs.consentAt = System.currentTimeMillis()
            scope.launch { Locator.outbox.enqueue(OutKind.CONSENT, "privasi") }
        }
        Text(
            "Saat dimatikan, Farhan hanya bekerja di HP: cari rekaman, riwayat lokasi, dan jadwal. Saat dinyalakan, hanya potongan transkrip yang relevan yang dikirim ke server kampus.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        Text("Tampilan", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { m ->
                FilterChip(selected = theme == m, onClick = { Locator.prefs.themeMode = m }, label = { Text(m.label) })
            }
        }

        HorizontalDivider()
        Text("Privasi", fontWeight = FontWeight.SemiBold)
        Text(
            "Kebijakan versi ${Locator.prefs.consentVersion ?: "-"}, disetujui ${Locator.prefs.consentAt.takeIf { it > 0 }?.formatDateTime() ?: "-"}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Privacy.policyUrl(Locator.prefs.serverUrl)))) }
        }) { Text("Baca kebijakan privasi") }

        HorizontalDivider()
        Text("Server: ${Locator.prefs.serverUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Versi ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        message?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
        OutlinedButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth()) { Text("Keluar") }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Keluar dari Everest?") },
            text = { Text("Rekaman, lokasi, dan laporan di HP ini akan dihapus. Data yang sudah tersinkron tetap ada di server. Item yang belum terkirim akan hilang.") },
            confirmButton = { TextButton(onClick = { scope.launch { AuthManager.signOut(context) }; confirmLogout = false }) { Text("Keluar", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Batal") } },
        )
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = value, onCheckedChange = onChange)
    }
}
