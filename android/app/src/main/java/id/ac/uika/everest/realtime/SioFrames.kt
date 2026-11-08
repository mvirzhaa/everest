package id.ac.uika.everest.realtime

/**
 * Bingkai protokol Socket.IO v4 (Engine.IO v4) di atas WebSocket, cukup untuk klien Everest:
 *   "0{...}"          open (berisi pingInterval/pingTimeout)
 *   "2" / "3"         ping dari server / pong dari klien
 *   "40{...}"         CONNECT ke namespace "/" (klien kirim auth, server balas sid)
 *   "44{...}"         CONNECT_ERROR
 *   "42[\"nama\",x]"  EVENT
 *   "41"              DISCONNECT
 * Murni Kotlin (tanpa Android/OkHttp) agar bisa diuji unit.
 */
object SioFrames {

    sealed interface Frame {
        data class Open(val pingIntervalMs: Long, val pingTimeoutMs: Long) : Frame
        data object Ping : Frame
        data object Connected : Frame
        data class ConnectError(val message: String) : Frame
        /** [payloadJson] = argumen pertama event sebagai teks JSON ("null" bila tidak ada). */
        data class Event(val name: String, val payloadJson: String) : Frame
        data object Disconnected : Frame
        data object Close : Frame
        data class Unknown(val raw: String) : Frame
    }

    const val PONG = "3"

    fun connect(authJson: String) = "40$authJson"

    fun event(name: String, argJson: String) = "42[${quote(name)},$argJson]"

    fun parse(raw: String): Frame {
        if (raw.isEmpty()) return Frame.Unknown(raw)
        return when (raw[0]) {
            '0' -> Frame.Open(numberField(raw, "pingInterval") ?: 25_000, numberField(raw, "pingTimeout") ?: 20_000)
            '1' -> Frame.Close
            '2' -> Frame.Ping
            '4' -> parseSocket(raw.substring(1))
            else -> Frame.Unknown(raw)
        }
    }

    private fun parseSocket(p: String): Frame {
        if (p.isEmpty()) return Frame.Unknown("4")
        // Hanya namespace "/" yang dipakai; namespace lain diawali "/" sebelum data
        if (p.length > 1 && p[1] == '/') return Frame.Unknown("4$p")
        return when (p[0]) {
            '0' -> Frame.Connected
            '1' -> Frame.Disconnected
            '4' -> Frame.ConnectError(stringField(p.substring(1), "message") ?: "Ditolak server")
            '2' -> parseEvent(p.substring(1).dropWhile { it.isDigit() }) // abaikan id ack bila ada
            else -> Frame.Unknown("4$p")
        }
    }

    private fun parseEvent(arr: String): Frame {
        val s = arr.trim()
        if (!s.startsWith("[")) return Frame.Unknown("42$arr")
        var i = 1
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length || s[i] != '"') return Frame.Unknown("42$arr")
        val (name, end) = readString(s, i) ?: return Frame.Unknown("42$arr")
        i = end
        while (i < s.length && s[i].isWhitespace()) i++
        if (i < s.length && s[i] == ']') return Frame.Event(name, "null")
        if (i >= s.length || s[i] != ',') return Frame.Unknown("42$arr")
        // Argumen pertama: ambil sampai koma tingkat-atas atau kurung tutup terakhir
        val start = i + 1
        var depth = 0
        var j = start
        var inStr = false
        while (j < s.length) {
            val c = s[j]
            if (inStr) {
                if (c == '\\') j++ else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '[', '{' -> depth++
                    ']', '}' -> {
                        if (depth == 0) break
                        depth--
                    }
                    ',' -> if (depth == 0) break
                }
            }
            j++
        }
        return Frame.Event(name, s.substring(start, j).trim().ifEmpty { "null" })
    }

    /** Baca string JSON yang dimulai di [from] (karakter '"'). @return (nilai, indeks setelah kutip tutup). */
    private fun readString(s: String, from: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = from + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> return sb.toString() to i + 1
                c == '\\' && i + 1 < s.length -> {
                    when (val e = s[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (i + 5 >= s.length) return null
                            sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(e)
                    }
                    i += 2
                    continue
                }
                else -> sb.append(c)
            }
            i++
        }
        return null
    }

    private fun numberField(s: String, key: String): Long? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*(\\d+)").find(s)?.groupValues?.get(1)?.toLongOrNull()

    private fun stringField(s: String, key: String): String? {
        val m = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"").find(s) ?: return null
        return readString(s, m.range.last)?.first
    }

    fun quote(v: String): String = buildString {
        append('"')
        for (c in v) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /** "https://host/x" → "wss://host/socket.io/?EIO=4&transport=websocket" (path dasar server ikut dipertahankan). */
    fun socketUrl(serverUrl: String): String {
        val base = serverUrl.trim().trimEnd('/')
        val ws = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return "$ws/socket.io/?EIO=4&transport=websocket"
    }
}
