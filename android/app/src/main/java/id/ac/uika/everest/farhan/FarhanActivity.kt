package id.ac.uika.everest.farhan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import id.ac.uika.everest.Locator
import id.ac.uika.everest.ui.MainActivity
import id.ac.uika.everest.ui.theme.EverestTheme
import kotlinx.coroutines.launch

/**
 * Farhan: asisten suara. Dibuka dari widget, langsung mendengarkan; jawaban diucapkan.
 * Teks hanya pelengkap (voice-first).
 */
class FarhanActivity : ComponentActivity() {
    private data class Bubble(val fromUser: Boolean, val text: String, val cards: List<FarhanCard> = emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Locator.prefs.isLoggedIn) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContent { EverestTheme { Overlay() } }
    }

    @Composable
    private fun Overlay() {
        val scope = rememberCoroutineScope()
        val engine = remember { FarhanEngine(this) }
        val bubbles = remember { mutableStateListOf(Bubble(false, "Halo ${Locator.prefs.user?.name?.substringBefore(' ') ?: ""}, ada yang bisa saya bantu?")) }
        var listening by remember { mutableStateOf(false) }
        var thinking by remember { mutableStateOf(false) }
        var partial by remember { mutableStateOf("") }
        var level by remember { mutableFloatStateOf(0f) }
        var typed by remember { mutableStateOf("") }
        var pending by remember { mutableStateOf<PendingEvent?>(null) }
        var listenAfterSpeech by remember { mutableStateOf(false) }
        val listState = rememberLazyListState()

        lateinit var speech: SpeechInput

        fun respond(reply: FarhanReply, speaker: Speaker) {
            bubbles += Bubble(false, reply.speech, reply.cards)
            pending = reply.pending
            listenAfterSpeech = reply.pending != null // dengarkan "ya/batal" setelah Farhan selesai bicara
            speaker.speak(reply.speech)
        }

        val speaker = remember {
            Speaker(this) {
                runOnUiThread {
                    if (listenAfterSpeech) {
                        listenAfterSpeech = false
                        listening = true
                        speech.start()
                    }
                }
            }
        }

        fun submit(text: String) {
            val t = text.trim()
            if (t.isEmpty()) return
            bubbles += Bubble(true, t)
            val p = pending
            scope.launch {
                thinking = true
                val reply = if (p != null) {
                    when (FarhanEngine.confirmation(t)) {
                        true -> engine.confirmEvent(p).also { pending = null }
                        false -> engine.cancelEvent().also { pending = null }
                        null -> { pending = null; engine.handle(t) }
                    }
                } else engine.handle(t)
                thinking = false
                respond(reply, speaker)
            }
        }

        speech = remember {
            SpeechInput(
                this,
                onPartial = { partial = it },
                onFinal = {
                    listening = false
                    partial = ""
                    level = 0f
                    submit(it)
                },
                onError = {
                    listening = false
                    partial = ""
                    level = 0f
                    bubbles += Bubble(false, it)
                },
                onLevel = { level = it },
            )
        }

        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) {
                listening = true
                speech.start()
            } else bubbles += Bubble(false, "Izin mikrofon diperlukan agar saya bisa mendengar. Anda tetap bisa mengetik.")
        }

        fun listen() {
            speaker.stop()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                listening = true
                partial = ""
                speech.start()
            } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        LaunchedEffect(Unit) { listen() } // voice-first: langsung mendengarkan
        LaunchedEffect(bubbles.size) { if (bubbles.isNotEmpty()) listState.animateScrollToItem(bubbles.size - 1) }
        DisposableEffect(Unit) {
            onDispose {
                speech.destroy()
                speaker.shutdown()
            }
        }

        Box(Modifier.fillMaxSize().clickable { finish() }, contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.78f).clickable(enabled = false) {},
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 3.dp,
            ) {
                Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Farhan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { finish() }) { Icon(Icons.Filled.Close, contentDescription = "Tutup") }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(bubbles) { b -> BubbleView(b.fromUser, b.text, b.cards) }
                        if (thinking) item { Text("Farhan sedang berpikir…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    pending?.let { p ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { submit("batal") }, modifier = Modifier.weight(1f)) { Text("Batal") }
                            Button(onClick = { submit("ya") }, modifier = Modifier.weight(1f)) { Text("Ya, jadwalkan") }
                        }
                    }
                    if (partial.isNotBlank()) {
                        Text("“$partial”", modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.tertiary)
                    }
                    MicButton(listening, level) { if (listening) { speech.stop(); listening = false } else listen() }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            placeholder = { Text("atau ketik perintah…") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { submit(typed); typed = "" }, enabled = typed.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Kirim")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MicButton(listening: Boolean, level: Float, onClick: () -> Unit) {
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 1f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "pulse",
        )
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(88.dp)
                    .scale(if (listening) pulse + level * 0.25f else 1f)
                    .background(
                        if (listening) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(68.dp).background(MaterialTheme.colorScheme.primary, CircleShape).clickable(onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (listening) "Berhenti mendengarkan" else "Bicara ke Farhan",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }
        Text(
            if (listening) "Mendengarkan…" else "Ketuk untuk bicara",
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    @Composable
    private fun BubbleView(fromUser: Boolean, text: String, cards: List<FarhanCard>) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start) {
            Surface(
                color = if (fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(
                    text,
                    color = if (fromUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            cards.forEach { c ->
                Card(
                    onClick = { openCard(c) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(c.title, fontWeight = FontWeight.SemiBold)
                        Text(c.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
            }
        }
    }

    private fun openCard(c: FarhanCard) {
        when {
            c.route != null -> startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_ROUTE, c.route)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            c.noteId != null -> startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_ROUTE, "catatan/${c.noteId}?t=${c.startMs}")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            c.url != null -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.url)))
        }
    }
}
