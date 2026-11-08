package id.ac.uika.everest.report

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import id.ac.uika.everest.Locator
import id.ac.uika.everest.data.local.ReportEntity
import id.ac.uika.everest.sync.OutKind
import id.ac.uika.everest.track.TrackingService
import id.ac.uika.everest.ui.MainActivity
import id.ac.uika.everest.ui.theme.EverestTheme
import id.ac.uika.everest.util.isOnline
import id.ac.uika.everest.util.newId
import id.ac.uika.everest.util.now
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

val REPORT_CATEGORIES = listOf(
    "fasilitas" to "Fasilitas rusak",
    "kebersihan" to "Kebersihan",
    "keamanan" to "Keamanan",
    "lingkungan" to "Lingkungan",
    "lainnya" to "Lainnya",
)

/** Report: kamera → pratinjau → deskripsi (bisa didikte) → simpan. Terkirim otomatis saat online. */
class ReportActivity : ComponentActivity() {
    private val reportId = newId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Locator.prefs.isLoggedIn) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContent { EverestTheme { ReportFlow() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ReportFlow() {
        var photo by remember { mutableStateOf<File?>(null) }
        var preview by remember { mutableStateOf<Bitmap?>(null) }
        var hasCamera by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
        val camPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
        LaunchedEffect(Unit) { if (!hasCamera) camPermission.launch(Manifest.permission.CAMERA) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (photo == null) "Foto laporan" else "Detail laporan") },
                    navigationIcon = {
                        IconButton(onClick = { if (photo != null) { photo = null; preview = null } else finish() }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Kembali")
                        }
                    },
                )
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when {
                    !hasCamera -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Izin kamera diperlukan untuk memotret laporan.")
                        Button(onClick = { camPermission.launch(Manifest.permission.CAMERA) }) { Text("Izinkan kamera") }
                    }
                    photo == null -> CameraCapture { file ->
                        photo = file
                        preview = BitmapFactory.decodeFile(file.absolutePath)
                    }
                    else -> ReportForm(photo!!, preview)
                }
            }
        }
    }

    @Composable
    private fun CameraCapture(onCaptured: (File) -> Unit) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
        var busy by remember { mutableStateOf(false) }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).also { view ->
                        val future = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            val provider = future.get()
                            val prev = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, prev, capture)
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
            )
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp).navigationBarsPadding()
                    .size(80.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
                    .background(if (busy) Color.Gray else Color.White, CircleShape),
            ) {
                IconButton(
                    modifier = Modifier.fillMaxSize(),
                    enabled = !busy,
                    onClick = {
                        busy = true
                        val raw = File(cacheDir, "capture_$reportId.jpg")
                        capture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(raw).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    busy = false
                                    onCaptured(raw)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    busy = false
                                    Toast.makeText(context, "Gagal memotret: ${exception.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    },
                ) {}
            }
        }
    }

    @Composable
    private fun ReportForm(raw: File, preview: Bitmap?) {
        var description by remember { mutableStateOf("") }
        var category by remember { mutableStateOf("fasilitas") }
        var location by remember { mutableStateOf<Pair<Double, Double>?>(null) }
        var saving by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            TrackingService.currentLocation(this@ReportActivity, 4_000)?.let { location = it.latitude to it.longitude }
        }
        val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) description = listOf(description.trim(), text).filter { it.isNotEmpty() }.joinToString(" ")
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            preview?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = "Foto laporan",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
                )
            }
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Deskripsi (wajib)") },
                placeholder = { Text("Apa yang terjadi dan di mana?") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = {
                        dictation.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ceritakan laporan Anda"),
                        )
                    }) { Icon(Icons.Filled.Mic, contentDescription = "Dikte deskripsi") }
                },
            )
            Text("Kategori", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                REPORT_CATEGORIES.take(3).forEach { (key, label) -> FilterChip(selected = category == key, onClick = { category = key }, label = { Text(label) }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                REPORT_CATEGORIES.drop(3).forEach { (key, label) -> FilterChip(selected = category == key, onClick = { category = key }, label = { Text(label) }) }
            }
            Text(
                location?.let { "Lokasi terlampir otomatis" } ?: "Lokasi tidak terlampir (izin lokasi/GPS belum tersedia)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        val ok = save(raw, description.trim(), category, location)
                        saving = false
                        if (ok) finish()
                    }
                },
                enabled = description.isNotBlank() && !saving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (saving) "Menyimpan…" else "Simpan laporan") }
            OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Batal") }
        }
    }

    private suspend fun save(raw: File, description: String, category: String, location: Pair<Double, Double>?): Boolean {
        val out = File(filesDir, "reports").apply { mkdirs() }.resolve("$reportId.jpg")
        val ok = withContext(Dispatchers.IO) { runCatching { normalizePhoto(raw, out) }.isSuccess }
        if (!ok) {
            Toast.makeText(this, "Foto gagal diproses, coba potret ulang", Toast.LENGTH_LONG).show()
            return false
        }
        raw.delete()
        Locator.db.reports().upsert(
            ReportEntity(
                id = reportId,
                photoPath = out.absolutePath,
                description = description,
                category = category,
                lat = location?.first,
                lng = location?.second,
                createdAt = now(),
            ),
        )
        Locator.outbox.enqueue(OutKind.REPORT, reportId)
        Toast.makeText(
            this,
            if (isOnline(this)) "Laporan disimpan dan sedang dikirim" else "Laporan disimpan. Terkirim otomatis saat ada sinyal.",
            Toast.LENGTH_LONG,
        ).show()
        return true
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}

/**
 * Putar sesuai orientasi EXIF, perkecil (sisi terpanjang 1600 px), simpan JPEG baru.
 * Menulis ulang JPEG dari bitmap membuang seluruh metadata EXIF (model HP, GPS, dsb).
 */
fun normalizePhoto(src: File, dst: File, maxSide: Int = 1600) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(src.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val decoded = BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IllegalStateException("foto tidak terbaca")
    val rotation = when (ExifInterface(src.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
    val m = Matrix().apply {
        postScale(scale, scale)
        postRotate(rotation)
    }
    val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
    dst.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 82, it) }
    if (out !== decoded) out.recycle()
    decoded.recycle()
}
