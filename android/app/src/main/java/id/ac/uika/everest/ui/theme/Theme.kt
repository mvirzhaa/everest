package id.ac.uika.everest.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.activity.enableEdgeToEdge
import id.ac.uika.everest.Locator

/** Oranye cerah utama: tombol, ikon, aksen (kontras 3:1 dengan teks putih). */
val Brand = Color(0xFFF26B0F)
/** Oranye tua untuk TULISAN di atas putih (kontras 5:1, terbaca di teks kecil). */
val BrandText = Color(0xFFB45309)
val PanicRed = Color(0xFFC0352B)
/** Sirine: kuning amber dengan teks gelap, beda jelas dari oranye merek dan merah panic. */
val SirenAmber = Color(0xFFFFB300)
val OnSiren = Color(0xFF2B1700)
val WarnAmber = Color(0xFFB45309)
val OkGreen = Color(0xFF2F7D4F)

/** Pilihan tema di Profil. Bawaan: terang (nuansa putih-oranye Everest). */
enum class ThemeMode(val key: String, val label: String) {
    LIGHT("light", "Terang"),
    DARK("dark", "Gelap"),
    SYSTEM("system", "Ikuti sistem");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: LIGHT
    }
}

private val Light = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3CC),
    onPrimaryContainer = Color(0xFF5A2600),
    secondary = BrandText,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFEDDC),
    onSecondaryContainer = Color(0xFF5A2600),
    tertiary = BrandText,
    error = PanicRed,
    background = Color.White,
    onBackground = Color(0xFF1F1A16),
    surface = Color.White,
    onSurface = Color(0xFF1F1A16),
    surfaceVariant = Color(0xFFFFF4EA),
    onSurfaceVariant = Color(0xFF5F5650),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFAF5),
    surfaceContainer = Color(0xFFFFF6EE),
    surfaceContainerHigh = Color(0xFFFFF1E4),
    surfaceContainerHighest = Color(0xFFFFEBD9),
    outline = Color(0xFFD9C3B0),
    outlineVariant = Color(0xFFF0E1D4),
)

/**
 * Mode gelap: latar cokelat-hitam hangat, oranye dibuat lebih terang agar tetap "oranye cerah"
 * dan teks di atasnya gelap (kontras > 7:1). Semua pasangan teks/latar ≥ 4.5:1.
 */
private val Dark = darkColorScheme(
    primary = Color(0xFFFF9150),
    onPrimary = Color(0xFF3A1500),
    primaryContainer = Color(0xFF6B2E00),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFFFB680),
    onSecondary = Color(0xFF4A2000),
    secondaryContainer = Color(0xFF4A2A12),
    onSecondaryContainer = Color(0xFFFFDCC2),
    tertiary = Color(0xFFFFB680),
    onTertiary = Color(0xFF4A2000),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5C0F0A),
    background = Color(0xFF15110E),
    onBackground = Color(0xFFF6EEE7),
    surface = Color(0xFF15110E),
    onSurface = Color(0xFFF6EEE7),
    surfaceVariant = Color(0xFF2B221B),
    onSurfaceVariant = Color(0xFFD2C3B8),
    surfaceContainerLowest = Color(0xFF100C0A),
    surfaceContainerLow = Color(0xFF1C1612),
    surfaceContainer = Color(0xFF211A15),
    surfaceContainerHigh = Color(0xFF2B231C),
    surfaceContainerHighest = Color(0xFF362C24),
    outline = Color(0xFF9C8B7E),
    outlineVariant = Color(0xFF4A3D33),
    inverseSurface = Color(0xFFF6EEE7),
    inverseOnSurface = Color(0xFF2B221B),
)

/** true bila layar sedang memakai skema gelap Everest. */
val LocalEverestDark = staticCompositionLocalOf { false }

/** Apakah tema gelap aktif untuk pilihan [mode] (untuk kode di luar Compose, mis. warna jendela). */
fun ThemeMode.isDark(systemDark: Boolean) = when (this) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> systemDark
}

/** Nuansa Everest: putih dan oranye cerah, dengan pilihan mode gelap di Profil. */
@Composable
fun EverestTheme(content: @Composable () -> Unit) {
    val mode by Locator.prefs.themeFlow.collectAsState()
    val dark = mode.isDark(isSystemInDarkTheme())
    CompositionLocalProvider(LocalEverestDark provides dark) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}

/** Warna untuk label status (panic dan laporan), menyesuaikan terang/gelap. */
@Composable
@ReadOnlyComposable
fun statusColor(status: String): Color {
    val dark = LocalEverestDark.current
    return when (status) {
        "aktif", "baru", "gagal" -> if (dark) Color(0xFFFF8A80) else PanicRed
        "diterima", "diproses", "memproses", "menunggu_kirim", "menunggu_model", "merekam" -> if (dark) Color(0xFFFFB45E) else WarnAmber
        "ditangani", "aman", "selesai" -> if (dark) Color(0xFF63D091) else OkGreen
        else -> if (dark) Color(0xFFB8B0A8) else Color(0xFF6B756E)
    }
}

fun statusLabel(status: String): String = when (status) {
    "aktif" -> "Aktif"
    "diterima" -> "Diterima satpam"
    "ditangani" -> "Ditangani"
    "aman" -> "Aman"
    "dibatalkan" -> "Dibatalkan"
    "menunggu_kirim" -> "Menunggu kirim"
    "baru" -> "Terkirim"
    "diproses" -> "Diproses"
    "selesai" -> "Selesai"
    "ditolak" -> "Ditolak"
    "merekam" -> "Merekam"
    "memproses" -> "Mentranskrip"
    "menunggu_model" -> "Menunggu model"
    "gagal" -> "Gagal"
    else -> status
}

/**
 * Warna ikon status bar/navigasi dan latar jendela mengikuti tema Everest (bukan tema sistem),
 * supaya tidak ada ikon putih di atas latar putih saat pengguna memilih Terang di HP mode gelap.
 */
@Composable
fun EverestSystemBars(activity: androidx.activity.ComponentActivity) {
    val dark = LocalEverestDark.current
    val bg = MaterialTheme.colorScheme.background
    androidx.compose.runtime.LaunchedEffect(dark) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) androidx.activity.SystemBarStyle.dark(transparent) else androidx.activity.SystemBarStyle.light(transparent, transparent)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg.toArgb()))
    }
}

