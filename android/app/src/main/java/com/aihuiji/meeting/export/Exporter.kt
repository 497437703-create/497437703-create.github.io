package com.aihuiji.meeting.export

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.aihuiji.meeting.R
import com.aihuiji.meeting.core.model.MeetingRecord
import com.aihuiji.meeting.core.model.formatClock
import com.aihuiji.meeting.ui.labels.Labels
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val ext: String, val mime: String) {
    PDF("pdf", "application/pdf"),
    MARKDOWN("md", "text/markdown"),
    TEXT("txt", "text/plain"),
}

/** F-13 M1 exports: PDF + plain text / Markdown, shared through the system share sheet. */
class Exporter(private val context: Context) {
    private val dir get() = File(context.filesDir, "exports").apply { mkdirs() }

    fun markdown(record: MeetingRecord): String = buildString {
        val res = context.resources
        appendLine("# ${record.title}")
        appendLine()
        appendLine("- ${res.getString(R.string.export_time)}: ${dateFormat.format(Date(record.startedAtEpochMs))}")
        appendLine("- ${res.getString(R.string.export_duration)}: ${formatClock(record.durationMs)}")
        appendLine("- ${res.getString(R.string.export_code)}: ${record.code}")
        record.share?.let { appendLine("- ${res.getString(R.string.export_link)}: ${it.url}") }
        appendLine()
        record.summary?.let { s ->
            appendLine("## ${res.getString(R.string.summary_overview)}")
            appendLine(s.overview.text)
            appendLine()
            s.cards.forEach { card ->
                appendLine("## ${res.getString(Labels.card(card.type))}")
                if (card.items.isEmpty()) appendLine("- —")
                card.items.forEach { item ->
                    val owner = item.owner?.let { " (${it})" }.orEmpty()
                    val at = item.sourceRefs.firstOrNull()?.let { " [${formatClock(it.startMs)}]" }.orEmpty()
                    appendLine("- ${item.text}$owner$at")
                }
                appendLine()
            }
        }
        if (record.notes.isNotEmpty() || record.marks.isNotEmpty()) {
            appendLine("## ${res.getString(R.string.tab_notes)}")
            record.notes.forEach { n ->
                val tag = n.tag?.let { "【${res.getString(Labels.tag(it))}】" }.orEmpty()
                appendLine("- ${formatClock(n.atMs)} $tag${n.text}")
            }
            record.marks.filter { m -> record.notes.none { it.segmentId == m.segmentId && m.segmentId != null } }.forEach { m ->
                appendLine("- ${formatClock(m.atMs)} 【${res.getString(Labels.tag(m.tag))}】")
            }
            appendLine()
        }
        appendLine("## ${res.getString(R.string.tab_transcript)}")
        record.finalSegments.forEach { seg ->
            val sp = record.speaker(seg.speakerId)
            appendLine("**${Labels.speakerName(context, sp)}** ${formatClock(seg.startMs)}  ")
            appendLine(seg.text + "  ")
            seg.translations.forEach { (lang, t) -> appendLine("> ${lang.tag} $t  ") }
            appendLine()
        }
    }

    fun text(record: MeetingRecord): String =
        markdown(record).lines().joinToString("\n") { it.removePrefix("## ").removePrefix("# ").replace("**", "").trimEnd() }

    fun export(record: MeetingRecord, format: ExportFormat): File {
        val base = "${record.code}-${fileDate.format(Date(record.startedAtEpochMs))}"
        val file = File(dir, "$base.${format.ext}")
        when (format) {
            ExportFormat.MARKDOWN -> file.writeText(markdown(record))
            ExportFormat.TEXT -> file.writeText(text(record))
            ExportFormat.PDF -> writePdf(record, file)
        }
        return file
    }

    fun shareIntent(file: File, format: ExportFormat): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(format.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            context.getString(R.string.action_export),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun writePdf(record: MeetingRecord, file: File) {
        val pageW = 595
        val pageH = 842
        val margin = 48
        val doc = PdfDocument()
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; typeface = Typeface.DEFAULT }
        val heading = TextPaint(body).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD; color = 0xFF6B3AD4.toInt() }
        val title = TextPaint(heading).apply { textSize = 20f; color = 0xFF0E0A18.toInt() }
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        var y = margin.toFloat()
        fun draw(text: String, paint: TextPaint, spacing: Float = 6f) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, pageW - margin * 2)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()
            if (y + layout.height > pageH - margin) {
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
                y = margin.toFloat()
            }
            page.canvas.save()
            page.canvas.translate(margin.toFloat(), y)
            layout.draw(page.canvas)
            page.canvas.restore()
            y += layout.height + spacing
        }
        markdown(record).lines().forEach { line ->
            when {
                line.startsWith("# ") -> draw(line.removePrefix("# "), title, 12f)
                line.startsWith("## ") -> { y += 6; draw(line.removePrefix("## "), heading) }
                line.isBlank() -> y += 4
                else -> draw(line.replace("**", "").trimEnd(), body, 2f)
            }
        }
        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    private companion object {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        val fileDate = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
    }
}
