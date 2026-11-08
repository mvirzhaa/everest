package id.ac.uika.everest.farhan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class IntentParserTest {
    private val zone = ZoneId.of("Asia/Jakarta")
    // Jumat, 6 November 2026 pukul 15.42 WIB
    private val now = ZonedDateTime.of(2026, 11, 6, 15, 42, 0, 0, zone)
    private val p = IntentParser { now }
    private fun at(d: Int, h: Int, m: Int = 0, month: Int = 11) = ZonedDateTime.of(2026, month, d, h, m, 0, 0, zone)

    @Test fun jadwalBesokDenganLokasi() {
        val i = p.parse("Jadwalkan rapat prodi besok jam 9 di ruang 204") as FarhanIntent.CreateEvent
        assertEquals("Rapat prodi", i.title)
        assertEquals(at(7, 9), i.start)
        assertEquals("Ruang 204", i.location)
        assertNull(i.end)
    }

    @Test fun jadwalHariSeninSiangSampaiJam3() {
        val i = p.parse("tolong masukkan ke kalender kuliah umum hari senin jam 1 siang sampai jam 3 di aula") as FarhanIntent.CreateEvent
        assertEquals("Kuliah umum", i.title)
        assertEquals(at(9, 13), i.start)
        assertEquals(at(9, 15), i.end)
        assertEquals("Aula", i.location)
    }

    @Test fun jadwalJamYangSudahLewatPindahBesok() {
        val i = p.parse("jadwalkan bimbingan skripsi jam 2") as FarhanIntent.CreateEvent
        assertEquals("Bimbingan skripsi", i.title)
        assertEquals(at(7, 14), i.start)
    }

    @Test fun jadwalDenganDurasi() {
        val i = p.parse("buat jadwal seminar tanggal 20 november pukul 08.30 selama 2 jam") as FarhanIntent.CreateEvent
        assertEquals("Seminar", i.title)
        assertEquals(at(20, 8, 30), i.start)
        assertEquals(at(20, 10, 30), i.end)
    }

    @Test fun jadwalTanpaWaktuDiserahkanKeCloud() {
        assertTrue(p.parse("jadwalkan rapat") is FarhanIntent.Ask)
    }

    @Test fun rekamanPadaTanggal() {
        val i = p.parse("Rekaman tanggal 3 November ada apa saja?") as FarhanIntent.SearchRecordings
        assertEquals(at(3, 0), i.range!!.start)
        assertEquals(at(4, 0), i.range!!.end)
        assertNull(i.keywords)
    }

    @Test fun rekamanBerdasarkanKataKunci() {
        val i = p.parse("cari rekaman yang membahas anggaran laboratorium") as FarhanIntent.SearchRecordings
        assertNull(i.range)
        assertEquals("anggaran laboratorium", i.keywords)
    }

    @Test fun rekamanMingguKemarinDenganTopik() {
        val i = p.parse("rekaman minggu kemarin tentang tugas besar") as FarhanIntent.SearchRecordings
        assertEquals(at(26, 0, month = 10), i.range!!.start)
        assertEquals(at(2, 0), i.range!!.end)
        assertEquals("tugas besar", i.keywords)
    }

    @Test fun rekamanHariSeninAdalahSeninTerakhir() {
        val i = p.parse("rekaman hari senin ada apa saja") as FarhanIntent.SearchRecordings
        assertEquals(at(2, 0), i.range!!.start)
    }

    @Test fun riwayatLokasiKemarinSore() {
        val i = p.parse("kemarin sore saya di mana saja") as FarhanIntent.LocationHistory
        assertEquals(at(5, 15), i.range.start)
        assertEquals(at(5, 18), i.range.end)
    }

    @Test fun lihatJadwalMingguDepan() {
        val i = p.parse("jadwal saya minggu depan apa saja") as FarhanIntent.ListEvents
        assertEquals(at(9, 0), i.range.start)
        assertEquals(at(16, 0), i.range.end)
    }

    @Test fun ringkasRapatTadiPagi() {
        val i = p.parse("ringkas rapat tadi pagi") as FarhanIntent.Summarize
        assertEquals(at(6, 4), i.range!!.start)
        assertEquals(at(6, 11), i.range!!.end)
        assertEquals("rapat", i.keywords)
    }

    @Test fun pertanyaanPenalaranKeCloud() {
        assertTrue(p.parse("apa keputusan rapat akreditasi bulan lalu") is FarhanIntent.Ask)
    }

    @Test fun perintahRekam() {
        assertEquals(FarhanIntent.StartRecording, p.parse("mulai rekam"))
        assertEquals(FarhanIntent.StopRecording, p.parse("berhenti merekam"))
    }

    @Test fun doaDanAyat() {
        assertEquals(FarhanIntent.OpenPrayer("Farhan, bacakan doa naik motor"), p.parse("Farhan, bacakan doa naik motor"))
        assertEquals(FarhanIntent.OpenPrayer("ayat kursi"), p.parse("ayat kursi"))
        assertTrue(p.parse("tampilkan ayat ruqyah") is FarhanIntent.OpenPrayer)
    }

    @Test fun doaTidakMenggangguJadwalDanRekaman() {
        assertTrue(p.parse("jadwalkan doa bersama besok jam 7 di masjid") is FarhanIntent.CreateEvent)
        assertTrue(p.parse("cari rekaman doa bersama minggu lalu") is FarhanIntent.SearchRecordings)
    }

    @Test fun sirine() {
        assertEquals(FarhanIntent.StartSiren, p.parse("nyalakan sirine"))
        assertEquals(FarhanIntent.StopSiren, p.parse("matikan sirine!"))
    }
}
