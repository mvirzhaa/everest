package id.ac.uika.everest.siren

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SirenToneTest {
    @Test fun frekuensiNaikTurunDalamRentang() {
        val t = SirenTone(lowHz = 650.0, highHz = 1550.0, cycleSec = 1.6)
        assertEquals(650.0, t.frequencyAt(0.0), 1e-6)
        assertEquals(1550.0, t.frequencyAt(0.8), 1e-6) // puncak di tengah siklus
        assertEquals(650.0, t.frequencyAt(1.6), 1e-6) // kembali ke awal
        for (i in 0..320) {
            val f = t.frequencyAt(i / 100.0)
            assertTrue("f=$f", f in 650.0..1550.0)
        }
    }

    @Test fun sampelKerasTanpaClippingDanBerkesinambungan() {
        val rate = 44_100
        val t = SirenTone(rate)
        val a = ShortArray(rate / 2)
        val b = ShortArray(rate / 2)
        t.fill(a)
        t.fill(b)
        val peak = (a + b).maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("terlalu pelan: $peak", peak > 20_000)
        // Tidak ada lompatan besar di sambungan antar-buffer (bunyi tidak "klik")
        assertTrue(kotlin.math.abs(b[0] - a.last()) < 12_000)
    }

    @Test fun frekuensiTerdengarSesuaiDariSampel() {
        val rate = 44_100
        val t = SirenTone(rate, lowHz = 1000.0, highHz = 1000.0)
        val s = ShortArray(rate) // 1 detik pada 1000 Hz
        t.fill(s)
        var crossings = 0
        for (i in 1 until s.size) if (s[i - 1] < 0 && s[i] >= 0) crossings++
        assertTrue("persilangan=$crossings", crossings in 990..1010)
    }
}
