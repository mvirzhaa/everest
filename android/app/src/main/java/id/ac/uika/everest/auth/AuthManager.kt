package id.ac.uika.everest.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import id.ac.uika.everest.BuildConfig
import id.ac.uika.everest.Locator
import id.ac.uika.everest.data.remote.DevLoginReq
import id.ac.uika.everest.data.remote.GoogleLinkReq
import id.ac.uika.everest.data.remote.LoginRes
import id.ac.uika.everest.data.remote.OidcLoginReq
import id.ac.uika.everest.data.remote.UserDto
import id.ac.uika.everest.record.RecordingService
import id.ac.uika.everest.sync.OutKind
import id.ac.uika.everest.sync.SyncScheduler
import id.ac.uika.everest.track.TrackingService
import id.ac.uika.everest.widget.WidgetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import net.openid.appauth.AuthorizationRequest as OidcRequest

/** Login SSO E-Portal (OpenID Connect via AppAuth), login dev, dan tautan Google Calendar. */
object AuthManager {
    private val redirectUri: Uri = Uri.parse("id.ac.uika.everest:/oauth2redirect")

    val ssoConfigured: Boolean get() = BuildConfig.OIDC_ISSUER.isNotBlank() && BuildConfig.OIDC_CLIENT_ID.isNotBlank()

    private suspend fun discovery(): AuthorizationServiceConfiguration = suspendCancellableCoroutine { cont ->
        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(BuildConfig.OIDC_ISSUER)) { cfg, ex ->
            if (cfg != null) cont.resume(cfg) else cont.resumeWithException(ex ?: IllegalStateException("Konfigurasi SSO tidak terbaca"))
        }
    }

    /** Intent browser untuk halaman login SSO. */
    suspend fun ssoIntent(context: Context): Intent {
        val cfg = discovery()
        val req = OidcRequest.Builder(cfg, BuildConfig.OIDC_CLIENT_ID, ResponseTypeValues.CODE, redirectUri)
            .setScopes("openid", "profile", "email")
            .build()
        val service = AuthorizationService(context)
        return try {
            service.getAuthorizationRequestIntent(req)
        } finally {
            service.dispose()
        }
    }

    /** Selesaikan login SSO: tukar code → id_token → token sesi Everest. */
    suspend fun completeSso(context: Context, data: Intent): UserDto {
        val resp = AuthorizationResponse.fromIntent(data)
            ?: throw AuthorizationException.fromIntent(data) ?: IllegalStateException("Login dibatalkan")
        val service = AuthorizationService(context)
        val idToken = try {
            suspendCancellableCoroutine { cont ->
                service.performTokenRequest(resp.createTokenExchangeRequest()) { tok, ex ->
                    val id = tok?.idToken
                    if (id != null) cont.resume(id) else cont.resumeWithException(ex ?: IllegalStateException("SSO tidak mengirim id_token"))
                }
            }
        } finally {
            service.dispose()
        }
        return finish(Locator.api.loginOidc(OidcLoginReq(idToken)))
    }

    suspend fun devLogin(email: String): UserDto = finish(Locator.api.loginDev(DevLoginReq(email.trim().lowercase())))

    private suspend fun finish(res: LoginRes): UserDto {
        val p = Locator.prefs
        p.token = res.token
        p.user = res.user
        Locator.outbox.enqueue(OutKind.DEVICE, "fcm")
        // Persetujuan privasi diberikan di layar sebelum login: catat ke server atas nama akun ini
        if (Locator.prefs.consentVersion != null) Locator.outbox.enqueue(OutKind.CONSENT, "privasi")
        SyncScheduler.schedulePeriodic(Locator.app)
        runCatching { Repo.refreshContacts() }
        return res.user
    }

    suspend fun refreshMe(): UserDto? = runCatching { Locator.api.me().also { Locator.prefs.user = it } }.getOrNull()

    /** Keluar: hentikan servis, hapus data lokal (rekaman, lokasi, laporan) dari HP ini. */
    suspend fun signOut(context: Context) {
        RecordingService.stop(context)
        TrackingService.stop(context)
        id.ac.uika.everest.siren.SirenService.stop(context)
        WorkManager.getInstance(context).cancelAllWork()
        withContext(Dispatchers.IO) {
            Locator.db.clearAllTables()
            context.filesDir.resolve("notes").deleteRecursively()
            context.filesDir.resolve("reports").deleteRecursively()
        }
        Locator.prefs.signOut()
        WidgetState.refresh(context)
    }

    // ---------- Google Calendar ----------
    private val calendarScope = Scope("https://www.googleapis.com/auth/calendar.events")

    val calendarConfigured: Boolean get() = BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotBlank()

    /** Langkah 1: minta izin. Jika perlu persetujuan pengguna, kembalikan PendingIntent untuk diluncurkan. */
    suspend fun requestCalendar(activity: Activity): AuthorizationResult {
        val req = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(calendarScope))
            .requestOfflineAccess(BuildConfig.GOOGLE_SERVER_CLIENT_ID, true)
            .build()
        return Identity.getAuthorizationClient(activity).authorize(req).await()
    }

    /** Langkah 2: kirim server auth code ke backend (yang menyimpan refresh token terenkripsi). */
    suspend fun linkCalendar(result: AuthorizationResult) {
        val code = result.serverAuthCode ?: throw IllegalStateException("Google tidak mengirim kode otorisasi")
        Locator.api.linkGoogle(GoogleLinkReq(code))
        refreshMe()
    }

    fun calendarResultFromIntent(activity: Activity, data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
}
