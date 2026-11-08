package id.ac.uika.everest.privacy

/**
 * Persetujuan pemrosesan data pribadi (UU No. 27 Tahun 2022 tentang PDP).
 * Naikkan [VERSION] (sama dengan PRIVACY_VERSION di server) setiap kali isi kebijakan berubah:
 * aplikasi akan meminta persetujuan ulang.
 */
object Privacy {
    const val VERSION = "2026-11-07"
    const val FARHAN_CLOUD = "farhan_cloud"

    data class Scope(val key: String, val title: String, val detail: String, val required: Boolean)

    val SCOPES = listOf(
        Scope("kontak", "Akun & kontak darurat", "Nama, email kampus, nomor HP, serta nama dan nomor kontak darurat yang Anda tambahkan.", true),
        Scope("panic", "Panic & sirine", "Saat Panic: lokasi, baterai, nama, dan nomor HP dikirim ke kontak darurat dan satpam kampus. Tanpa internet, lokasi dikirim lewat SMS dari HP Anda. Bila tidak ditanggapi, diteruskan ke semua satpam lalu admin.", true),
        Scope("lokasi", "Berbagi lokasi (Track)", "Hanya saat Track menyala: posisi setiap 10 detik ke kontak yang Anda pilih. Riwayat Track biasa dihapus otomatis setelah 30 hari.", true),
        Scope("rekam", "Rekam & transkrip", "Suara ditranskrip di HP dan audionya tetap di HP. Hanya teks transkrip yang disinkronkan ke server kampus. Minta izin orang yang Anda rekam.", true),
        Scope("laporan", "Laporan (Report)", "Foto, deskripsi, dan lokasi laporan dikirim ke admin kampus. Metadata foto dihapus di server.", true),
        Scope(FARHAN_CLOUD, "Farhan cloud (opsional)", "Pertanyaan bebas dan ringkasan dikirim lewat server kampus ke penyedia AI cloud beserta potongan transkrip yang relevan. Bisa dimatikan kapan saja di Profil.", false),
    )

    /** Cakupan yang dicatat saat menyetujui. */
    fun scopes(farhanCloud: Boolean): Set<String> =
        SCOPES.filter { it.required || (it.key == FARHAN_CLOUD && farhanCloud) }.map { it.key }.toSet()

    /** Perlu meminta persetujuan (lagi)? */
    fun needsConsent(agreedVersion: String?) = agreedVersion != VERSION

    fun policyUrl(serverUrl: String) = serverUrl.trim().trimEnd('/') + "/privasi"
}
