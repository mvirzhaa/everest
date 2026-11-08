package id.ac.uika.everest.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.ac.uika.everest.BuildConfig
import id.ac.uika.everest.R
import androidx.compose.ui.res.painterResource
import id.ac.uika.everest.Locator
import id.ac.uika.everest.auth.AuthManager
import id.ac.uika.everest.ui.theme.LocalEverestDark
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch

@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var email by remember { mutableStateOf("") }
    var showServer by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(Locator.prefs.serverUrl) }

    val ssoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode != Activity.RESULT_OK || data == null) {
            busy = false
            error = "Login dibatalkan"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching { AuthManager.completeSso(context, data) }
                .onFailure { error = Locator.client.errorMessage(it) }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Image(
            painter = painterResource(R.drawable.logo_everest),
            contentDescription = "Everest",
            modifier = Modifier
                .fillMaxWidth(0.82f)
                // Logo bergaris biru tua di latar transparan: beri pelat putih di mode gelap
                .then(if (LocalEverestDark.current) Modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp) else Modifier),
        )
        Text(
            "Rekam & transkrip, panic, berbagi lokasi, laporan, dan asisten Farhan untuk penjelajah UIKA.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (AuthManager.ssoConfigured) {
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching { ssoLauncher.launch(AuthManager.ssoIntent(context)) }
                            .onFailure {
                                busy = false
                                error = "SSO tidak bisa dihubungi: ${it.message}"
                            }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (busy) "Menghubungkan…" else "Masuk dengan SSO UIKA") }
        } else {
            Text("SSO belum dikonfigurasi di aplikasi ini (OIDC_ISSUER).", color = MaterialTheme.colorScheme.error)
        }

        if (BuildConfig.DEV_LOGIN) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Login pengembangan", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching { AuthManager.devLogin(email) }.onFailure { error = Locator.client.errorMessage(it) }
                        busy = false
                    }
                },
                enabled = !busy && email.contains('@'),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Masuk (dev)") }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        TextButton(onClick = { showServer = !showServer }) { Text("Alamat server") }
        if (showServer) {
            OutlinedTextField(
                server,
                { server = it },
                label = { Text("URL server Everest") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { Locator.prefs.serverUrl = server; showServer = false }) { Text("Simpan alamat") }
        }
    }
}
