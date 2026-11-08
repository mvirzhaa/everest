package id.ac.uika.everest.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.os.SystemClock
import android.view.View
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import id.ac.uika.everest.Locator
import id.ac.uika.everest.auth.AuthManager
import id.ac.uika.everest.ui.screens.ContactsScreen
import id.ac.uika.everest.ui.screens.DoaReadScreen
import id.ac.uika.everest.ui.screens.DoaScreen
import id.ac.uika.everest.ui.screens.FarhanHistoryScreen
import id.ac.uika.everest.ui.screens.GuardScreen
import id.ac.uika.everest.ui.screens.HomeScreen
import id.ac.uika.everest.ui.screens.LoginScreen
import id.ac.uika.everest.ui.screens.NoteDetailScreen
import id.ac.uika.everest.ui.screens.NotesScreen
import id.ac.uika.everest.ui.screens.OnboardingScreen
import id.ac.uika.everest.ui.screens.ProfileScreen
import id.ac.uika.everest.ui.screens.ReportDetailScreen
import id.ac.uika.everest.ui.screens.ReportsScreen
import id.ac.uika.everest.ui.screens.SecurityScreen
import id.ac.uika.everest.ui.theme.EverestSystemBars
import id.ac.uika.everest.ui.theme.EverestTheme
import id.ac.uika.everest.ui.screens.ConsentScreen
import id.ac.uika.everest.privacy.Privacy
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Rute dari notifikasi/widget (mis. "catatan/<id>?t=12000"). */
    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash: logo Everest di layar putih. Ditahan minimal 900 ms saat aplikasi baru dibuka
        // agar logo sempat terlihat, lalu memudar dan sedikit membesar.
        val splash = installSplashScreen()
        val shownAt = SystemClock.uptimeMillis()
        if (savedInstanceState == null) splash.setKeepOnScreenCondition { SystemClock.uptimeMillis() - shownAt < SPLASH_MIN_MS }
        splash.setOnExitAnimationListener { provider ->
            val v: View = provider.view
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(provider.iconView, View.SCALE_X, 1f, 1.08f),
                    ObjectAnimator.ofFloat(provider.iconView, View.SCALE_Y, 1f, 1.08f),
                )
                duration = 280
                doOnEnd { provider.remove() }
                start()
            }
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingRoute.value = intent?.getStringExtra(EXTRA_ROUTE)
        setContent {
            EverestTheme {
                EverestSystemBars(this)
                App()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
    }

    @Composable
    private fun App() {
        val user by Locator.prefs.userFlow.collectAsState()
        var onboarded by remember { mutableStateOf(Locator.prefs.onboardingDone) }
        var consented by remember { mutableStateOf(!Privacy.needsConsent(Locator.prefs.consentVersion)) }
        // Keluar akun menghapus persetujuan lokal: minta lagi untuk akun berikutnya
        LaunchedEffect(user) { if (user == null) consented = !Privacy.needsConsent(Locator.prefs.consentVersion) }

        // Token ditolak server → keluar dan kembali ke login
        LaunchedEffect(Unit) {
            Locator.client.unauthorized.collect { if (Locator.prefs.isLoggedIn) AuthManager.signOut(this@MainActivity) }
        }
        LaunchedEffect(user?.id) { if (user != null) AuthManager.refreshMe() }

        when {
            !consented -> ConsentScreen { consented = true }
            user == null -> LoginScreen()
            !onboarded -> OnboardingScreen(isGuard = user?.role in setOf("guard", "admin")) {
                Locator.prefs.onboardingDone = true
                onboarded = true
            }
            else -> MainNav(isStaff = user?.role in setOf("guard", "admin"))
        }
    }

    private data class Tab(val route: String, val label: String, val icon: ImageVector)

    @Composable
    private fun MainNav(isStaff: Boolean) {
        val nav = rememberNavController()
        val tabs = buildList {
            add(Tab("beranda", "Beranda", Icons.Filled.Home))
            add(Tab("catatan", "Catatan", Icons.Filled.Notes))
            add(Tab("farhan", "Farhan", Icons.Filled.SmartToy))
            add(Tab("keamanan", "Keamanan", Icons.Filled.Shield))
            if (isStaff) add(Tab("petugas", "Petugas", Icons.Filled.Badge))
            add(Tab("profil", "Profil", Icons.Filled.Person))
        }
        val backStack by nav.currentBackStackEntryAsState()
        val current = backStack?.destination?.route
        val route by pendingRoute.collectAsState()
        LaunchedEffect(route) {
            route?.let {
                runCatching { nav.navigate(it) { launchSingleTop = true } }
                pendingRoute.value = null
            }
        }

        Scaffold(
            bottomBar = {
                if (tabs.any { it.route == current }) {
                    NavigationBar {
                        tabs.forEach { t ->
                            NavigationBarItem(
                                selected = current == t.route,
                                onClick = { nav.switchTab(t.route) },
                                icon = { Icon(t.icon, contentDescription = t.label) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            },
        ) { pad ->
            NavHost(nav, startDestination = "beranda", modifier = Modifier.padding(pad)) {
                composable("beranda") { HomeScreen(nav) }
                composable("catatan") { NotesScreen(nav) }
                composable(
                    "catatan/{id}?t={t}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("t") { type = NavType.LongType; defaultValue = -1L }),
                ) { e -> NoteDetailScreen(nav, e.arguments?.getString("id").orEmpty(), e.arguments?.getLong("t") ?: -1L) }
                composable("farhan") { FarhanHistoryScreen() }
                composable("keamanan") { SecurityScreen(nav) }
                composable("kontak") { ContactsScreen(nav) }
                composable("laporan") { ReportsScreen(nav) }
                composable("laporan/{id}") { e -> ReportDetailScreen(nav, e.arguments?.getString("id").orEmpty()) }
                composable(
                    "doa?kategori={k}",
                    arguments = listOf(navArgument("k") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { e -> DoaScreen(nav, e.arguments?.getString("k")) }
                composable("doa/baca/{id}") { e -> DoaReadScreen(nav, e.arguments?.getString("id").orEmpty()) }
                composable("petugas") { GuardScreen() }
                composable("profil") { ProfileScreen() }
            }
        }
    }

    private fun NavHostController.switchTab(route: String) = navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        private const val SPLASH_MIN_MS = 900L
    }
}
