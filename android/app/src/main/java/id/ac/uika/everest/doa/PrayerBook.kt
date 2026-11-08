package id.ac.uika.everest.doa

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Verse(
    val ref: String,
    val number: Int,
    val numberAr: String,
    val arabic: String,
    val latin: String? = null,
    val meaning: String,
)

@Serializable
data class Prayer(
    val id: String,
    val category: String,
    val title: String,
    val `when`: String? = null,
    val source: String,
    val note: String? = null,
    val repeat: String? = null,
    val arabic: String? = null,
    val latin: String? = null,
    val meaning: String? = null,
    val verses: List<Verse> = emptyList(),
) {
    /** Teks lengkap untuk disalin/dibagikan. */
    fun shareText(): String = buildString {
        appendLine(title)
        appendLine()
        if (verses.isEmpty()) {
            appendLine(arabic)
            latin?.let { appendLine(); appendLine(it) }
            meaning?.let { appendLine(); appendLine("Artinya: $it") }
        } else {
            verses.forEach { v ->
                appendLine("${v.arabic} ﴿${v.numberAr}﴾")
                v.latin?.let { appendLine(it) }
                appendLine("(${v.number}) ${v.meaning}")
                appendLine()
            }
        }
        appendLine()
        append("Sumber: $source")
    }
}

@Serializable
data class PrayerCategory(val id: String, val title: String, val subtitle: String, val items: List<Prayer>)

@Serializable
data class PrayerData(val version: Int, val categories: List<PrayerCategory>, val credits: List<String> = emptyList())

/** Kumpulan doa & ayat dari assets/doa.json (dibangun oleh tools/doa/build_doa.py). Tersedia offline. */
object PrayerBook {
    @Volatile private var cached: PrayerData? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): PrayerData = cached ?: synchronized(this) {
        cached ?: context.applicationContext.assets.open("doa.json").bufferedReader().use { json.decodeFromString(PrayerData.serializer(), it.readText()) }
            .also { cached = it }
    }

    fun find(context: Context, id: String): Prayer? = load(context).categories.asSequence().flatMap { it.items }.firstOrNull { it.id == id }

    fun refs(context: Context): List<PrayerRef> =
        load(context).categories.flatMap { c -> c.items.map { PrayerRef(it.id, c.id, it.title, it.`when`) } }

    /** Pencarian teks bebas di judul, keterangan, latin, dan arti. */
    fun search(context: Context, query: String): List<Prayer> {
        val words = query.lowercase().split(Regex("""\s+""")).filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()
        return load(context).categories.flatMap { it.items }.filter { p ->
            val hay = listOfNotNull(p.title, p.`when`, p.latin, p.meaning, p.source, p.verses.joinToString(" ") { it.meaning + " " + (it.latin ?: "") + " " + it.ref })
                .joinToString(" ").lowercase()
            words.all { hay.contains(it) }
        }
    }
}
