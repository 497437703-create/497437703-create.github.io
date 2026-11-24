package com.aihuiji.meeting.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.util.Log
import com.aihuiji.meeting.core.model.Channel
import com.aihuiji.meeting.core.provider.AudioFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.sqrt

sealed interface CaptureStart {
    data class Started(val deviceName: String, val external: Boolean, val aecActive: Boolean) : CaptureStart
    data object NoPermission : CaptureStart
    data object NoDevice : CaptureStart
    data class Failed(val reason: String) : CaptureStart
}

/**
 * Near-end: USB omni mic via the VOICE_COMMUNICATION source so the platform/hardware AEC path is
 * engaged. Far-end: playback capture of the video-conference app through MediaProjection.
 * Each channel is recorded to its own WAV aligned to the meeting clock and streamed as 20 ms frames.
 */
class AudioCaptureEngine(
    private val context: Context,
    private val clockMs: () -> Long,
) {
    var onFrame: (AudioFrame) -> Unit = {}

    private val _levels = MutableStateFlow(mapOf(Channel.LOCAL to 0f, Channel.REMOTE to 0f))
    val levels: StateFlow<Map<Channel, Float>> = _levels.asStateFlow()

    private val workers = mutableMapOf<Channel, Worker>()
    @Volatile private var paused = false

    val activeChannels: Set<Channel> get() = workers.keys

    @SuppressLint("MissingPermission")
    fun startNear(dir: File): CaptureStart {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return CaptureStart.NoPermission
        }
        val mic = AudioDevices.bestMic(context) ?: return CaptureStart.NoDevice
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(format())
                .setBufferSizeInBytes(bufferSize())
                .build()
        } catch (e: Exception) {
            return CaptureStart.Failed(e.message ?: "AudioRecord")
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return CaptureStart.Failed("AudioRecord not initialised")
        }
        mic.device?.let { record.setPreferredDevice(it) }
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.enabled = true
        start(Channel.LOCAL, record, dir)
        return CaptureStart.Started(mic.name, mic.external, aec?.enabled == true)
    }

    /**
     * Captures the conference app's playback. VoIP-usage audio additionally needs
     * CAPTURE_VOICE_COMMUNICATION_OUTPUT, which only a privileged (preinstalled) build holds.
     */
    @SuppressLint("MissingPermission")
    fun startFar(projection: MediaProjection, dir: File): Boolean {
        if (Channel.REMOTE in workers) return true
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .apply {
                    if (context.checkSelfPermission("android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT") == PackageManager.PERMISSION_GRANTED) {
                        addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    }
                }
                .build()
            val record = AudioRecord.Builder()
                .setAudioFormat(format())
                .setBufferSizeInBytes(bufferSize())
                .setAudioPlaybackCaptureConfig(config)
                .build()
            start(Channel.REMOTE, record, dir)
            true
        } catch (e: Exception) {
            Log.w(TAG, "far-end capture unavailable", e)
            false
        }
    }

    private fun start(channel: Channel, record: AudioRecord, dir: File) {
        val wav = WavWriter(File(dir, "${channel.name.lowercase()}.wav"))
        wav.writeSilence(clockMs())
        workers[channel] = Worker(channel, record, wav).also { it.start() }
    }

    fun pause() { paused = true }
    fun resume() { paused = false }

    fun stop(): Map<Channel, File> {
        val files = workers.mapValues { (_, w) -> w.finish() }
        workers.clear()
        _levels.value = mapOf(Channel.LOCAL to 0f, Channel.REMOTE to 0f)
        return files
    }

    private inner class Worker(val channel: Channel, val record: AudioRecord, val wav: WavWriter) : Thread("capture-$channel") {
        @Volatile var running = true

        override fun run() {
            val buf = ShortArray(FRAME_SAMPLES)
            var pausedAt = -1L
            record.startRecording()
            while (running) {
                if (paused) {
                    if (pausedAt < 0) {
                        pausedAt = clockMs()
                        record.stop()
                        setLevel(0f)
                    }
                    sleep(50)
                    continue
                }
                if (pausedAt >= 0) {
                    wav.writeSilence(clockMs() - pausedAt)
                    pausedAt = -1
                    record.startRecording()
                }
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                wav.write(buf, n)
                setLevel(rms(buf, n))
                onFrame(AudioFrame(channel, clockMs(), buf.copyOf(n)))
            }
        }

        private fun setLevel(v: Float) {
            _levels.value = _levels.value + (channel to v)
        }

        fun finish(): File {
            running = false
            join(1_000)
            runCatching { record.stop() }
            record.release()
            wav.close()
            return wav.file
        }
    }

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = SAMPLE_RATE / 50

        private fun format() = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        private fun bufferSize() = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            FRAME_SAMPLES * 2 * 10,
        )

        /** Normalised loudness 0..1 with a perceptual curve so speech fills the waveform bars. */
        fun rms(buf: ShortArray, n: Int): Float {
            var sum = 0.0
            for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
            val r = sqrt(sum / n) / 32768.0
            return (sqrt(r) * 1.8).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
