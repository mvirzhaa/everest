package id.ac.uika.everest.record

/** Ekstraksi kata kunci sederhana dari transkrip (frekuensi kata, tanpa kata umum bahasa Indonesia). */
object Keywords {
    private val STOP = setOf(
        "yang", "dan", "di", "ke", "dari", "ini", "itu", "untuk", "dengan", "pada", "adalah", "akan", "juga", "tidak",
        "ada", "saya", "kita", "kami", "kamu", "anda", "mereka", "dia", "ia", "bisa", "sudah", "belum", "lagi", "jadi",
        "karena", "kalau", "jika", "atau", "tapi", "tetapi", "namun", "seperti", "dalam", "oleh", "sebagai", "para",
        "nya", "pun", "lah", "kah", "dong", "sih", "nah", "ya", "oke", "ok", "eh", "ah", "hmm", "gitu", "begitu", "gini",
        "aja", "saja", "harus", "masih", "sangat", "lebih", "kurang", "sama", "semua", "setiap", "hari", "kali", "mau",
        "ingin", "bagaimana", "kenapa", "mengapa", "apa", "siapa", "kapan", "dimana", "mana", "bahwa", "hal", "terus",
        "nanti", "tadi", "sekarang", "sini", "situ", "sana", "kemudian", "lalu", "setelah", "sebelum", "baik", "banyak",
        "satu", "dua", "tiga", "bapak", "ibu", "pak", "bu", "mas", "mbak", "teman", "temen", "gimana", "punya", "buat",
        "bikin", "the", "and", "you", "that", "this", "with", "for",
    )

    fun extract(text: String, max: Int = 6): List<String> =
        Regex("""[\p{L}][\p{L}\p{N}-]{3,}""").findAll(text.lowercase())
            .map { it.value.trim('-') }
            .filter { it.length >= 4 && it !in STOP }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .filter { it.value >= 2 }
            .take(max)
            .map { it.key }

    /** Kata perintah/pertanyaan yang bukan isi rekaman (tidak ikut dicari). */
    private val QUERY_NOISE = setOf(
        "tentang", "soal", "membahas", "bahas", "dibahas", "cari", "carikan", "temukan", "tampilkan", "rekaman", "rekamannya",
        "catatan", "transkrip", "isi", "isinya", "menyebut", "disebut", "farhan", "tolong", "dong", "saja", "aja",
    )

    /** Teks aman untuk FTS4 MATCH: kata dengan awalan (prefix) digabung AND. */
    fun ftsQuery(input: String): String? {
        val words = Regex("""[\p{L}\p{N}]+""").findAll(input.lowercase()).map { it.value }
            .filter { it !in STOP && it !in QUERY_NOISE && it.length >= 2 }.toList()
        if (words.isEmpty()) return null
        return words.take(6).joinToString(" ") { "$it*" }
    }
}
