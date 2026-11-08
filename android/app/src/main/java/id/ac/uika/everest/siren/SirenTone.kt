package id.ac.uika.everest.siren

import kotlin.math.PI
import kotlin.math.sin

/**
 * Pembangkit suara sirine sintetis (tanpa file audio): nada "wail" yang naik-turun
 * antara [lowHz] dan [highHz] dalam satu siklus [cycleSec] detik, ditambah harmonik
 * agar terdengar tajam dan menembus keramaian. Murni Kotlin sehingga bisa diuji tanpa Android.
 */
class SirenTone(
    private val sampleRate: Int = 44_100,
    private val lowHz: Double = 650.0,
    private val highHz: Double = 1_550.0,
    private val cycleSec: Double = 1.6,
) {
    private var phase = 0.0
    private var t = 0L

    /** Frekuensi sesaat pada detik ke-[sec] (naik linear lalu turun, segitiga). */
    fun frequencyAt(sec: Double): Double {
        val pos = (sec % cycleSec) / cycleSec // 0..1
        val tri = if (pos < 0.5) pos * 2 else 2 - pos * 2 // 0→1→0
        return lowHz + (highHz - lowHz) * tri
    }

    /** Isi [out] dengan sampel PCM16 berikutnya (kontinu antar panggilan). */
    fun fill(out: ShortArray) {
        for (i in out.indices) {
            val f = frequencyAt(t.toDouble() / sampleRate)
            phase += 2 * PI * f / sampleRate
            if (phase > 2 * PI) phase -= 2 * PI
            // Gelombang dasar + harmonik ke-3 dan ke-5 → mendekati gelombang kotak yang lebih keras
            val s = sin(phase) + sin(3 * phase) / 3 + sin(5 * phase) / 5
            out[i] = (s * 0.72 * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            t++
        }
    }
}
