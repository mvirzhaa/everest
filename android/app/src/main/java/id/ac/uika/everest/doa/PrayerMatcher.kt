package id.ac.uika.everest.doa

/** Ringkasan bacaan untuk pencarian (tanpa ketergantungan Android, mudah diuji). */
data class PrayerRef(val id: String, val category: String, val title: String, val whenText: String?)

/**
 * Mencocokkan permintaan bebas ("doa naik motor", "bacakan ayat kursi", "ayat ruqyah")
 * ke satu bacaan atau satu kategori.
 */
object PrayerMatcher {
    sealed class Match {
        data class Item(val id: String) : Match()
        data class Category(val id: String) : Match()
        data object None : Match()
    }

    /** Kata pemicu → id bacaan (urutan penting: yang lebih spesifik di atas). */
    private val SYNONYMS: List<Pair<Regex, String>> = listOf(
        Regex("""\b(kapal|perahu|feri|ferry|speedboat|laut)\b""") to "naik-kapal",
        Regex("""\b(pulang|kembali dari|tiba di rumah)\b""") to "pulang",
        Regex("""\b(singgah|menginap|berhenti|hotel|penginapan|istirahat)\b""") to "singgah",
        Regex("""\b(masuk|memasuki|sampai di) (kota|desa|kampung|daerah)\b""") to "masuk-kota",
        Regex("""\b(tanjakan|menanjak|turunan|menurun)\b""") to "menanjak-menurun",
        Regex("""\b(tergelincir|terpeleset|oleng|tersandung|jatuh)\b""") to "tergelincir",
        Regex("""\b(takut|khawatir|bahaya|terancam|begal|preman)\b""") to "takut-bahaya",
        Regex("""\b(pamit|berpamitan|menitipkan|titip)\b""") to "pamit-musafir",
        Regex("""\b(melepas|mengantar|melepaskan)\b""") to "melepas-musafir",
        Regex("""\b(tersesat|sesat|dizalimi|zalim)\b""") to "keluar-rumah-2",
        Regex("""\b(keluar rumah|berangkat dari rumah|keluar dari rumah)\b""") to "keluar-rumah",
        Regex("""\b(pagi|petang|sore) (dan|&) (petang|sore|pagi)|perlindungan harian|la yadhurru\b""") to "perlindungan-harian",
        Regex("""\b(kendaraan|motor|mobil|bus|bis|kereta|pesawat|ojek|angkot|taksi|krl|mrt)\b""") to "naik-kendaraan",
        Regex("""\b(safar|bepergian jauh|perjalanan jauh|mudik|bepergian|perjalanan)\b""") to "safar",
        Regex("""\b(ayat kursi|ayatul kursi|kursi)\b""") to "ayat-kursi",
        Regex("""\b(orang sakit|menjenguk|sakit)\b""") to "ruqyah-rabban-nas",
        Regex("""\b(al ?fatihah|fatihah)\b""") to "al-fatihah",
        Regex("""\b(al ?ikhlas|ikhlas)\b""") to "al-ikhlas",
        Regex("""\b(al ?falaq|falaq)\b""") to "al-falaq",
        Regex("""\b(an ?nas)\b""") to "an-nas",
    )

    private val RUQYAH = Regex("""\b(ruqyah|ruqiyah|rukyah|rukiyah|ruqya)\b""")
    private val RUQYAH_TOPICS = setOf("ayat-kursi", "al-fatihah", "al-ikhlas", "al-falaq", "an-nas", "ruqyah-rabban-nas")
    private val STOP = setOf("doa", "do'a", "bacaan", "bacakan", "baca", "tolong", "farhan", "saya", "untuk", "yang", "ketika", "saat", "waktu", "naik", "minta", "dong", "ayat", "apa")

    fun match(query: String, items: List<PrayerRef>): Match {
        val q = query.lowercase().replace("’", "'").replace(Regex("""[?!.,;"]"""), " ").replace(Regex("""\s+"""), " ").trim()
        val ids = items.map { it.id }.toSet()
        val ruqyah = RUQYAH.containsMatchIn(q)
        // "ruqyah orang sakit" → doa spesifik; "ayat ruqyah" saja → kategori ruqyah
        val allowed = if (ruqyah) RUQYAH_TOPICS else null
        for ((re, id) in SYNONYMS) {
            if (id in ids && (allowed == null || id in allowed) && re.containsMatchIn(q)) return Match.Item(id)
        }
        if (ruqyah) return Match.Category("ruqyah")
        if (Regex("""\b(doa (perjalanan|bepergian|musafir))\b""").containsMatchIn(q)) return Match.Category("perjalanan")

        // Cocokkan sisa kata dengan judul/keterangan
        val words = Regex("""[\p{L}']+""").findAll(q).map { it.value }.filter { it.length >= 3 && it !in STOP }.toList()
        if (words.isEmpty()) return Match.None
        val best = items.map { item ->
            val hay = (item.title + " " + (item.whenText ?: "") + " " + item.id.replace('-', ' ')).lowercase()
            item to words.count { w -> hay.contains(w) }
        }.filter { it.second > 0 }.maxByOrNull { it.second }
        return best?.let { Match.Item(it.first.id) } ?: Match.None
    }
}
