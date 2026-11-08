package id.ac.uika.everest.realtime

import android.util.Log
import id.ac.uika.everest.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Koneksi real-time ke server Everest (Socket.IO v4 lewat WebSocket) untuk layar Petugas.
 * Hanya tersambung selama ada layar yang memakainya (acquire/release), supaya tidak boros baterai;
 * notifikasi saat aplikasi tertutup tetap lewat FCM.
 */
class Realtime(private val prefs: Prefs, baseClient: OkHttpClient) {

    enum class State { OFF, CONNECTING, ONLINE, RETRYING }

    data class Event(val name: String, val data: JsonObject)

    private val client = baseClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // diatur sendiri lewat ping Engine.IO
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events

    private var users = 0
    private var socket: WebSocket? = null
    private var loop: Job? = null
    private var watchdog: Job? = null
    @Volatile private var lastPing = 0L
    @Volatile private var pingWindowMs = 45_000L
    private val watched = mutableSetOf<String>()

    @Synchronized
    fun acquire() {
        users++
        if (users == 1) start()
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    /** Ikuti titik lokasi sesi panic tertentu (event "points"). Diulang otomatis setelah sambung ulang. */
    @Synchronized
    fun watch(sessionId: String) {
        // Sebelum CONNECT diterima, cukup dicatat: dikirim begitu tersambung
        if (watched.add(sessionId) && _state.value == State.ONLINE) socket?.send(SioFrames.event("watch", SioFrames.quote(sessionId)))
    }

    private fun start() {
        loop?.cancel()
        loop = scope.launch {
            var attempt = 0
            while (true) {
                val token = prefs.token
                if (token == null) {
                    _state.value = State.OFF
                    return@launch
                }
                _state.value = if (attempt == 0) State.CONNECTING else State.RETRYING
                val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
                val ws = client.newWebSocket(
                    Request.Builder().url(SioFrames.socketUrl(prefs.serverUrl)).build(),
                    Listener(token, onOnline = { attempt = 0 }, onClosed = { closed.complete(Unit) }),
                )
                synchronized(this@Realtime) { socket = ws }
                closed.await()
                synchronized(this@Realtime) { socket = null }
                watchdog?.cancel()
                attempt++
                _state.value = State.RETRYING
                // 1 s, 2 s, 4 s … maks 30 s
                delay(min(30_000L, 1_000L shl min(attempt - 1, 5)))
            }
        }
    }

    private fun stop() {
        loop?.cancel()
        loop = null
        watchdog?.cancel()
        socket?.close(1000, null)
        socket = null
        watched.clear()
        _state.value = State.OFF
    }

    private inner class Listener(
        private val token: String,
        private val onOnline: () -> Unit,
        private val onClosed: () -> Unit,
    ) : WebSocketListener() {
        @Volatile private var done = false

        @Synchronized
        private fun finish(ws: WebSocket) {
            if (done) return
            done = true
            ws.cancel()
            onClosed()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val f = SioFrames.parse(text)) {
                is SioFrames.Frame.Open -> {
                    pingWindowMs = f.pingIntervalMs + f.pingTimeoutMs
                    lastPing = System.currentTimeMillis()
                    webSocket.send(SioFrames.connect("{\"token\":${SioFrames.quote(token)}}"))
                    startWatchdog(webSocket)
                }
                SioFrames.Frame.Ping -> {
                    lastPing = System.currentTimeMillis()
                    webSocket.send(SioFrames.PONG)
                }
                SioFrames.Frame.Connected -> {
                    _state.value = State.ONLINE
                    onOnline()
                    synchronized(this@Realtime) { watched.forEach { webSocket.send(SioFrames.event("watch", SioFrames.quote(it))) } }
                    _events.tryEmit(Event(CONNECTED, JsonObject(emptyMap())))
                }
                is SioFrames.Frame.ConnectError -> {
                    Log.w(TAG, "ditolak: ${f.message}")
                    finish(webSocket)
                }
                is SioFrames.Frame.Event -> {
                    val data = runCatching { json.parseToJsonElement(f.payloadJson).jsonObject }.getOrElse { JsonObject(emptyMap()) }
                    _events.tryEmit(Event(f.name, data))
                }
                SioFrames.Frame.Disconnected, SioFrames.Frame.Close -> finish(webSocket)
                is SioFrames.Frame.Unknown -> Unit
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = finish(webSocket)
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(webSocket)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.d(TAG, "putus: ${t.message}")
            finish(webSocket)
        }

        /** Server Engine.IO v4 mengirim ping berkala; bila lewat batas, anggap koneksi mati dan sambung ulang. */
        private fun startWatchdog(ws: WebSocket) {
            watchdog?.cancel()
            watchdog = scope.launch {
                while (!done) {
                    delay(5_000)
                    if (System.currentTimeMillis() - lastPing > pingWindowMs) {
                        Log.d(TAG, "tidak ada ping dari server, sambung ulang")
                        finish(ws)
                    }
                }
            }
        }
    }

    companion object {
        /** Event lokal: baru tersambung (layar sebaiknya memuat ulang data yang mungkin terlewat). */
        const val CONNECTED = "_connected"
        private const val TAG = "EverestRealtime"
    }
}
