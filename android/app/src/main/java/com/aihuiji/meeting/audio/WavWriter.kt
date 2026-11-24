package com.aihuiji.meeting.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Streaming PCM16 mono WAV writer; the header sizes are patched on [close]. */
class WavWriter(val file: File, private val sampleRate: Int = AudioCaptureEngine.SAMPLE_RATE) {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(HEADER)) }
    private var dataBytes = 0L
    private val scratch = ByteBuffer.allocate(AudioCaptureEngine.FRAME_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)

    val durationMs: Long get() = dataBytes / 2 * 1000 / sampleRate

    @Synchronized
    fun write(pcm: ShortArray, count: Int = pcm.size) {
        val buf = if (count * 2 <= scratch.capacity()) scratch.apply { clear() } else ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) buf.putShort(pcm[i])
        raf.write(buf.array(), 0, count * 2)
        dataBytes += count * 2
    }

    @Synchronized
    fun writeSilence(ms: Long) {
        var samples = ms * sampleRate / 1000
        val zeros = ByteArray(4096)
        while (samples > 0) {
            val n = minOf(samples * 2, zeros.size.toLong()).toInt()
            raf.write(zeros, 0, n)
            dataBytes += n
            samples -= n / 2
        }
    }

    @Synchronized
    fun close() {
        val h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        h.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(h.array())
        raf.close()
    }

    companion object {
        const val HEADER = 44

        /** Peak envelope of a PCM16 WAV in [buckets] bins (0..1), for the waveform timeline. */
        fun envelope(file: File, buckets: Int): FloatArray {
            if (!file.exists() || file.length() <= HEADER) return FloatArray(0)
            val samples = (file.length() - HEADER) / 2
            val per = (samples / buckets).coerceAtLeast(1)
            val out = FloatArray(buckets)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(HEADER.toLong())
                val buf = ByteArray((per * 2).toInt().coerceAtMost(1 shl 20))
                for (b in 0 until buckets) {
                    val read = raf.read(buf)
                    if (read <= 0) break
                    var peak = 0
                    var i = 0
                    while (i + 1 < read) {
                        val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                        peak = maxOf(peak, kotlin.math.abs(s.toShort().toInt()))
                        i += 2
                    }
                    out[b] = peak / 32768f
                    if (per * 2 > buf.size) raf.seek(raf.filePointer + per * 2 - buf.size)
                }
            }
            return out
        }
    }
}
