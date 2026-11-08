package id.ac.uika.everest.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTextTest {
    @Test fun hanya_catatan_selesai_atau_gagal_yang_bisa_diedit() {
        assertTrue(TranscriptText.editable("selesai"))
        assertTrue(TranscriptText.editable("gagal"))
        assertFalse(TranscriptText.editable("memproses"))
        assertFalse(TranscriptText.editable("merekam"))
    }

    @Test fun spasi_dirapikan_dan_kosong_berarti_hapus() {
        assertEquals("rapat jam dua", TranscriptText.clean("  rapat \n jam\tdua  "))
        assertEquals("", TranscriptText.clean(" \n "))
    }

    @Test fun ringkasan_otomatis_ikut_diperbarui() {
        val old = "rapat jam dua dengan dekan"
        assertEquals("rapat jam tiga dengan dekan", TranscriptText.nextSummary(old, old, "rapat jam tiga dengan dekan"))
        assertEquals("teks baru", TranscriptText.nextSummary(null, "x", "teks baru"))
        val long = "a".repeat(400)
        assertEquals("b".repeat(280), TranscriptText.nextSummary(long.take(280), long, "b".repeat(400)))
    }

    @Test fun ringkasan_buatan_pengguna_tidak_ditimpa() {
        assertEquals("Ringkasan Farhan", TranscriptText.nextSummary("Ringkasan Farhan", "lama", "baru"))
    }

    @Test fun transkrip_kosong_tanpa_ringkasan() {
        assertEquals(null, TranscriptText.nextSummary("lama", "lama", ""))
    }
}
