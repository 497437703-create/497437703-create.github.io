package com.aihuiji.meeting.core.provider.remote

import com.aihuiji.meeting.core.model.Channel
import com.aihuiji.meeting.core.provider.AudioFrame
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Disk-backed FIFO of audio frames captured while the stream is down (F-15 断网缓冲).
 * Frames past [maxBytes] are dropped from the stream side only; the local recording keeps them,
 * so post-meeting re-transcription can still recover the audio.
 */
class FrameSpool(private val file: File, private val maxBytes: Long = 256L * 1024 * 1024) {
    private var out: DataOutputStream? = null
    var frameCount: Int = 0
        private set
    var droppedFrames: Int = 0
        private set

    val isEmpty: Boolean @Synchronized get() = frameCount == 0

    @Synchronized
    fun append(frame: AudioFrame) {
        if (file.length() >= maxBytes) {
            droppedFrames++
            return
        }
        val stream = out ?: DataOutputStream(FileOutputStream(file, true).buffered()).also { out = it }
        stream.writeByte(frame.channel.ordinal)
        stream.writeLong(frame.offsetMs)
        stream.writeInt(frame.pcm.size)
        frame.pcm.forEach { stream.writeShort(it.toInt()) }
        frameCount++
    }

    /** Replays every spooled frame in capture order, then truncates the spool. */
    @Synchronized
    fun drain(consumer: (AudioFrame) -> Unit): Int {
        out?.close()
        out = null
        if (!file.exists()) return 0
        var n = 0
        DataInputStream(FileInputStream(file).buffered()).use { input ->
            while (true) {
                val ch = try { input.readByte().toInt() } catch (_: EOFException) { break }
                val offset = input.readLong()
                val size = input.readInt()
                val pcm = ShortArray(size) { input.readShort() }
                consumer(AudioFrame(Channel.entries[ch], offset, pcm))
                n++
            }
        }
        file.delete()
        frameCount = 0
        return n
    }

    @Synchronized
    fun clear() {
        out?.close()
        out = null
        file.delete()
        frameCount = 0
    }
}
