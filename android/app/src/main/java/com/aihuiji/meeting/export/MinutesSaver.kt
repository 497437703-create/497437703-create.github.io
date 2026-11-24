package com.aihuiji.meeting.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/** Where saved minutes end up. Implementations create one user-visible file per call. */
interface FileSink {
    val label: String
    fun create(name: String, mime: String): OutputStream
}

data class SaveResult(val saved: List<String>, val failed: List<String>, val where: String) {
    val ok get() = saved.isNotEmpty() && failed.isEmpty()
}

/** Post-meeting "save to the device" step: copies the exported files into a user-visible folder. */
object MinutesSaver {
    fun save(files: List<Pair<File, ExportFormat>>, sink: FileSink): SaveResult {
        val saved = mutableListOf<String>()
        val failed = mutableListOf<String>()
        files.forEach { (file, format) ->
            runCatching {
                sink.create(file.name, format.mime).use { out -> file.inputStream().use { it.copyTo(out) } }
            }.onSuccess { saved += file.name }.onFailure { failed += file.name }
        }
        return SaveResult(saved, failed, sink.label)
    }

    /** Human label for a SAF tree such as `content://.../tree/primary%3ADocuments%2FMeetings` -> `Documents/Meetings`. */
    fun treeLabel(treeDocumentId: String): String =
        treeDocumentId.substringAfter(':', treeDocumentId).ifBlank { treeDocumentId.substringBefore(':') }
}

/** Public Downloads/AI会记 folder through MediaStore; visible in the system Files app, no permission needed. */
class DownloadsSink(private val context: Context, private val folder: String = FOLDER) : FileSink {
    override val label get() = "Download/$folder"

    override fun create(name: String, mime: String): OutputStream {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$folder")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore refused $name")
        return context.contentResolver.openOutputStream(uri) ?: error("cannot open $uri")
    }

    companion object {
        const val FOLDER = "AI会记"
    }
}

/** A folder the user picked with the system folder picker (Storage Access Framework). */
class TreeSink(private val context: Context, private val tree: Uri) : FileSink {
    private val treeId = DocumentsContract.getTreeDocumentId(tree)
    override val label get() = MinutesSaver.treeLabel(treeId)

    override fun create(name: String, mime: String): OutputStream {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
        val doc = DocumentsContract.createDocument(context.contentResolver, parent, mime, name) ?: error("cannot create $name")
        return context.contentResolver.openOutputStream(doc) ?: error("cannot open $doc")
    }
}

/** Remembers the folder the user picked so the next meeting saves with one tap. */
class SaveLocationStore(context: Context) {
    private val prefs = context.getSharedPreferences("minutes_save", Context.MODE_PRIVATE)
    var tree: Uri?
        get() = prefs.getString(KEY, null)?.let(Uri::parse)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY) else putString(KEY, value.toString()) }.apply()

    private companion object {
        const val KEY = "tree"
    }
}
