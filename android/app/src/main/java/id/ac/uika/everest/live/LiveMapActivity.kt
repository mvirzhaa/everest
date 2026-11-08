package id.ac.uika.everest.live

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import id.ac.uika.everest.Locator
import id.ac.uika.everest.ui.theme.EverestSystemBars
import id.ac.uika.everest.ui.theme.EverestTheme
import id.ac.uika.everest.ui.theme.LocalEverestDark

/**
 * Peta live di dalam aplikasi (penerima panic dan berbagi lokasi): halaman /live/<token> dari server
 * Everest dimuat dalam WebView, jadi jejak, status, jarak dari posisi saya, dan pembaruan real-time
 * sama persis dengan versi web. Tautan telepon/navigasi dibuka di aplikasi yang sesuai.
 */
class LiveMapActivity : ComponentActivity() {
    private var web: WebView? = null
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val granted = res.values.any { it }
        pendingGeo?.let { (origin, cb) -> cb.invoke(origin, granted, false) }
        pendingGeo = null
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // petugas sedang menuju lokasi
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Peta live" }
        if (!isAllowed(url)) {
            finish()
            return
        }
        setContent {
            EverestTheme {
                EverestSystemBars(this)
                val dark = LocalEverestDark.current
                var progress by remember { mutableIntStateOf(0) }
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    TopAppBar(
                        title = { Text(title, maxLines = 1) },
                        navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") } },
                        actions = { IconButton(onClick = { web?.reload() }) { Icon(Icons.Filled.Refresh, "Muat ulang") } },
                    )
                    if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx -> createWebView(ctx) { progress = it }.also { web = it; it.loadUrl(embedUrl(url, dark)) } },
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(ctx: Context, onProgress: (Int) -> Unit) = WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setGeolocationEnabled(true)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (isAllowed(u.toString())) return false // tetap di peta live server Everest
                // tel:, Google Maps, navigasi, dll. → aplikasi di HP
                val intent = if (u.scheme == "tel") Intent(Intent.ACTION_DIAL, u) else Intent(Intent.ACTION_VIEW, u)
                runCatching { startActivity(intent) }
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                // Hanya halaman server Everest yang boleh meminta lokasi
                if (!isAllowed(origin)) return callback.invoke(origin, false, false)
                if (hasLocation()) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeo = origin to callback
                    askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
        }
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** URL berasal dari server Everest yang dikonfigurasi (cegah WebView dipakai membuka situs lain). */
    private fun isAllowed(url: String): Boolean {
        val target = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val server = Uri.parse(Locator.prefs.serverUrl)
        return target.scheme in setOf("http", "https") && target.host == server.host && target.port == server.port
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onPause() {
        web?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun embedUrl(url: String, dark: Boolean): String =
            Uri.parse(url).buildUpon()
                .appendQueryParameter("embed", "1")
                .appendQueryParameter("theme", if (dark) "dark" else "light")
                .build().toString()

        fun intentFor(ctx: Context, url: String, title: String? = null) = Intent(ctx, LiveMapActivity::class.java)
            .putExtra(EXTRA_URL, url)
            .putExtra(EXTRA_TITLE, title)

        /** Buka peta live; bila URL bukan dari server Everest (mis. server diganti), jatuh ke peramban. */
        fun open(ctx: Context, url: String?, title: String? = null) {
            if (url.isNullOrBlank()) return
            val server = Uri.parse(Locator.prefs.serverUrl)
            val u = Uri.parse(url)
            val i = if (u.host == server.host && u.port == server.port) intentFor(ctx, url, title) else Intent(Intent.ACTION_VIEW, u)
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }
        }

        fun pending(ctx: Context, url: String, title: String?, requestCode: Int): PendingIntent =
            PendingIntent.getActivity(
                ctx,
                requestCode,
                intentFor(ctx, url, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
