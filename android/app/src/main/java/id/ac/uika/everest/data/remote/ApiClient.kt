package id.ac.uika.everest.data.remote

import id.ac.uika.everest.data.Prefs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Klien API Everest. URL server bisa diubah di Profil, jadi Retrofit dibuat ulang saat URL berubah. */
class ApiClient(private val prefs: Prefs) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Dipancarkan saat server menolak token (401) → UI kembali ke layar login. */
    val unauthorized: SharedFlow<Unit> = _unauthorized

    private val authInterceptor = Interceptor { chain ->
        val token = prefs.token
        val req = chain.request().newBuilder().apply {
            if (token != null) header("Authorization", "Bearer $token")
            header("Accept", "application/json")
        }.build()
        val res = chain.proceed(req)
        if (res.code == 401 && token != null) _unauthorized.tryEmit(Unit)
        res
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .build()

    @Volatile private var cachedUrl: String? = null
    @Volatile private var cachedApi: EverestApi? = null

    val api: EverestApi
        get() {
            val url = prefs.serverUrl.trimEnd('/') + "/"
            cachedApi?.let { if (cachedUrl == url) return it }
            synchronized(this) {
                val built = Retrofit.Builder()
                    .baseUrl(url)
                    .client(http)
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .build()
                    .create(EverestApi::class.java)
                cachedUrl = url
                cachedApi = built
                return built
            }
        }

    /** Pesan error dari server ({"error": "..."}) dalam bahasa manusia. */
    fun errorMessage(t: Throwable): String = when (t) {
        is HttpException -> runCatching {
            t.response()?.errorBody()?.string()?.let { json.decodeFromString(ErrorDto.serializer(), it).error }
        }.getOrNull() ?: "Server menolak permintaan (${t.code()})"
        is IOException -> "Tidak ada koneksi ke server"
        else -> t.message ?: "Terjadi kesalahan"
    }
}

/** Kategori kegagalan untuk antrean offline. */
enum class Failure { RETRY, DROP, UNAUTHORIZED }

fun classify(t: Throwable): Failure = when (t) {
    is IOException -> Failure.RETRY
    is HttpException -> when (t.code()) {
        401 -> Failure.UNAUTHORIZED
        404, 408, 409, 425, 429 -> Failure.RETRY
        in 400..499 -> Failure.DROP
        else -> Failure.RETRY
    }
    else -> Failure.RETRY
}
