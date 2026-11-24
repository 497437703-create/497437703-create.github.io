package com.aihuiji.meeting

import com.aihuiji.meeting.export.ExportFormat
import com.aihuiji.meeting.export.FileSink
import com.aihuiji.meeting.export.MinutesSaver
import com.aihuiji.meeting.export.exportBaseName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class MinutesSaverTest {
    @get:Rule val tmp = TemporaryFolder()

    private class MemorySink(val failOn: Set<String> = emptySet()) : FileSink {
        override val label = "Download/AI会记"
        val files = linkedMapOf<String, ByteArrayOutputStream>()
        val mimes = mutableMapOf<String, String>()
        override fun create(name: String, mime: String): OutputStream {
            if (name in failOn) throw IOException("denied")
            mimes[name] = mime
            return ByteArrayOutputStream().also { files[name] = it }
        }
    }

    private fun sources() = listOf(
        tmp.newFile("M-1.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) } to ExportFormat.PDF,
        tmp.newFile("M-1.md").apply { writeText("# 纪要") } to ExportFormat.MARKDOWN,
        tmp.newFile("M-1.txt").apply { writeText("纪要") } to ExportFormat.TEXT,
    )

    @Test fun savesEveryFormatWithItsMimeType() {
        val sink = MemorySink()
        val result = MinutesSaver.save(sources(), sink)
        assertTrue(result.ok)
        assertEquals(listOf("M-1.pdf", "M-1.md", "M-1.txt"), result.saved)
        assertEquals("Download/AI会记", result.where)
        assertEquals("application/pdf", sink.mimes["M-1.pdf"])
        assertEquals("text/markdown", sink.mimes["M-1.md"])
        assertEquals("# 纪要", sink.files.getValue("M-1.md").toString(Charsets.UTF_8))
        assertEquals(listOf<Byte>(1, 2, 3), sink.files.getValue("M-1.pdf").toByteArray().toList())
    }

    @Test fun oneFailedFileDoesNotStopTheOthers() {
        val result = MinutesSaver.save(sources(), MemorySink(failOn = setOf("M-1.md")))
        assertFalse(result.ok)
        assertEquals(listOf("M-1.pdf", "M-1.txt"), result.saved)
        assertEquals(listOf("M-1.md"), result.failed)
    }

    @Test fun nothingSavedIsNotOk() {
        val result = MinutesSaver.save(emptyList(), MemorySink())
        assertFalse(result.ok)
    }

    @Test fun treeLabelDropsTheVolumePrefix() {
        assertEquals("Documents/Meetings", MinutesSaver.treeLabel("primary:Documents/Meetings"))
        assertEquals("primary", MinutesSaver.treeLabel("primary:"))
        assertEquals("Download", MinutesSaver.treeLabel("Download"))
    }

    @Test fun exportBaseNameIsStablePerMeeting() {
        val a = exportBaseName("ABC123", 1_700_000_000_000)
        assertEquals(a, exportBaseName("ABC123", 1_700_000_000_000))
        assertTrue(a.startsWith("ABC123-"))
        assertTrue(Regex("ABC123-\\d{8}-\\d{4}").matches(a))
    }
}
