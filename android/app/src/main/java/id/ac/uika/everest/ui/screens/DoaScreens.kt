package id.ac.uika.everest.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import id.ac.uika.everest.Locator
import id.ac.uika.everest.doa.Prayer
import id.ac.uika.everest.doa.PrayerBook
import id.ac.uika.everest.ui.EmptyState

/** Daftar doa: Perjalanan & Bepergian, Ayat Kursi, Ayat-ayat Ruqyah. Semua tersedia offline. */
@Composable
fun DoaScreen(nav: NavController, initialCategory: String? = null) {
    val context = LocalContext.current
    val data = remember { PrayerBook.load(context) }
    var category by remember { mutableStateOf(initialCategory ?: data.categories.first().id) }
    var query by remember { mutableStateOf("") }
    val results = remember(query) { if (query.isBlank()) emptyList() else PrayerBook.search(context, query) }
    val current = data.categories.firstOrNull { it.id == category } ?: data.categories.first()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") }
            Text("Doa & Ayat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Hapus") } },
            placeholder = { Text("Cari: kendaraan, pulang, sakit…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (query.isBlank()) {
            LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                items(data.categories, key = { it.id }) { c ->
                    FilterChip(selected = c.id == category, onClick = { category = c.id }, label = { Text(c.title) })
                }
            }
        }
        val list = if (query.isBlank()) current.items else results
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (query.isBlank()) item { Text(current.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (list.isEmpty()) item { EmptyState("Tidak ditemukan", "Coba kata lain, misalnya \"kendaraan\" atau \"pulang\".") }
            items(list, key = { it.id }) { p ->
                OutlinedCard(Modifier.fillMaxWidth().clickable { nav.navigate("doa/baca/${p.id}") }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.title, fontWeight = FontWeight.SemiBold)
                        p.`when`?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        val preview = p.arabic ?: p.verses.firstOrNull()?.arabic
                        preview?.let {
                            Text(
                                it,
                                maxLines = 1,
                                style = arabicStyle(20),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    data.credits.joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
}

private fun arabicStyle(size: Int) = TextStyle(
    fontSize = size.sp,
    lineHeight = (size * 1.9).sp,
    textDirection = TextDirection.Rtl,
    textAlign = TextAlign.Right,
)

/** Layar baca satu doa / rangkaian ayat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoaReadScreen(nav: NavController, id: String) {
    val context = LocalContext.current
    val prayer = remember(id) { PrayerBook.find(context, id) }
    var size by remember { mutableIntStateOf(Locator.prefs.arabicSize) }
    var showLatin by remember { mutableStateOf(Locator.prefs.showLatin) }
    var showMeaning by remember { mutableStateOf(Locator.prefs.showMeaning) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(prayer?.title ?: "Doa", maxLines = 1) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") } },
            actions = {
                IconButton(onClick = { size = (size - 2).coerceAtLeast(18); Locator.prefs.arabicSize = size }) { Icon(Icons.Filled.TextDecrease, "Perkecil huruf") }
                IconButton(onClick = { size = (size + 2).coerceAtMost(48); Locator.prefs.arabicSize = size }) { Icon(Icons.Filled.TextIncrease, "Perbesar huruf") }
                if (prayer != null) {
                    IconButton(onClick = { copy(context, prayer) }) { Icon(Icons.Filled.ContentCopy, "Salin") }
                    IconButton(onClick = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, prayer.shareText()), "Bagikan doa"))
                    }) { Icon(Icons.Filled.Share, "Bagikan") }
                }
            },
        )
        if (prayer == null) {
            EmptyState("Doa tidak ditemukan", "")
            return@Column
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val hasLatin = prayer.latin != null || prayer.verses.any { it.latin != null }
            if (hasLatin) FilterChip(selected = showLatin, onClick = { showLatin = !showLatin; Locator.prefs.showLatin = showLatin }, label = { Text("Latin") })
            FilterChip(selected = showMeaning, onClick = { showMeaning = !showMeaning; Locator.prefs.showMeaning = showMeaning }, label = { Text("Arti") })
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            prayer.`when`?.let { w -> item { Text("Dibaca: $w", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) } }
            if (prayer.verses.isEmpty()) {
                item {
                    Passage(prayer.arabic.orEmpty(), prayer.latin.takeIf { showLatin }, prayer.meaning.takeIf { showMeaning }, size)
                }
            } else {
                items(prayer.verses, key = { it.ref }) { v ->
                    Passage(
                        arabic = "${v.arabic} ﴿${v.numberAr}﴾",
                        latin = v.latin.takeIf { showLatin },
                        meaning = if (showMeaning) "${v.number}. ${v.meaning}" else null,
                        size = size,
                        label = v.ref,
                    )
                }
                if (showLatin && prayer.verses.any { it.latin == null }) item {
                    Text(
                        "Latin tidak dicantumkan untuk ayat ini agar tidak salah baca. Bacalah dari teks Arab atau dengarkan dari qari.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            prayer.repeat?.let { r -> item { Text("Dibaca $r", fontWeight = FontWeight.SemiBold) } }
            prayer.note?.let { n ->
                item {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Text(n, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Text("Sumber: ${prayer.source}", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { Box(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun Passage(arabic: String, latin: String?, meaning: String?, size: Int, label: String? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(14.dp)).padding(14.dp)) {
            Text(arabic, style = arabicStyle(size).copy(color = MaterialTheme.colorScheme.onSurface), modifier = Modifier.fillMaxWidth())
        }
        latin?.let { Text(it, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.tertiary) }
        meaning?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

private fun copy(context: Context, p: Prayer) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText(p.title, p.shareText()))
    Toast.makeText(context, "Disalin", Toast.LENGTH_SHORT).show()
}
