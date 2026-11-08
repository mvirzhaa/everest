package id.ac.uika.everest.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.ac.uika.everest.Locator
import id.ac.uika.everest.R
import id.ac.uika.everest.privacy.Privacy
import id.ac.uika.everest.sync.OutKind
import id.ac.uika.everest.ui.theme.LocalEverestDark
import id.ac.uika.everest.util.now
import kotlinx.coroutines.launch

/**
 * Layar persetujuan privasi (UU PDP). Tampil sebelum login dan setiap kali versi kebijakan berubah.
 * Persetujuan disimpan di HP, lalu dicatat di server lewat antrean (bisa saat offline).
 */
@Composable
fun ConsentScreen(onAgreed: () -> Unit) {
    val context = LocalContext.current
    val previous = Locator.prefs.consentVersion
    var agree by remember { mutableStateOf(false) }
    var cloud by remember { mutableStateOf(Locator.prefs.farhanCloud) }
    var refuse by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.logo_everest),
            contentDescription = "Everest",
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .align(Alignment.CenterHorizontally)
                .then(if (LocalEverestDark.current) Modifier.background(Color.White, RoundedCornerShape(10.dp)).padding(6.dp) else Modifier),
        )
        Text("Privasi & persetujuan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            if (previous != null) "Kebijakan privasi Everest diperbarui. Mohon baca ringkasannya dan setujui kembali."
            else "Everest memproses data pribadi Anda untuk keselamatan dan catatan. Berikut ringkasan data yang diproses dan untuk apa.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Privacy.SCOPES.forEach { s ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontWeight = FontWeight.SemiBold)
                        Text(s.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!s.required) Switch(checked = cloud, onCheckedChange = { cloud = it }, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        Text(
            "Admin tidak dapat membaca transkrip Anda. Anda berhak mengakses, memperbaiki, menghapus data, dan menarik persetujuan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Privacy.policyUrl(Locator.prefs.serverUrl)))) }
        }) { Text("Baca kebijakan privasi lengkap") }

        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { agree = !agree },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = agree, onCheckedChange = { agree = it })
            Text("Saya sudah membaca dan menyetujui Kebijakan Privasi Everest.", style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = {
                val p = Locator.prefs
                p.farhanCloud = cloud
                p.consentScopes = Privacy.scopes(cloud)
                p.consentAt = now()
                p.consentVersion = Privacy.VERSION
                if (p.isLoggedIn) Locator.appScope.launch { Locator.outbox.enqueue(OutKind.CONSENT, "privasi") }
                onAgreed()
            },
            enabled = agree,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Setuju dan lanjutkan") }
        TextButton(onClick = { refuse = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Tidak setuju") }
    }

    if (refuse) {
        AlertDialog(
            onDismissRequest = { refuse = false },
            title = { Text("Everest tidak bisa dipakai tanpa persetujuan") },
            text = {
                Text(
                    "Fitur keselamatan (Panic, Track) harus mengirim lokasi dan identitas Anda ke kontak darurat dan satpam. " +
                        "Bila tidak setuju, aplikasi ditutup dan tidak ada data yang dikirim. Anda bisa kembali kapan saja.",
                )
            },
            confirmButton = { TextButton(onClick = { refuse = false }) { Text("Kembali") } },
            dismissButton = { TextButton(onClick = { (context as? Activity)?.finishAffinity() }) { Text("Tutup aplikasi") } },
        )
    }
}
