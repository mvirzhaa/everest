package id.ac.uika.everest.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import id.ac.uika.everest.BuildConfig
import id.ac.uika.everest.data.remote.UserDto
import id.ac.uika.everest.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Pengaturan dan status kecil aplikasi. Data besar ada di Room. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("everest", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _user = MutableStateFlow(readUser())
    val userFlow: StateFlow<UserDto?> = _user

    /** Status widget/servis yang perlu diamati UI. */
    private val _recording = MutableStateFlow(sp.getString(K_REC_NOTE, null))
    val recordingFlow: StateFlow<String?> = _recording
    private val _tracking = MutableStateFlow(sp.getString(K_TRACK_SESSION, null))
    val trackingFlow: StateFlow<String?> = _tracking
    private val _siren = MutableStateFlow(sp.getBoolean(K_SIREN, false))
    val sirenFlow: StateFlow<Boolean> = _siren

    private val _theme = MutableStateFlow(ThemeMode.of(sp.getString(K_THEME, null)))
    val themeFlow: StateFlow<ThemeMode> = _theme

    /** Tema aplikasi (Terang/Gelap/Ikuti sistem). Pengaturan perangkat: tidak ikut terhapus saat keluar. */
    var themeMode: ThemeMode
        get() = _theme.value
        set(v) {
            sp.edit { putString(K_THEME, v.key) }
            _theme.value = v
        }

    /** Versi kebijakan privasi yang sudah disetujui di HP ini (null = belum). */
    var consentVersion: String?
        get() = sp.getString(K_CONSENT_VER, null)
        set(v) = sp.edit(commit = true) { putString(K_CONSENT_VER, v) }

    /** Waktu persetujuan (ms epoch) dan cakupan yang dipilih, dikirim ke server lewat antrean. */
    var consentAt: Long
        get() = sp.getLong(K_CONSENT_AT, 0L)
        set(v) = sp.edit(commit = true) { putLong(K_CONSENT_AT, v) }

    var consentScopes: Set<String>
        get() = sp.getStringSet(K_CONSENT_SCOPES, emptySet()) ?: emptySet()
        set(v) = sp.edit(commit = true) { putStringSet(K_CONSENT_SCOPES, v) }

    var sirenOn: Boolean
        get() = _siren.value
        set(v) {
            sp.edit(commit = true) { putBoolean(K_SIREN, v) }
            _siren.value = v
        }

    /** Ukuran teks Arab di menu Doa (sp). */
    var arabicSize: Int
        get() = sp.getInt(K_AR_SIZE, 28)
        set(v) = sp.edit { putInt(K_AR_SIZE, v) }

    var showLatin: Boolean
        get() = sp.getBoolean(K_SHOW_LATIN, true)
        set(v) = sp.edit { putBoolean(K_SHOW_LATIN, v) }

    var showMeaning: Boolean
        get() = sp.getBoolean(K_SHOW_MEANING, true)
        set(v) = sp.edit { putBoolean(K_SHOW_MEANING, v) }

    var serverUrl: String
        get() = sp.getString(K_SERVER, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.SERVER_URL
        set(v) = sp.edit { putString(K_SERVER, v.trim().trimEnd('/')) }

    var token: String?
        get() = sp.getString(K_TOKEN, null)
        set(v) = sp.edit { putString(K_TOKEN, v) }

    var user: UserDto?
        get() = _user.value
        set(v) {
            sp.edit { putString(K_USER, v?.let { json.encodeToString(it) }) }
            _user.value = v
        }

    private fun readUser(): UserDto? = sp.getString(K_USER, null)?.let { runCatching { json.decodeFromString<UserDto>(it) }.getOrNull() }

    val isLoggedIn: Boolean get() = token != null && user != null

    var notesSince: String?
        get() = sp.getString(K_NOTES_SINCE, null)
        set(v) = sp.edit { putString(K_NOTES_SINCE, v) }

    var modelName: String?
        get() = sp.getString(K_MODEL, null)
        set(v) = sp.edit { putString(K_MODEL, v) }

    /** Rekaman panjang (> 30 menit) baru ditranskrip saat HP dicas. */
    var processLongOnCharge: Boolean
        get() = sp.getBoolean(K_ON_CHARGE, true)
        set(v) = sp.edit { putBoolean(K_ON_CHARGE, v) }

    /** File besar (audio, foto) hanya dikirim lewat Wi-Fi. */
    var largeFilesWifiOnly: Boolean
        get() = sp.getBoolean(K_WIFI_ONLY, false)
        set(v) = sp.edit { putBoolean(K_WIFI_ONLY, v) }

    var uploadAudio: Boolean
        get() = sp.getBoolean(K_UPLOAD_AUDIO, false)
        set(v) = sp.edit { putBoolean(K_UPLOAD_AUDIO, v) }

    var farhanCloud: Boolean
        get() = sp.getBoolean(K_FARHAN_CLOUD, true)
        set(v) = sp.edit { putBoolean(K_FARHAN_CLOUD, v) }

    var lastTrackRecipients: Set<String>
        get() = sp.getStringSet(K_TRACK_RCP, emptySet()) ?: emptySet()
        set(v) = sp.edit { putStringSet(K_TRACK_RCP, v) }

    var recordingNoteId: String?
        get() = _recording.value
        set(v) {
            sp.edit(commit = true) { putString(K_REC_NOTE, v) }
            _recording.value = v
        }

    var trackSessionId: String?
        get() = _tracking.value
        set(v) {
            sp.edit(commit = true) { putString(K_TRACK_SESSION, v) }
            _tracking.value = v
        }

    var activePanicId: String?
        get() = sp.getString(K_PANIC, null)
        set(v) = sp.edit(commit = true) { putString(K_PANIC, v) }

    var fcmToken: String?
        get() = sp.getString(K_FCM, null)
        set(v) = sp.edit { putString(K_FCM, v) }

    var onboardingDone: Boolean
        get() = sp.getBoolean(K_ONBOARD, false)
        set(v) = sp.edit { putBoolean(K_ONBOARD, v) }

    var recordConsentShown: Boolean
        get() = sp.getBoolean(K_CONSENT, false)
        set(v) = sp.edit { putBoolean(K_CONSENT, v) }

    var dbKeyEnc: String?
        get() = sp.getString(K_DBKEY, null)
        set(v) = sp.edit(commit = true) { putString(K_DBKEY, v) }

    fun signOut() {
        // Kunci DB dipertahankan: data lokal dihapus terpisah oleh AuthManager
        val dbKey = dbKeyEnc
        val server = sp.getString(K_SERVER, null)
        val theme = sp.getString(K_THEME, null)
        sp.edit(commit = true) { clear() }
        dbKeyEnc = dbKey
        if (server != null) serverUrl = server
        if (theme != null) sp.edit { putString(K_THEME, theme) }
        _user.value = null
        _recording.value = null
        _tracking.value = null
        _siren.value = false
    }

    private companion object {
        const val K_SERVER = "server_url"
        const val K_TOKEN = "token"
        const val K_USER = "user"
        const val K_NOTES_SINCE = "notes_since"
        const val K_MODEL = "whisper_model"
        const val K_ON_CHARGE = "process_on_charge"
        const val K_WIFI_ONLY = "wifi_only_large"
        const val K_UPLOAD_AUDIO = "upload_audio"
        const val K_FARHAN_CLOUD = "farhan_cloud"
        const val K_TRACK_RCP = "track_recipients"
        const val K_REC_NOTE = "recording_note"
        const val K_TRACK_SESSION = "track_session"
        const val K_PANIC = "active_panic"
        const val K_FCM = "fcm_token"
        const val K_ONBOARD = "onboarding_done"
        const val K_CONSENT = "record_consent"
        const val K_DBKEY = "db_key"
        const val K_SIREN = "siren_on"
        const val K_AR_SIZE = "arabic_size"
        const val K_SHOW_LATIN = "show_latin"
        const val K_SHOW_MEANING = "show_meaning"
        const val K_THEME = "theme_mode"
        const val K_CONSENT_VER = "privacy_consent_version"
        const val K_CONSENT_AT = "privacy_consent_at"
        const val K_CONSENT_SCOPES = "privacy_consent_scopes"
    }
}
