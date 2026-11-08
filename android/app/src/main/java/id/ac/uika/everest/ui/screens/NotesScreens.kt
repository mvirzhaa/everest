package id.ac.uika.everest.ui.screens

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import id.ac.uika.everest.Locator
import id.ac.uika.everest.data.local.NoteEntity
import id.ac.uika.everest.data.local.SegmentHit
import id.ac.uika.everest.record.Keywords
import id.ac.uika.everest.record.TranscriptEdit
import id.ac.uika.everest.data.local.SegmentEntity
import id.ac.uika.everest.sync.OutKind
import id.ac.uika.everest.ui.EmptyState
import id.ac.uika.everest.ui.NotePlayer
import id.ac.uika.everest.ui.StatusChip
import id.ac.uika.everest.util.ZONE
import id.ac.uika.everest.util.formatDate
import id.ac.uika.everest.util.formatDateTime
import id.ac.uika.everest.util.formatDuration
import id.ac.uika.everest.util.formatOffset
import id.ac.uika.everest.util.formatTime
import id.ac.uika.everest.util.now
import id.ac.uika.everest.whisper.ModelDownloadWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset

private val NOTE_TYPES = listOf("pribadi" to "Pribadi", "rapat" to "Rapat", "kuliah" to "Kuliah", "lapangan" to "Lapangan")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(nav: NavController) {
    val notes by remember { Locator.db.notes().observeAll() }.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SegmentHit>>(emptyList()) }
    var type by remember { mutableStateOf<String?>(null) }
    var day by remember { mutableStateOf<Long?>(null) } // awal hari (epoch ms, zona lokal)
    var pickDate by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        delay(250)
        val q = Keywords.ftsQuery(query)
        hits = if (q == null) emptyList() else runCatching { Locator.db.segments().search(q, limit = 50) }.getOrDefault(emptyList())
    }

    val filtered = notes.filter { n ->
        (type == null || n.type == type) && (day == null || (n.startedAt >= day!! && n.startedAt < day!! + 86_400_000))
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Catatan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { pickDate = true }) { Icon(Icons.Filled.CalendarMonth, contentDescription = "Pilih tanggal") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Hapus") } },
            placeholder = { Text("Cari kata di semua transkrip") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = type == null, onClick = { type = null }, label = { Text("Semua") })
            NOTE_TYPES.forEach { (k, l) -> FilterChip(selected = type == k, onClick = { type = if (type == k) null else k }, label = { Text(l) }) }
        }
        day?.let { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.formatDate(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { day = null }) { Text("Semua tanggal") }
            }
        }

        if (query.isNotBlank()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hits.isEmpty()) item { EmptyState("Tidak ditemukan", "Tidak ada transkrip yang memuat \"$query\".") }
                items(hits, key = { "${it.noteId}:${it.startMs}" }) { h ->
                    OutlinedCard(Modifier.fillMaxWidth().clickable { nav.navigate("catatan/${h.noteId}?t=${h.startMs}") }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(h.title, fontWeight = FontWeight.SemiBold)
                            Text("${h.startedAt.formatDateTime()} · menit ${formatOffset(h.startMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("“${h.text}”", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        } else {
            val grouped = filtered.groupBy { it.startedAt.formatDate() }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (filtered.isEmpty()) item { EmptyState("Belum ada catatan", "Rekaman dan meeting Anda akan tampil di sini, dikelompokkan per tanggal.") }
                grouped.forEach { (date, list) ->
                    item(key = "h$date") { Text(date, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 8.dp)) }
                    items(list, key = { it.id }) { n -> NoteRow(n) { nav.navigate("catatan/${n.id}") } }
                }
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = day ?: now())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    // DatePicker memberi tengah malam UTC; ubah ke awal hari zona lokal
                    day = state.selectedDateMillis?.let { utc ->
                        Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZONE).toInstant().toEpochMilli()
                    }
                    pickDate = false
                }) { Text("Pilih") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Batal") } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun NoteRow(n: NoteEntity, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (n.status != "selesai") StatusChip(n.status)
            }
            Text(
                "${n.startedAt.formatTime()} · ${formatDuration(n.durationS)} · ${NOTE_TYPES.firstOrNull { it.first == n.type }?.second ?: n.type}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            n.summary?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 2, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NoteDetailScreen(nav: NavController, noteId: String, startAtMs: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val note by remember(noteId) { Locator.db.notes().observe(noteId) }.collectAsState(initial = null)
    val segments by remember(noteId) { Locator.db.segments().observeForNote(noteId) }.collectAsState(initial = emptyList())
    var player by remember { mutableStateOf<NotePlayer?>(null) }
    val notPlaying = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
    val playing by (player?.playing ?: notPlaying).collectAsState()
    var position by remember { mutableLongStateOf(0L) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editSeg by remember { mutableStateOf<SegmentEntity?>(null) }
    val listState = rememberLazyListState()
    val downloading by remember { ModelDownloadWorker.progress(context) }.collectAsState(initial = null)

    LaunchedEffect(noteId, note?.chunkCount, note?.status) {
        player?.release()
        player = NotePlayer(Locator.db.chunks().forNote(noteId))
    }
    DisposableEffect(noteId) { onDispose { player?.release() } }
    LaunchedEffect(playing) {
        while (playing) {
            position = player?.positionMs() ?: 0
            delay(300)
        }
    }
    // Dibuka dari hasil pencarian/Farhan: gulir ke segmen yang relevan
    LaunchedEffect(segments.size, startAtMs) {
        if (startAtMs >= 0 && segments.isNotEmpty()) {
            val i = segments.indexOfLast { it.startMs <= startAtMs }.coerceAtLeast(0)
            listState.scrollToItem(i)
            position = startAtMs
        }
    }

    val n = note ?: return
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(n.title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") } },
            actions = {
                IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, "Ubah") }
                IconButton(onClick = {
                    val text = buildString {
                        appendLine(n.title)
                        appendLine(n.startedAt.formatDateTime())
                        appendLine()
                        segments.forEach { appendLine("[${formatOffset(it.startMs)}] ${it.text}") }
                    }
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, n.title).putExtra(Intent.EXTRA_TEXT, text), "Bagikan transkrip"))
                }) { Icon(Icons.Filled.Share, "Bagikan") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Hapus") }
            },
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${n.startedAt.formatDateTime()} · ${formatDuration(n.durationS)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                StatusChip(n.status)
            }
            if (n.status == "menunggu_model") {
                Text(
                    downloading?.let { "Mengunduh model transkripsi… $it%" } ?: "Model transkripsi belum terunduh. Unduhan dimulai otomatis saat ada internet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                downloading?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }
            }
            if (n.status == "memproses") Text("Transkrip sedang dibuat di HP…", style = MaterialTheme.typography.bodySmall)
            if (n.keywords.isNotBlank()) Text(n.keywords.replace(",", " · "), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            val p = player
            if (p?.available == true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(onClick = { if (playing) p.pause() else p.play(position) }) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Jeda" else "Putar")
                    }
                    Text("  ${formatOffset(position)} / ${formatOffset(p.durationMs)}", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (n.chunkCount == 0 && n.status == "selesai") {
                Text("Audio tidak tersimpan di HP ini (catatan disinkronkan dari perangkat lain).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (segments.isNotEmpty() && TranscriptEdit.editable(n.status)) {
                Text("Tekan lama sebuah baris untuk memperbaiki teks yang salah dengar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (segments.isEmpty() && n.status == "selesai") item { EmptyState("Tidak ada ucapan", "Tidak ada suara yang terdeteksi pada rekaman ini.") }
            items(segments, key = { it.id }) { s ->
                val active = position >= s.startMs && position < s.endMs
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (active) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClickLabel = "Putar dari sini",
                            onClick = { position = s.startMs; player?.play(s.startMs) },
                            onLongClickLabel = "Perbaiki teks",
                            onLongClick = { if (TranscriptEdit.editable(n.status)) editSeg = s },
                        )
                        .padding(8.dp),
                ) {
                    Text(formatOffset(s.startMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.width(52.dp))
                    Text(s.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    if (editing) {
        var title by remember { mutableStateOf(n.title) }
        var t by remember { mutableStateOf(n.type) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Ubah catatan") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Judul") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        NOTE_TYPES.forEach { (k, l) -> FilterChip(selected = t == k, onClick = { t = k }, label = { Text(l) }) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        Locator.db.notes().upsert(n.copy(title = title.trim().ifEmpty { n.title }, type = t, updatedAt = now(), synced = false))
                        if (n.status == "selesai") Locator.outbox.enqueue(OutKind.NOTE, n.id)
                    }
                    editing = false
                }) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Batal") } },
        )
    }

    editSeg?.let { seg ->
        var text by remember(seg.id) { mutableStateOf(seg.text) }
        var confirmRemove by remember(seg.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editSeg = null },
            title = { Text("Perbaiki transkrip · ${formatOffset(seg.startMs)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (player?.available == true) {
                        TextButton(onClick = { position = seg.startMs; player?.play(seg.startMs) }) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Text(" Dengarkan bagian ini")
                        }
                    }
                    OutlinedTextField(
                        text,
                        { text = it },
                        label = { Text("Teks") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (confirmRemove) {
                        Text("Baris ini akan dihapus dari transkrip. Lanjutkan?", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = if (confirmRemove) "" else text
                        scope.launch { TranscriptEdit.save(n.id, seg.id, value) }
                        editSeg = null
                    },
                    enabled = confirmRemove || TranscriptEdit.clean(text).isNotEmpty(),
                ) { Text(if (confirmRemove) "Hapus baris" else "Simpan") }
            },
            dismissButton = {
                Row {
                    if (!confirmRemove) TextButton(onClick = { confirmRemove = true }) { Text("Hapus baris", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { if (confirmRemove) confirmRemove = false else editSeg = null }) { Text("Batal") }
                }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Hapus catatan?") },
            text = { Text("Audio dan transkrip dihapus permanen dari HP ini dan dari server.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        player?.release()
                        Locator.db.notes().upsert(n.copy(deleted = true, status = "selesai", summary = null, keywords = "", updatedAt = now(), synced = false))
                        Locator.db.segments().deleteForNote(n.id)
                        Locator.db.chunks().deleteForNote(n.id)
                        java.io.File(n.audioDir).deleteRecursively()
                        Locator.outbox.enqueue(OutKind.NOTE, n.id)
                        nav.popBackStack()
                    }
                    confirmDelete = false
                }) { Text("Hapus", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Batal") } },
        )
    }
}
