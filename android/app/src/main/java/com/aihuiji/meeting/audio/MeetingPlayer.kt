package com.aihuiji.meeting.audio

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Plays the meeting's per-channel recordings in lock-step (near + far WAVs share the meeting clock).
 * Supports bounded playback for 溯源 clips.
 */
class MeetingPlayer(files: Collection<String>) {
    private val players = files.map(::File).filter { it.exists() && it.length() > WavWriter.HEADER }.mapNotNull { f ->
        runCatching { MediaPlayer().apply { setDataSource(f.absolutePath); prepare() } }.getOrNull()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private var stopAt: Long? = null

    val available: Boolean get() = players.isNotEmpty()
    val durationMs: Long get() = players.maxOfOrNull { it.duration.toLong() } ?: 0

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    fun play(fromMs: Long? = null, untilMs: Long? = null) {
        if (!available) return
        fromMs?.let { seek(it) }
        stopAt = untilMs
        players.forEach { it.start() }
        _playing.value = true
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val pos = players.first().currentPosition.toLong()
                _position.value = pos
                val limit = stopAt
                if ((limit != null && pos >= limit) || players.none { it.isPlaying }) {
                    pause()
                    break
                }
                delay(100)
            }
        }
    }

    fun pause() {
        players.forEach { if (it.isPlaying) it.pause() }
        _playing.value = false
        ticker?.cancel()
    }

    fun seek(ms: Long) {
        players.forEach { it.seekTo(ms.coerceIn(0, it.duration.toLong()), MediaPlayer.SEEK_CLOSEST) }
        _position.value = ms
    }

    fun release() {
        ticker?.cancel()
        players.forEach { it.release() }
        scope.cancel()
    }
}
