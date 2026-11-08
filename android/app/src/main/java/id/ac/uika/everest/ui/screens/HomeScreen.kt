package id.ac.uika.everest.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import id.ac.uika.everest.Locator
import id.ac.uika.everest.data.local.FarhanMessageEntity
import id.ac.uika.everest.farhan.FarhanActivity
import id.ac.uika.everest.panic.PanicActivity
import id.ac.uika.everest.record.RecordingService
import id.ac.uika.everest.report.ReportActivity
import id.ac.uika.everest.siren.SirenActivity
import id.ac.uika.everest.siren.SirenService
import id.ac.uika.everest.ui.theme.OnSiren
import id.ac.uika.everest.ui.theme.SirenAmber
import id.ac.uika.everest.track.TrackStartActivity
import id.ac.uika.everest.track.TrackingService
import id.ac.uika.everest.ui.EmptyState
import id.ac.uika.everest.ui.SectionTitle
import id.ac.uika.everest.ui.StatusChip
import id.ac.uika.everest.ui.theme.PanicRed
import id.ac.uika.everest.util.formatDateTime
import id.ac.uika.everest.util.formatDuration
import id.ac.uika.everest.util.formatTime
import id.ac.uika.everest.widget.RecordToggleActivity

@Composable
fun HomeScreen(nav: NavController) {
    val context = LocalContext.current
    val user by Locator.prefs.userFlow.collectAsState()
    val recording by Locator.prefs.recordingFlow.collectAsState()
    val tracking by Locator.prefs.trackingFlow.collectAsState()
    val siren by Locator.prefs.sirenFlow.collectAsState()
    val notes by remember { Locator.db.notes().observeAll() }.collectAsState(initial = emptyList())
    val reports by remember { Locator.db.reports().observeAll() }.collectAsState(initial = emptyList())
    val queued by remember { Locator.db.outbox().observeCount() }.collectAsState(initial = 0)

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text("Halo, ${user?.name?.substringBefore(' ').orEmpty()}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (queued == 0) Icons.Filled.CloudDone else Icons.Filled.CloudOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (queued == 0) "  Semua data tersinkron" else "  $queued item menunggu dikirim (otomatis saat ada sinyal)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(if (recording != null) Icons.Filled.Stop else Icons.Filled.Mic, if (recording != null) "Stop" else "Rekam", if (recording != null) PanicRed else null, Modifier.weight(1f)) {
                    context.startActivity(Intent(context, RecordToggleActivity::class.java))
                }
                QuickAction(Icons.Filled.MyLocation, if (tracking != null) "Stop track" else "Track", null, Modifier.weight(1f)) {
                    if (tracking != null) TrackingService.stop(context) else context.startActivity(Intent(context, TrackStartActivity::class.java))
                }
                QuickAction(Icons.Filled.SmartToy, "Farhan", null, Modifier.weight(1f)) {
                    context.startActivity(Intent(context, FarhanActivity::class.java))
                }
                QuickAction(Icons.Filled.CameraAlt, "Report", null, Modifier.weight(1f)) {
                    context.startActivity(Intent(context, ReportActivity::class.java))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { if (siren) SirenService.stop(context) else context.startActivity(Intent(context, SirenActivity::class.java)) },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = SirenAmber, contentColor = OnSiren),
                ) {
                    Icon(if (siren) Icons.Filled.VolumeOff else Icons.Filled.Campaign, null)
                    Text(if (siren) "  MATIKAN" else "  SIRINE", fontWeight = FontWeight.Black)
                }
                Button(
                    onClick = { context.startActivity(Intent(context, PanicActivity::class.java)) },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = PanicRed, contentColor = Color.White),
                ) {
                    Icon(Icons.Filled.Warning, null)
                    Text("  PANIC", fontWeight = FontWeight.Black)
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth().clickable { nav.navigate("doa") }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                        Text("  Doa & Ayat", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("Offline", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.AssistChip(onClick = { nav.navigate("doa?kategori=perjalanan") }, label = { Text("Perjalanan") })
                        androidx.compose.material3.AssistChip(onClick = { nav.navigate("doa/baca/ayat-kursi") }, label = { Text("Ayat Kursi") })
                        androidx.compose.material3.AssistChip(onClick = { nav.navigate("doa?kategori=ruqyah") }, label = { Text("Ruqyah") })
                    }
                }
            }
        }
        if (recording != null) item {
            Card(colors = CardDefaults.cardColors(containerColor = PanicRed.copy(alpha = 0.12f))) {
                Text("Sedang merekam. Transkrip dibuat bertahap tiap 30 detik.", Modifier.padding(14.dp))
            }
        }
        if (siren) item {
            Card(colors = CardDefaults.cardColors(containerColor = SirenAmber.copy(alpha = 0.22f))) {
                Text("Sirine sedang menyala. Tekan MATIKAN atau tombol Sirine di widget.", Modifier.padding(14.dp))
            }
        }
        if (tracking != null) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text("Lokasi Anda sedang dibagikan tiap 10 detik.", Modifier.padding(14.dp))
            }
        }
        item { SectionTitle("Catatan terbaru") }
        if (notes.isEmpty()) item { EmptyState("Belum ada rekaman", "Tekan Rekam di widget atau di atas. Transkrip dibuat di HP, tanpa internet.") }
        items(notes.take(5), key = { it.id }) { n ->
            OutlinedCard(Modifier.fillMaxWidth().clickable { nav.navigate("catatan/${n.id}") }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (n.status != "selesai") StatusChip(n.status)
                    }
                    Text("${n.startedAt.formatDateTime()} · ${formatDuration(n.durationS)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (n.keywords.isNotBlank()) Text(n.keywords.replace(",", " · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        if (reports.isNotEmpty()) {
            item { SectionTitle("Laporan saya") }
            items(reports.take(3), key = { "r" + it.id }) { r ->
                OutlinedCard(Modifier.fillMaxWidth().clickable { nav.navigate("laporan/${r.id}") }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.description, maxLines = 1, modifier = Modifier.weight(1f))
                        StatusChip(r.status)
                    }
                }
            }
        }
        item { Text("") }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, tint: Color?, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = tint ?: MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
fun FarhanHistoryScreen() {
    val context = LocalContext.current
    val messages by remember { Locator.db.farhan().observeRecent() }.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Farhan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Contoh: \"Rekaman tanggal 3 November ada apa saja?\" · \"Jadwalkan rapat prodi besok jam 9 di ruang 204\" · \"Kemarin sore saya di mana saja?\"",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { context.startActivity(Intent(context, FarhanActivity::class.java)) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Filled.Mic, null)
            Text("  Bicara ke Farhan")
        }
        if (messages.isEmpty()) EmptyState("Belum ada percakapan", "Riwayat percakapan dengan Farhan akan tampil di sini.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), reverseLayout = false) {
            items(messages, key = { it.id }) { m -> HistoryRow(m) }
        }
    }
}

@Composable
private fun HistoryRow(m: FarhanMessageEntity) {
    val mine = m.role == "pengguna"
    Column(Modifier.fillMaxWidth()) {
        Text(
            (if (mine) "Anda" else "Farhan") + " · " + m.createdAt.formatTime(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(m.text, style = MaterialTheme.typography.bodyMedium, fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Normal)
    }
}
