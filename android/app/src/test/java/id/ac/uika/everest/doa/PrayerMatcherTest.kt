package id.ac.uika.everest.doa

import org.junit.Assert.assertEquals
import org.junit.Test

class PrayerMatcherTest {
    private val items = listOf(
        "keluar-rumah" to "Doa keluar rumah", "keluar-rumah-2" to "Doa agar tidak tersesat dan tidak dizalimi",
        "naik-kendaraan" to "Doa naik kendaraan", "safar" to "Doa safar (bepergian jauh)", "naik-kapal" to "Doa naik kapal atau perahu",
        "singgah" to "Doa singgah di suatu tempat", "masuk-kota" to "Doa memasuki kota atau desa", "pulang" to "Doa pulang dari perjalanan",
        "melepas-musafir" to "Doa melepas orang yang bepergian", "pamit-musafir" to "Doa berpamitan",
        "ayat-kursi" to "Ayat Kursi", "al-fatihah" to "Al-Fatihah", "an-nas" to "An-Nas", "jin-1-9" to "Al-Jin: 1–9",
        "ruqyah-rabban-nas" to "Doa ruqyah untuk orang sakit",
    ).map { (id, t) -> PrayerRef(id, if (id.startsWith("ruqyah") || id in setOf("al-fatihah", "an-nas", "jin-1-9")) "ruqyah" else "perjalanan", t, null) }

    private fun m(q: String) = PrayerMatcher.match(q, items)

    @Test fun kendaraanSehariHari() {
        assertEquals(PrayerMatcher.Match.Item("naik-kendaraan"), m("Farhan, bacakan doa naik motor"))
        assertEquals(PrayerMatcher.Match.Item("naik-kendaraan"), m("doa naik pesawat"))
    }

    @Test fun kapalLebihSpesifikDariKendaraan() = assertEquals(PrayerMatcher.Match.Item("naik-kapal"), m("doa naik kapal laut"))

    @Test fun safarDanPulang() {
        assertEquals(PrayerMatcher.Match.Item("safar"), m("doa mudik"))
        assertEquals(PrayerMatcher.Match.Item("pulang"), m("doa pulang dari perjalanan"))
    }

    @Test fun ayatKursi() = assertEquals(PrayerMatcher.Match.Item("ayat-kursi"), m("bacakan ayat kursi"))

    @Test fun ruqyahUmumMembukaKategori() = assertEquals(PrayerMatcher.Match.Category("ruqyah"), m("ayat ayat ruqyah"))

    @Test fun ruqyahSpesifik() {
        assertEquals(PrayerMatcher.Match.Item("ruqyah-rabban-nas"), m("ruqyah untuk orang sakit"))
        // "perjalanan" bukan topik ruqyah → tetap kategori ruqyah
        assertEquals(PrayerMatcher.Match.Category("ruqyah"), m("ruqyah sebelum perjalanan"))
    }

    @Test fun kataJudulSebagaiCadangan() = assertEquals(PrayerMatcher.Match.Item("jin-1-9"), m("surat al jin"))

    @Test fun tidakDikenal() = assertEquals(PrayerMatcher.Match.None, m("doa"))
}
