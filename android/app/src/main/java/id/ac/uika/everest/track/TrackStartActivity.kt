package id.ac.uika.everest.track

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import id.ac.uika.everest.Locator
import id.ac.uika.everest.auth.Repo
import id.ac.uika.everest.data.local.TrackSessionEntity
import id.ac.uika.everest.sync.OutKind
import id.ac.uika.everest.ui.MainActivity
import id.ac.uika.everest.ui.theme.EverestTheme
import id.ac.uika.everest.util.newId
import id.ac.uika.everest.util.now
import kotlinx.coroutines.launch

/** Lembar pilih penerima sebelum mulai berbagi lokasi (dibuka dari widget atau aplikasi). */
class TrackStartActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EverestTheme { Sheet() } }
    }

    @Composable
    private fun Sheet() {
        val contacts by remember { Locator.db.contacts().observeAll() }.collectAsState(initial = emptyList())
        val eligible = contacts.filter { it.contactUserId != null }
        var selected by remember { mutableStateOf(Locator.prefs.lastTrackRecipients) }
        val scope = rememberCoroutineScope()
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.values.any { it }) startTracking(selected) else Toast.makeText(this, "Izin lokasi diperlukan untuk berbagi lokasi", Toast.LENGTH_LONG).show()
        }
        LaunchedEffect(Unit) { runCatching { Repo.refreshContacts() } }

        Box(Modifier.fillMaxSize().clickable { finish() }, contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = false) {},
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tonalElevation = 2.dp,
            ) {
                Column(Modifier.padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bagikan lokasi live", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Lokasi dikirim tiap 10 detik sampai Anda menekan tombol Track lagi. Tanpa sinyal, titik disimpan dan dikirim begitu online.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (eligible.isEmpty()) {
                        Text(
                            "Belum ada kontak darurat yang memakai Everest. Lokasi tetap direkam sebagai riwayat pribadi.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        TextButton(onClick = {
                            startActivity(Intent(this@TrackStartActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "keamanan"))
                            finish()
                        }) { Text("Tambah kontak darurat") }
                    } else {
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(eligible, key = { it.id }) { c ->
                                val uid = c.contactUserId!!
                                Row(
                                    Modifier.fillMaxWidth().clickable { selected = if (uid in selected) selected - uid else selected + uid }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = uid in selected, onCheckedChange = { selected = if (it) selected + uid else selected - uid })
                                    Column {
                                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                                        c.contactEmail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { finish() }) { Text("Batal") }
                        Button(onClick = {
                            scope.launch {
                                if (hasLocationPermission()) startTracking(selected)
                                else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            }
                        }) {
                            Text(if (selected.isEmpty()) "Mulai (riwayat pribadi)" else "Mulai berbagi ke ${selected.size} orang")
                        }
                    }
                }
            }
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startTracking(recipients: Set<String>) {
        Locator.prefs.lastTrackRecipients = recipients
        val id = newId()
        // Mulai servis selagi activity ini masih terlihat (aturan Android 14), simpan sesi di Room dulu
        kotlinx.coroutines.runBlocking {
            Locator.db.tracks().upsertSession(TrackSessionEntity(id = id, kind = "track", startedAt = now(), recipientIds = recipients.joinToString(",")))
        }
        TrackingService.start(this, id, "track")
        Locator.appScope.launch { Locator.outbox.enqueue(OutKind.TRACK_START, id) }
        Toast.makeText(this, if (recipients.isEmpty()) "Lokasi direkam" else "Lokasi dibagikan ke ${recipients.size} orang", Toast.LENGTH_SHORT).show()
        finish()
    }
}
