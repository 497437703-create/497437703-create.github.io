package com.aihuiji.meeting.data

import com.aihuiji.meeting.core.model.MeetingRecord
import com.aihuiji.meeting.core.model.MeetingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local meeting store: one directory per meeting with `meeting.json` and its audio files.
 * Keeps the newest [cacheLimit] meetings (F-11) and deletes anything past the retention period.
 */
class MeetingRepository(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _meetings = MutableStateFlow<List<MeetingRecord>>(emptyList())
    val meetings: StateFlow<List<MeetingRecord>> = _meetings.asStateFlow()

    init {
        root.mkdirs()
    }

    fun dirFor(id: String): File = File(root, id).apply { mkdirs() }

    suspend fun load() = withContext(Dispatchers.IO) {
        val list = root.listFiles()?.mapNotNull { dir ->
            File(dir, FILE).takeIf { it.exists() }?.let { f ->
                runCatching { json.decodeFromString(MeetingRecord.serializer(), f.readText()) }.getOrNull()
            }
        }.orEmpty()
        _meetings.value = list.sortedByDescending { it.startedAtEpochMs }
    }

    fun get(id: String): MeetingRecord? = _meetings.value.firstOrNull { it.id == id }

    fun findByCode(code: String): MeetingRecord? = _meetings.value.firstOrNull { it.code.equals(code, ignoreCase = true) }

    suspend fun save(record: MeetingRecord) = withContext(Dispatchers.IO) {
        val dir = dirFor(record.id)
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(json.encodeToString(MeetingRecord.serializer(), record))
        tmp.renameTo(File(dir, FILE))
        synchronized(this@MeetingRepository) {
            _meetings.value = (listOf(record) + _meetings.value.filterNot { it.id == record.id })
                .sortedByDescending { it.startedAtEpochMs }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        synchronized(this@MeetingRepository) { _meetings.value = _meetings.value.filterNot { it.id == id } }
    }

    /** Meetings left LIVE/PAUSED/ENDING by a crash or power loss (F-01 恢复未结束会议). */
    fun unfinished(activeId: String?): List<MeetingRecord> = _meetings.value.filter {
        it.id != activeId && it.state in setOf(MeetingState.LIVE, MeetingState.PAUSED, MeetingState.ENDING, MeetingState.GENERATING)
    }

    suspend fun prune(retentionDays: Int, cacheLimit: Int, now: Long = System.currentTimeMillis()) {
        val cutoff = now - retentionDays * 24L * 3600 * 1000
        val sorted = _meetings.value.sortedByDescending { it.startedAtEpochMs }
        val expired = sorted.filter { it.startedAtEpochMs < cutoff } + sorted.drop(cacheLimit)
        expired.distinctBy { it.id }
            .filter { it.state == MeetingState.DONE || it.state == MeetingState.FAILED }
            .forEach { delete(it.id) }
    }

    private companion object {
        const val FILE = "meeting.json"
    }
}
