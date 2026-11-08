package id.ac.uika.everest.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordsTest {
    @Test fun kataUmumDibuangDanDiurutkanFrekuensi() {
        val text = "Jadi untuk akreditasi prodi kita perlu borang. Borang akreditasi harus lengkap, ya, borang dikumpulkan Senin. Prodi juga."
        val k = Keywords.extract(text)
        assertEquals(listOf("borang", "akreditasi", "prodi"), k)
    }

    @Test fun kataSekaliMunculTidakJadiKataKunci() {
        assertTrue(Keywords.extract("Laboratorium jaringan baru dibuka hari ini").isEmpty())
    }

    @Test fun queryFtsAmanDenganAwalan() {
        assertEquals("anggaran* laboratorium*", Keywords.ftsQuery("Yang tentang \"anggaran\" laboratorium?"))
        assertNull(Keywords.ftsQuery("yang di dan"))
    }
}
