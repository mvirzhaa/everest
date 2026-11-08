package id.ac.uika.everest.push

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.ac.uika.everest.Locator
import id.ac.uika.everest.auth.Repo
import id.ac.uika.everest.live.LiveMapActivity
import id.ac.uika.everest.util.formatDateTime
import kotlinx.coroutines.launch

/** Layar peringatan panic untuk penerima (kontak darurat dan satpam). */
class PanicAlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val a = PanicAlert.from(intent)
        setContent {
            val scope = rememberCoroutineScope()
            var accepted by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().background(Color(0xFFC0352B)).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("PANIC", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text("${a.userName} membutuhkan bantuan", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    if (a.escalation > 0) {
                        Text(
                            "ESKALASI: ${a.escalationText.ifBlank { "belum ada petugas yang menanggapi" }}",
                            color = Color(0xFFC0352B),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    Text(
                        listOfNotNull(
                            a.createdAt.formatDateTime(),
                            a.battery.takeIf { it.isNotBlank() }?.let { "baterai $it%" },
                            if (a.lat == null) "lokasi belum terdeteksi" else null,
                        ).joinToString(" · "),
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center,
                    )
                    val white = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFC0352B))
                    if (a.liveUrl.isNotBlank()) {
                        Button(onClick = { LiveMapActivity.open(this@PanicAlertActivity, a.liveUrl, "PANIC — ${a.userName}") }, colors = white, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                            Text("Lihat peta live", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (a.mapsUrl.isNotBlank()) {
                        Button(onClick = { open(a.mapsUrl) }, colors = white, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                            Text("Buka di Google Maps", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (a.userPhone.isNotBlank()) {
                        Button(onClick = { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${a.userPhone}"))) }, colors = white, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                            Text("Telepon ${a.userPhone}", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (a.isGuard && !accepted) {
                        Button(
                            onClick = {
                                scope.launch {
                                    Repo.setPanicStatus(a.panicId, "diterima")
                                    accepted = true
                                    PanicAlerts.cancel(this@PanicAlertActivity, a.panicId)
                                    Toast.makeText(this@PanicAlertActivity, "Tercatat: Anda menangani kejadian ini", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF26B0F), contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) { Text("Terima & tangani", fontWeight = FontWeight.Bold) }
                    }
                    OutlinedButton(onClick = { PanicAlerts.cancel(this@PanicAlertActivity, a.panicId); finish() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Tutup (matikan alarm)", color = Color.White)
                    }
                }
            }
        }
    }

    private fun open(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** Tombol "Terima" langsung dari notifikasi panic (satpam). */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("panic_id") ?: return
        val pending = goAsync()
        Locator.appScope.launch {
            try {
                Repo.setPanicStatus(id, "diterima")
                PanicAlerts.cancel(context, id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun acceptIntent(ctx: Context, panicId: String): PendingIntent = PendingIntent.getBroadcast(
            ctx,
            panicId.hashCode(),
            Intent(ctx, NotificationActionReceiver::class.java).putExtra("panic_id", panicId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
