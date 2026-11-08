package id.ac.uika.everest.whisper

import android.app.ActivityManager
import android.content.Context
import id.ac.uika.everest.Locator
import java.io.File

/** Pemanggilan native ke whisper.cpp (lihat app/src/main/cpp/whisper_jni.cpp). */
object WhisperLib {
    init {
        System.loadLibrary("everest_whisper")
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(ctx: Long)
    external fun fullTranscribe(ctx: Long, audio: FloatArray, language: String, nThreads: Int): Int
    external fun segmentCount(ctx: Long): Int
    external fun segmentText(ctx: Long, index: Int): String
    external fun segmentT0(ctx: Long, index: Int): Long
    external fun segmentT1(ctx: Long, index: Int): Long
    external fun systemInfo(): String
}

data class Segment(val startMs: Long, val endMs: Long, val text: String)

/** Satu konteks whisper (model dimuat sekali per sesi kerja, dilepas di close()). */
class WhisperEngine private constructor(private var ctx: Long) : AutoCloseable {

    fun transcribe(pcm: FloatArray, language: String = "id"): List<Segment> {
        check(ctx != 0L) { "konteks whisper sudah dilepas" }
        val rc = WhisperLib.fullTranscribe(ctx, pcm, language, 0)
        if (rc != 0) throw IllegalStateException("whisper gagal ($rc)")
        return (0 until WhisperLib.segmentCount(ctx)).mapNotNull { i ->
            val text = WhisperLib.segmentText(ctx, i).trim()
            if (text.isEmpty() || NOISE.matches(text)) null
            else Segment(WhisperLib.segmentT0(ctx, i) * 10, WhisperLib.segmentT1(ctx, i) * 10, text)
        }
    }

    override fun close() {
        if (ctx != 0L) WhisperLib.freeContext(ctx)
        ctx = 0
    }

    companion object {
        /** Penanda non-ucapan yang sering dihasilkan whisper pada bagian hening/musik. */
        private val NOISE = Regex("""^[\[(（].*[\])）]$|^\W*$""")

        fun load(model: File): WhisperEngine {
            val ptr = WhisperLib.initContext(model.absolutePath)
            if (ptr == 0L) throw IllegalStateException("Model whisper rusak atau tidak cocok: ${model.name}")
            return WhisperEngine(ptr)
        }
    }
}

/** Pemilihan dan lokasi model sesuai kemampuan HP. */
object Models {
    const val BASE = "ggml-base-q5_1.bin"
    const val SMALL = "ggml-small-q5_1.bin"

    fun recommended(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val gb = mi.totalMem / (1024.0 * 1024 * 1024)
        // RAM 6 GB ke atas (dilaporkan ±5,5 GB) → small; selain itu base
        return if (gb >= 5.3) SMALL else BASE
    }

    fun chosen(context: Context): String = Locator.prefs.modelName ?: recommended(context)

    fun dir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }

    fun file(context: Context, name: String = chosen(context)) = File(dir(context), name)

    /** Model siap dipakai: yang dipilih, atau model lain yang sudah terunduh. */
    fun available(context: Context): File? {
        val f = file(context)
        if (f.exists() && f.length() > 1_000_000) return f
        return dir(context).listFiles { x -> x.name.startsWith("ggml-") && x.name.endsWith(".bin") && x.length() > 1_000_000 }?.firstOrNull()
    }
}
