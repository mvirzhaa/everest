package id.ac.uika.everest.record

/** Aturan teks transkrip yang murni (tanpa Android), supaya bisa diuji unit. */
object TranscriptText {
    const val AUTO_SUMMARY = 280

    /** Status catatan yang boleh diedit: transkripsi sudah selesai (atau gagal sebagian). */
    fun editable(status: String) = status == "selesai" || status == "gagal"

    /** Rapikan spasi; teks kosong berarti segmen dihapus. */
    fun clean(text: String) = text.replace(Regex("\\s+"), " ").trim()

    /**
     * Ringkasan otomatis (280 huruf pertama transkrip) ikut diperbarui; ringkasan yang ditulis
     * pengguna/Farhan (berbeda dari potongan otomatis) dibiarkan.
     */
    fun nextSummary(current: String?, oldText: String, newText: String): String? =
        if (current == null || current == oldText.take(AUTO_SUMMARY)) newText.take(AUTO_SUMMARY).takeIf { it.isNotBlank() } else current
}
