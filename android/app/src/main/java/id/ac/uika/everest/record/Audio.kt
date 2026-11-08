package id.ac.uika.everest.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val SAMPLE_RATE = 16_000

/** Penulis WAV PCM 16-bit mono 16 kHz (format masukan whisper.cpp). */
class WavWriter(val file: File) {
    private val raf = RandomAccessFile(file, "rw")
    var samples: Long = 0
        private set

    init {
        raf.setLength(0)
        raf.write(ByteArray(44)) // header diisi saat close
    }

    fun write(buf: ShortArray, count: Int) {
        val bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bb.putShort(buf[i])
        raf.write(bb.array())
        samples += count
    }

    fun close() {
        val dataLen = samples * 2
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataLen).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(dataLen.toInt())
        raf.seek(0)
        raf.write(h.array())
        raf.close()
    }
}

/** Baca WAV PCM16 mono → float [-1, 1]. */
fun readWavAsFloats(file: File): FloatArray {
    val bytes = file.readBytes()
    // Cari chunk "data" (header bisa lebih dari 44 byte)
    var pos = 12
    var dataStart = 44
    var dataLen = bytes.size - 44
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4)
        val len = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (id == "data") {
            dataStart = pos + 8
            dataLen = minOf(len, bytes.size - dataStart)
            break
        }
        pos += 8 + len
    }
    val sb = ByteBuffer.wrap(bytes, dataStart, dataLen).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    return FloatArray(sb.remaining()) { sb.get(it) / 32768f }
}

/**
 * Deteksi suara sederhana berbasis energi: proporsi bingkai 30 ms yang lebih keras dari ambang.
 * Dipakai untuk melewati potongan hening agar tidak membuang waktu/baterai menjalankan whisper.
 */
fun speechRatio(pcm: FloatArray, threshold: Float = 0.012f): Float {
    val frame = SAMPLE_RATE * 30 / 1000
    if (pcm.size < frame) return 0f
    var voiced = 0
    var frames = 0
    var i = 0
    while (i + frame <= pcm.size) {
        var sum = 0.0
        for (j in i until i + frame) sum += pcm[j] * pcm[j]
        if (Math.sqrt(sum / frame) > threshold) voiced++
        frames++
        i += frame
    }
    return voiced.toFloat() / frames
}

/**
 * Ubah WAV (PCM16 mono) menjadi AAC dalam wadah .m4a (~32 kbps) untuk menghemat ruang:
 * 1 jam rekaman ≈ 14 MB, bukan ≈ 115 MB WAV.
 */
fun encodeWavToM4a(wav: File, out: File, bitRate: Int = 32_000) {
    val pcm = run {
        val f = readWavAsFloats(wav)
        ByteBuffer.allocate(f.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> f.forEach { b.putShort((it * 32767f).toInt().toShort()) } }.array()
    }
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    codec.start()
    val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var track = -1
    var muxing = false
    val info = MediaCodec.BufferInfo()
    var inPos = 0
    var inputDone = false
    var outputDone = false
    try {
        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    buf.clear()
                    val n = minOf(buf.remaining(), pcm.size - inPos)
                    val ptsUs = (inPos / 2L) * 1_000_000L / SAMPLE_RATE
                    if (n <= 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        buf.put(pcm, inPos, n)
                        codec.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                        inPos += n
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                outIdx >= 0 -> {
                    val ob = codec.getOutputBuffer(outIdx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
    } finally {
        runCatching { codec.stop() }
        codec.release()
        if (muxing) runCatching { muxer.stop() }
        muxer.release()
    }
}
