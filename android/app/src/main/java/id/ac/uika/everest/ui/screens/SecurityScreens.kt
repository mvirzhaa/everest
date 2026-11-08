package id.ac.uika.everest.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.ac.uika.everest.realtime.Realtime
import id.ac.uika.everest.ui.theme.statusColor
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import id.ac.uika.everest.Locator
import id.ac.uika.everest.auth.Repo
import id.ac.uika.everest.data.remote.PanicDto
import id.ac.uika.everest.live.LiveMapActivity
import id.ac.uika.everest.data.remote.ReportDto
import id.ac.uika.everest.panic.PanicActivity
import id.ac.uika.everest.panic.PanicManager
import id.ac.uika.everest.report.REPORT_CATEGORIES
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
import id.ac.uika.everest.util.parseIso
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SecurityScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tracking by Locator.prefs.trackingFlow.collectAsState()
    val siren by Locator.prefs.sirenFlow.collectAsState()
    val panics by remember { Locator.db.panics().observeAll() }.collectAsState(initial = emptyList())
    val sessions by remember { Locator.db.tracks().observeSessions() }.collectAsState(initial = emptyList())
    val contacts by remember { Locator.db.contacts().observeAll() }.collectAsState(initial = emptyList())
    val activePanic = panics.firstOrNull { it.id == PanicManager.activePanicId() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Keamanan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp)) }
        if (activePanic != null) item {
            Card(colors = CardDefaults.cardColors(containerColor = PanicRed, contentColor = Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Panic aktif sejak ${activePanic.createdAt.formatDateTime()}", fontWeight = FontWeight.Bold)
                    Text(if (activePanic.sent) "Terkirim ke server · status: ${activePanic.status}" else "Menunggu sinyal untuk dikirim ke satpam" + if (activePanic.smsSent) " · SMS cadangan terkirim" else "")
                    Button(
                        onClick = { scope.launch { PanicManager.markSafe(context) } },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = PanicRed),
                    ) { Text("Saya sudah aman") }
                }
            }
        } else item {
            Button(
                onClick = { context.startActivity(Intent(context, PanicActivity::class.java)) },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PanicRed, contentColor = Color.White),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Filled.Warning, null)
                Text("  PANIC (hitung mundur 5 detik)", fontWeight = FontWeight.Bold)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { if (tracking != null) TrackingService.stop(context) else context.startActivity(Intent(context, TrackStartActivity::class.java)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.MyLocation, null)
                    Text(if (tracking != null) "  Stop berbagi" else "  Bagikan lokasi")
                }
                OutlinedButton(onClick = { context.startActivity(Intent(context, ReportActivity::class.java)) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CameraAlt, null)
                    Text("  Buat laporan")
                }
            }
        }
        item {
            Button(
                onClick = { if (siren) SirenService.stop(context) else context.startActivity(Intent(context, SirenActivity::class.java)) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SirenAmber, contentColor = OnSiren),
                shape = RoundedCornerShape(18.dp),
            ) { Text(if (siren) "Matikan sirine" else "Sirine (hitung mundur 5 detik)", fontWeight = FontWeight.Bold) }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth().clickable { nav.navigate("kontak") }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.People, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Kontak darurat", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (contacts.isEmpty()) "Belum ada. Tambahkan minimal satu orang." else contacts.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (contacts.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth().clickable { nav.navigate("laporan") }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CameraAlt, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Laporan saya", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        item { SectionTitle("Riwayat panic") }
        if (panics.isEmpty()) item { Text("Belum pernah.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(panics.take(20), key = { it.id }) { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.createdAt.formatDateTime())
                    Text(if (p.sent) "Terkirim" else "Belum terkirim", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusChip(p.status)
            }
        }
        item { SectionTitle("Riwayat berbagi lokasi") }
        if (sessions.isEmpty()) item { Text("Belum pernah.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(sessions.take(20), key = { "s" + it.id }) { s ->
            Row(Modifier.fillMaxWidth().clickable(enabled = s.liveUrl != null) {
                LiveMapActivity.open(context, s.liveUrl, if (s.kind == "panic") "Panic saya" else "Lokasi yang saya bagikan")
            }) {
                Column(Modifier.weight(1f)) {
                    Text((if (s.kind == "panic") "Panic · " else "") + s.startedAt.formatDateTime())
                    Text(
                        s.endedAt?.let { "selesai ${it.formatDateTime()}" } ?: "sedang berjalan",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Text("") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    val contacts by remember { Locator.db.contacts().observeAll() }.collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { Repo.refreshContacts() }.onFailure { message = "Daftar dari HP (offline)" } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Kontak darurat") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") } },
            actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Tambah") } },
        )
        Text(
            "Kontak darurat menerima sinyal panic (wajib, bersama satpam kampus) dan bisa dipilih saat berbagi lokasi. " +
                "Isi email Everest agar mereka menerima notifikasi; isi nomor HP untuk SMS cadangan saat tidak ada internet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (contacts.isEmpty()) item { EmptyState("Belum ada kontak darurat", "Tambahkan keluarga atau teman yang bisa dihubungi saat darurat.") }
            items(contacts, key = { it.id }) { c ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(c.contactEmail, c.phone).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            if (c.contactUserId == null) Text("Hanya SMS (belum memakai Everest)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = {
                            scope.launch { runCatching { Repo.deleteContact(c.id) }.onFailure { message = Locator.client.errorMessage(it) } }
                        }) { Icon(Icons.Filled.Delete, "Hapus") }
                    }
                }
            }
        }
    }

    if (adding) {
        var email by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Tambah kontak darurat") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nama") }, singleLine = true)
                    OutlinedTextField(email, { email = it }, label = { Text("Email akun Everest") }, singleLine = true)
                    OutlinedTextField(phone, { phone = it }, label = { Text("Nomor HP (untuk SMS)") }, singleLine = true)
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { Repo.addContact(email, phone, name) }
                            .onSuccess { adding = false }
                            .onFailure { err = Locator.client.errorMessage(it) }
                    }
                }, enabled = email.isNotBlank() || phone.isNotBlank()) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Batal") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(nav: NavController) {
    val context = LocalContext.current
    val reports by remember { Locator.db.reports().observeAll() }.collectAsState(initial = emptyList())
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { Repo.refreshReports() } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Laporan saya") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") } },
            actions = { IconButton(onClick = { context.startActivity(Intent(context, ReportActivity::class.java)) }) { Icon(Icons.Filled.Add, "Buat laporan") } },
        )
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    runCatching { Repo.refreshReports() }
                    refreshing = false
                }
            },
        ) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (reports.isEmpty()) item { EmptyState("Belum ada laporan", "Tekan Report di widget: potret, tulis deskripsi, simpan.") }
                items(reports, key = { it.id }) { r ->
                    OutlinedCard(Modifier.fillMaxWidth().clickable { nav.navigate("laporan/${r.id}") }) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = File(r.photoPath),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp),
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(r.description, maxLines = 2, fontWeight = FontWeight.Medium)
                                Text(
                                    "${r.createdAt.formatDateTime()} · ${REPORT_CATEGORIES.firstOrNull { it.first == r.category }?.second ?: r.category}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StatusChip(r.status)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDetailScreen(nav: NavController, id: String) {
    val local by remember(id) { Locator.db.reports().observe(id) }.collectAsState(initial = null)
    var remote by remember { mutableStateOf<ReportDto?>(null) }
    LaunchedEffect(id) { remote = runCatching { Repo.reportDetail(id) }.getOrNull() }
    val r = local

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Detail laporan") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") } },
        )
        if (r == null && remote == null) {
            EmptyState("Laporan tidak ditemukan", "")
            return@Column
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(
                model = r?.photoPath?.let { File(it) }?.takeIf { it.exists() } ?: remote?.photoUrl,
                contentDescription = "Foto laporan",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(remote?.status ?: r?.status ?: "menunggu_kirim")
                Text("  " + (r?.createdAt?.formatDateTime() ?: remote?.createdAt?.parseIso()?.formatDateTime().orEmpty()), style = MaterialTheme.typography.bodySmall)
            }
            Text(r?.description ?: remote?.description.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            if (r?.status == "menunggu_kirim") {
                Text("Belum terkirim. Laporan dikirim otomatis begitu ada sinyal.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            remote?.history?.takeIf { it.isNotEmpty() }?.let { history ->
                SectionTitle("Riwayat tindak lanjut")
                history.forEach { h ->
                    Row(verticalAlignment = Alignment.Top) {
                        StatusChip(h.status)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(h.createdAt.parseIso().formatDateTime() + (h.actorName?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                            h.note?.let { Text(it) }
                        }
                    }
                }
            } ?: r?.adminNote?.let { Text("Catatan admin: $it") }
        }
    }
}

/**
 * Mode petugas (satpam/admin): status jaga dan panic aktif.
 * Real-time lewat WebSocket selama layar terlihat; polling hanya cadangan (15 dtk saat terputus, 60 dtk saat tersambung).
 */
@Composable
fun GuardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user by Locator.prefs.userFlow.collectAsState()
    var panics by remember { mutableStateOf<List<PanicDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val rt = Locator.realtime
    val rtState by rt.state.collectAsState()

    suspend fun reload() {
        runCatching { Repo.activePanics() }
            .onSuccess { list ->
                panics = list
                error = null
                list.forEach { p -> p.sessionId?.let(rt::watch) }
            }
            .onFailure { error = Locator.client.errorMessage(it) }
    }

    // Tersambung hanya saat layar ini terlihat (hemat baterai); di latar belakang tetap lewat FCM
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        var held = false
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START && !held) { rt.acquire(); held = true }
            if (e == Lifecycle.Event.ON_STOP && held) { rt.release(); held = false }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            if (held) rt.release()
        }
    }
    LaunchedEffect(Unit) {
        rt.events.collect { e ->
            when (e.name) {
                "panic", "panic_status", "panic_escalated", Realtime.CONNECTED -> reload()
                "panic_location" -> {
                    val pid = e.data["panic_id"]?.jsonPrimitive?.contentOrNull ?: return@collect
                    val lat = e.data["lat"]?.jsonPrimitive?.doubleOrNull
                    val lng = e.data["lng"]?.jsonPrimitive?.doubleOrNull
                    val at = e.data["recorded_at"]?.jsonPrimitive?.contentOrNull
                    panics = panics.map { if (it.id == pid) it.copy(lastLat = lat ?: it.lastLat, lastLng = lng ?: it.lastLng, lastAt = at ?: it.lastAt) else it }
                }
            }
        }
    }
    LaunchedEffect(rtState) {
        while (true) {
            reload()
            delay(if (rtState == Realtime.State.ONLINE) 60_000 else 15_000)
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Petugas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Bertugas  ")
                Switch(checked = user?.onDuty == true, onCheckedChange = { v ->
                    scope.launch { runCatching { Repo.setDuty(v) }.onFailure { error = Locator.client.errorMessage(it) } }
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (dot, label) = when (rtState) {
                    Realtime.State.ONLINE -> statusColor("aman") to "Real-time tersambung"
                    Realtime.State.CONNECTING -> statusColor("diterima") to "Menyambung…"
                    Realtime.State.RETRYING -> statusColor("aktif") to "Terputus, mencoba lagi (cadangan: perbarui tiap 15 detik)"
                    Realtime.State.OFF -> MaterialTheme.colorScheme.onSurfaceVariant to "Tidak tersambung"
                }
                Box(Modifier.size(10.dp).background(dot, CircleShape))
                Text("  $label", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Saat bertugas, semua panic kampus masuk ke HP ini dengan alarm. Jika tidak ada satpam yang bertugas, panic dikirim ke semua satpam. " +
                    "Panic yang tidak diterima dalam beberapa menit diteruskan ke semua satpam, lalu ke admin.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
        if (panics.isEmpty()) item { EmptyState("Tidak ada panic aktif", "Panic baru langsung muncul di sini.") }
        items(panics, key = { it.id }) { p ->
            Card(colors = CardDefaults.cardColors(containerColor = if (p.status == "aktif") PanicRed.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.userName ?: "Pengguna", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        StatusChip(p.status)
                    }
                    if (p.status == "aktif" && p.escalationLevel > 0) {
                        Text(
                            if (p.escalationLevel >= 2) "Eskalasi ke admin: belum ada yang menerima" else "Eskalasi ke semua satpam: belum ada yang menerima",
                            color = statusColor("aktif"),
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "Mulai ${p.createdAt.parseIso().formatDateTime()}" + (p.lastAt?.let { " · posisi terakhir ${it.parseIso().formatDateTime()}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        p.liveUrl?.let { url -> OutlinedButton(onClick = { LiveMapActivity.open(context, url, "PANIC — ${p.userName ?: "Pengguna"}") }) { Text("Peta live") } }
                        val lat = p.lastLat ?: p.lat
                        val lng = p.lastLng ?: p.lng
                        if (p.liveUrl == null && lat != null && lng != null) OutlinedButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lng")))
                        }) { Text("Maps") }
                        p.userPhone?.takeIf { it.isNotBlank() }?.let { tel ->
                            OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$tel"))) }) { Text("Telepon") }
                        }
                    }
                    when (p.status) {
                        "aktif" -> Button(onClick = { scope.launch { Repo.setPanicStatus(p.id, "diterima"); reload() } }, colors = ButtonDefaults.buttonColors(containerColor = PanicRed, contentColor = Color.White)) {
                            Text("Terima & tangani")
                        }
                        "diterima" -> Button(onClick = { scope.launch { Repo.setPanicStatus(p.id, "ditangani"); reload() } }) {
                            Text("Tandai sudah ditangani")
                        }
                    }
                }
            }
        }
    }
}
