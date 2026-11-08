package id.ac.uika.everest.realtime

import id.ac.uika.everest.realtime.SioFrames.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SioFramesTest {
    @Test fun open_membaca_interval_ping() {
        val f = SioFrames.parse("""0{"sid":"abc","upgrades":[],"pingInterval":25000,"pingTimeout":20000,"maxPayload":1000000}""")
        assertEquals(Frame.Open(25_000, 20_000), f)
    }

    @Test fun ping_connect_dan_disconnect() {
        assertEquals(Frame.Ping, SioFrames.parse("2"))
        assertEquals(Frame.Connected, SioFrames.parse("""40{"sid":"x"}"""))
        assertEquals(Frame.Disconnected, SioFrames.parse("41"))
        assertEquals(Frame.Close, SioFrames.parse("1"))
    }

    @Test fun connect_error_membawa_pesan() {
        assertEquals(Frame.ConnectError("unauthorized"), SioFrames.parse("""44{"message":"unauthorized"}"""))
    }

    @Test fun event_dengan_objek_bersarang_dan_string_berisi_kurung() {
        val f = SioFrames.parse("""42["panic",{"user_name":"Ana \"A\" [x]","points":[{"lat":-6.5}],"n":{"a":1}}]""") as Frame.Event
        assertEquals("panic", f.name)
        assertEquals("""{"user_name":"Ana \"A\" [x]","points":[{"lat":-6.5}],"n":{"a":1}}""", f.payloadJson)
    }

    @Test fun event_tanpa_argumen_dan_dengan_id_ack() {
        assertEquals(Frame.Event("ping_me", "null"), SioFrames.parse("""42["ping_me"]"""))
        assertEquals("watch", (SioFrames.parse("""4212["watch","s1"]""") as Frame.Event).name)
    }

    @Test fun event_namespace_lain_diabaikan() {
        assertTrue(SioFrames.parse("""42/admin,["x",{}]""") is Frame.Unknown)
    }

    @Test fun nama_event_dengan_escape_unicode() {
        assertEquals("oké", (SioFrames.parse("""42["oké",1]""") as Frame.Event).name)
    }

    @Test fun encode_connect_dan_event() {
        assertEquals("""40{"token":"a\"b"}""", SioFrames.connect("{\"token\":${SioFrames.quote("a\"b")}}"))
        assertEquals("""42["watch","id-1"]""", SioFrames.event("watch", SioFrames.quote("id-1")))
        assertEquals("\"baris\\nbaru\\\\\"", SioFrames.quote("baris\nbaru\\"))
    }

    @Test fun url_socket_dari_url_server() {
        assertEquals("wss://everest.uika-bogor.ac.id/socket.io/?EIO=4&transport=websocket", SioFrames.socketUrl("https://everest.uika-bogor.ac.id/"))
        assertEquals("ws://10.0.2.2:8080/socket.io/?EIO=4&transport=websocket", SioFrames.socketUrl("http://10.0.2.2:8080"))
    }
}
