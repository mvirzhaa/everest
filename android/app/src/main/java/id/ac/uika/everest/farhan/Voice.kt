package id.ac.uika.everest.farhan

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Pengenalan suara bahasa Indonesia. Meminta mode offline bila paket bahasa
 * Indonesia sudah terpasang di HP (Setelan → Pengenalan ucapan → Bahasa offline).
 */
class SpeechInput(
    context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onLevel: (Float) -> Unit = {},
) {
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null

    val available get() = recognizer != null

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) onError("Saya tidak menangkap suara. Coba lagi.") else onFinal(text)
            }
            override fun onError(error: Int) {
                onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Saya tidak menangkap suara. Ketuk mikrofon untuk mencoba lagi."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                            "Pengenal suara butuh internet. Pasang bahasa Indonesia offline di setelan pengenalan ucapan, atau ketik perintahnya."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Izin mikrofon belum diberikan."
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                            "Bahasa Indonesia belum tersedia di pengenal suara HP ini. Ketik perintahnya."
                        else -> "Pengenal suara bermasalah (kode $error). Ketik perintahnya."
                    },
                )
            }
        })
    }

    fun start() {
        recognizer?.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1),
        ) ?: onError("Pengenal suara tidak tersedia di HP ini. Ketik perintahnya.")
    }

    fun stop() = recognizer?.stopListening()

    fun destroy() = recognizer?.destroy()
}

/** Suara Farhan (TTS bahasa Indonesia bawaan HP). */
class Speaker(context: Context, private val onDone: () -> Unit) {
    @Volatile private var initDone = false
    @Volatile private var ready = false
    private var pending: String? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    private fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale.forLanguageTag("id-ID"))
            ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
        initDone = true
        val p = pending
        pending = null
        if (p != null) speak(p)
    }

    /** True bila suara bahasa Indonesia tersedia; bila tidak, Farhan hanya menampilkan teks. */
    val hasVoice get() = ready

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = onDone()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onDone()
        })
    }

    fun speak(text: String) {
        if (!initDone) {
            pending = text
            return
        }
        if (!ready) {
            onDone() // tanpa suara: lanjutkan alur seolah selesai bicara
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "farhan-${System.nanoTime()}")
    }

    fun stop() = tts.stop()

    fun shutdown() = tts.shutdown()
}
