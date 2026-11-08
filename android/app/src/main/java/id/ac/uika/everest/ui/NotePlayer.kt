package id.ac.uika.everest.ui

import android.media.MediaPlayer
import id.ac.uika.everest.data.local.ChunkEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Memutar rekaman yang tersimpan sebagai potongan berurutan (m4a/wav) seolah satu file.
 * Posisi dinyatakan dalam milidetik sejak awal rekaman.
 */
class NotePlayer(chunks: List<ChunkEntity>) {
    private val parts = chunks.filter { File(it.path).exists() }.sortedBy { it.idx }
    private var player: MediaPlayer? = null
    private var current = -1

    val available: Boolean get() = parts.isNotEmpty()
    val durationMs: Long = parts.lastOrNull()?.let { it.offsetMs + it.durationMs } ?: 0L

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    fun positionMs(): Long {
        val p = player ?: return 0
        val part = parts.getOrNull(current) ?: return 0
        return part.offsetMs + runCatching { p.currentPosition.toLong() }.getOrDefault(0L)
    }

    fun play(fromMs: Long = positionMs()) {
        if (!available) return
        val idx = parts.indexOfLast { it.offsetMs <= fromMs }.coerceAtLeast(0)
        open(idx, (fromMs - parts[idx].offsetMs).coerceAtLeast(0))
    }

    private fun open(idx: Int, seekMs: Long) {
        release()
        if (idx !in parts.indices) {
            _playing.value = false
            current = -1
            return
        }
        current = idx
        player = MediaPlayer().apply {
            setDataSource(parts[idx].path)
            setOnCompletionListener { open(idx + 1, 0) }
            setOnPreparedListener {
                if (seekMs > 0) it.seekTo(seekMs.toInt())
                it.start()
                _playing.value = true
            }
            setOnErrorListener { _, _, _ ->
                open(idx + 1, 0)
                true
            }
            prepareAsync()
        }
    }

    fun pause() {
        runCatching { player?.pause() }
        _playing.value = false
    }

    fun toggle() = if (_playing.value) pause() else play()

    fun release() {
        player?.let { runCatching { it.release() } }
        player = null
    }
}
