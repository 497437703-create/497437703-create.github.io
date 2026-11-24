package com.aihuiji.meeting.ui.summary

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aihuiji.meeting.R
import com.aihuiji.meeting.audio.MeetingPlayer
import com.aihuiji.meeting.container
import com.aihuiji.meeting.core.model.CardType
import com.aihuiji.meeting.core.model.MeetingMode
import com.aihuiji.meeting.core.model.MeetingRecord
import com.aihuiji.meeting.core.model.MeetingState
import com.aihuiji.meeting.core.model.SummaryCard
import com.aihuiji.meeting.core.model.SummaryItem
import com.aihuiji.meeting.core.model.Templates
import com.aihuiji.meeting.core.model.formatClock
import com.aihuiji.meeting.export.ExportFormat
import com.aihuiji.meeting.ui.components.Chip
import com.aihuiji.meeting.ui.components.IconCircleButton
import com.aihuiji.meeting.ui.components.PrimaryButton
import com.aihuiji.meeting.ui.components.SecondaryButton
import com.aihuiji.meeting.ui.components.SpeakerAvatar
import com.aihuiji.meeting.ui.components.StatusPill
import com.aihuiji.meeting.ui.components.ToolAction
import com.aihuiji.meeting.ui.components.Waveform
import com.aihuiji.meeting.ui.components.glass
import com.aihuiji.meeting.ui.components.pressable
import com.aihuiji.meeting.ui.labels.Labels
import com.aihuiji.meeting.ui.meeting.ShareQrDialog
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SummaryTab { SUMMARY, TODOS, NOTES, TRANSCRIPT, STATS }

private sealed interface SummaryOverlay {
    data class Trace(val item: SummaryItem, val title: String) : SummaryOverlay
    data object Template : SummaryOverlay
    data object Distribute : SummaryOverlay
    data object Export : SummaryOverlay
}

/** S18 summary workspace (S10 content, S10b transcript, S18a template, S18b trace, S18c distribute). */
@Composable
fun SummaryScreen(meetingId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val container = ctx.container
    val meetings by container.repository.meetings.collectAsStateWithLifecycle()
    val generation by container.meetingController.generation.collectAsStateWithLifecycle()
    val record = meetings.firstOrNull { it.id == meetingId }
    if (record == null) {
        Box(Modifier.fillMaxSize().background(Palette.Bg0), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.summary_not_found), style = Type.TitleM)
                Spacer(Modifier.height(24.dp))
                SecondaryButton(stringResource(R.string.action_back), onBack)
            }
        }
        return
    }
    val player = remember(record.id, record.audioFiles) { MeetingPlayer(record.audioFiles.values) }
    DisposableEffect(player) { onDispose { player.release() } }

    var tab by remember { mutableStateOf(SummaryTab.SUMMARY) }
    var overlay by remember { mutableStateOf<SummaryOverlay?>(null) }
    var focusSegment by remember { mutableStateOf<String?>(null) }
    val regenerating = generation?.takeIf { it.meetingId == meetingId && !it.done && it.error == null }

    BackHandler { if (overlay != null) overlay = null else onBack() }

    val locate: (String) -> Unit = { segId ->
        focusSegment = segId
        tab = SummaryTab.TRANSCRIPT
        overlay = null
    }

    Box(Modifier.fillMaxSize().background(Palette.Bg0)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
            SummaryTopBar(record, onBack, onTemplate = { overlay = SummaryOverlay.Template }, onDistribute = { overlay = SummaryOverlay.Distribute }, onExport = { overlay = SummaryOverlay.Export })
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                SummaryTab.entries.forEach { t ->
                    Chip(stringResource(tabLabel(t)), tab == t, { tab = t }, height = 64.dp)
                }
                Spacer(Modifier.weight(1f))
                if (regenerating != null) {
                    StatusPill(stringResource(R.string.regenerating, (regenerating.progress * 100).toInt()), Palette.Brand400)
                }
            }
            Spacer(Modifier.height(20.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    SummaryTab.SUMMARY -> SummaryTabContent(record) { item, title -> overlay = SummaryOverlay.Trace(item, title) }
                    SummaryTab.TODOS -> TodosTab(record) { item, title -> overlay = SummaryOverlay.Trace(item, title) }
                    SummaryTab.NOTES -> NotesTab(record, onLocate = locate, onSeek = { ms -> tab = SummaryTab.TRANSCRIPT; player.play(ms) })
                    SummaryTab.TRANSCRIPT -> TranscriptTab(record, player, focusSegment)
                    SummaryTab.STATS -> StatsTab(record)
                }
            }
        }

        AnimatedVisibility(
            visible = overlay is SummaryOverlay.Trace,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            (overlay as? SummaryOverlay.Trace)?.let { t ->
                TraceDrawer(record, t.item, t.title, player, onLocate = locate, onClose = { overlay = null })
            }
        }
        when (overlay) {
            SummaryOverlay.Template -> TemplatePicker(record.templateId, onPick = { id ->
                overlay = null
                if (id != record.templateId) container.meetingController.regenerate(record.id, id)
            }, onClose = { overlay = null })
            SummaryOverlay.Distribute -> {
                val share = record.share
                ShareQrDialog(
                    url = share?.url ?: "aihuiji://summary/${record.id}",
                    code = record.code,
                    expiresAt = share?.expiresAtEpochMs,
                    title = stringResource(R.string.share_summary_hint),
                    onClose = { overlay = null },
                )
            }
            SummaryOverlay.Export -> ExportMenu(onPick = { format ->
                val file = container.exporter.export(record, format)
                ctx.startActivity(container.exporter.shareIntent(file, format))
                overlay = null
            }, onClose = { overlay = null })
            else -> Unit
        }
    }
}

private fun tabLabel(t: SummaryTab) = when (t) {
    SummaryTab.SUMMARY -> R.string.tab_summary
    SummaryTab.TODOS -> R.string.tab_todos
    SummaryTab.NOTES -> R.string.tab_notes
    SummaryTab.TRANSCRIPT -> R.string.tab_transcript
    SummaryTab.STATS -> R.string.tab_stats
}

@Composable
private fun SummaryTopBar(record: MeetingRecord, onBack: () -> Unit, onTemplate: () -> Unit, onDistribute: () -> Unit, onExport: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconCircleButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back), onBack, size = 80.dp)
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(record.title, style = Type.TitleL, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(record.startedAtEpochMs))
            Text(
                stringResource(
                    R.string.summary_meta,
                    date,
                    formatClock(record.durationMs),
                    record.speakers.size,
                    stringResource(if (record.mode == MeetingMode.VIDEO) R.string.mode_video else R.string.mode_inroom),
                ),
                style = Type.Caption,
            )
        }
        Row(
            Modifier.height(80.dp).glass(40.dp).pressable(onClick = onTemplate).padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Labels.templateIcon(record.templateId), null, tint = Palette.Brand400, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.template_value, stringResource(Labels.template(record.templateId))), style = Type.Label)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.ExpandMore, null, tint = Palette.Text2)
        }
        Spacer(Modifier.width(16.dp))
        ToolAction(Icons.Outlined.Send, stringResource(R.string.action_distribute), onDistribute)
        Spacer(Modifier.width(16.dp))
        ToolAction(Icons.Outlined.FileDownload, stringResource(R.string.action_export), onExport)
    }
}

// ------------------------------------------------------------------ 总结

@Composable
private fun SummaryTabContent(record: MeetingRecord, onTrace: (SummaryItem, String) -> Unit) {
    val summary = record.summary
    if (summary == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(if (record.state == MeetingState.FAILED) R.string.summary_failed else R.string.summary_pending), style = Type.TitleM, color = Palette.Text2)
        }
        return
    }
    val overviewTitle = stringResource(R.string.summary_overview)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().glass(24.dp).padding(28.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(overviewTitle, style = Type.Label, color = Palette.Brand400)
                Spacer(Modifier.height(8.dp))
                Text(summary.overview.text, style = Type.Body)
            }
            Spacer(Modifier.width(16.dp))
            TraceButton { onTrace(summary.overview, overviewTitle) }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            summary.cards.forEach { card ->
                SummaryCardView(card, Modifier.weight(1f).fillMaxHeight(), onTrace)
            }
        }
    }
}

@Composable
private fun SummaryCardView(card: SummaryCard, modifier: Modifier, onTrace: (SummaryItem, String) -> Unit) {
    val title = stringResource(Labels.card(card.type))
    val accent = Labels.cardAccent(card.type)
    Column(modifier.glass(24.dp).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Labels.cardIcon(card.type), null, tint = accent, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(title, style = Type.TitleM)
            Spacer(Modifier.weight(1f))
            Text("${card.items.size}", style = Type.Label, color = Palette.Text2)
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (card.items.isEmpty()) item { Text(stringResource(R.string.card_empty), style = Type.Caption) }
            items(card.items, key = { it.id }) { item ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Bg2.copy(alpha = 0.6f)).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 12.dp).size(10.dp).clip(CircleShape).background(accent))
                        Spacer(Modifier.width(12.dp))
                        Text(item.text, style = Type.Body.copy(fontSize = 24.sp, lineHeight = 34.sp), modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        item.owner?.let { StatusPill(it, accent); Spacer(Modifier.width(10.dp)) }
                        if (item.inferred) StatusPill(stringResource(R.string.inferred), Palette.Warning)
                        item.sourceRefs.firstOrNull()?.let { Text(formatClock(it.startMs), style = Type.Caption.copy(fontFeatureSettings = "tnum")) }
                        Spacer(Modifier.weight(1f))
                        TraceButton { onTrace(item, title) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceButton(onClick: () -> Unit) {
    Row(
        Modifier.height(64.dp).clip(CircleShape).background(Palette.Brand700.copy(alpha = 0.35f)).pressable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.GpsFixed, null, tint = Palette.TextTrans, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.action_trace), style = Type.Caption, color = Palette.Text1)
    }
}

// ------------------------------------------------------------------ 待办

@Composable
private fun TodosTab(record: MeetingRecord, onTrace: (SummaryItem, String) -> Unit) {
    val todos = record.summary?.cards?.filter { it.type == CardType.TODOS }?.flatMap { it.items }.orEmpty()
    val done = remember { mutableStateMapOf<String, Boolean>() }
    val title = stringResource(R.string.card_todos)
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (todos.isEmpty()) item { Text(stringResource(R.string.card_empty), style = Type.Body, color = Palette.Text2) }
        items(todos, key = { it.id }) { item ->
            Row(Modifier.fillMaxWidth().glass(24.dp).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                val checked = done[item.id] == true
                IconCircleButton(
                    if (checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                    stringResource(R.string.tab_todos),
                    { done[item.id] = !checked },
                    size = 72.dp,
                    selected = checked,
                )
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.text, style = Type.Body, color = if (checked) Palette.Text2 else Palette.Text1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        item.owner?.let { Text(stringResource(R.string.todo_owner, it), style = Type.Caption); Spacer(Modifier.width(16.dp)) }
                        item.sourceRefs.firstOrNull()?.let { Text(formatClock(it.startMs), style = Type.Caption) }
                    }
                }
                TraceButton { onTrace(item, title) }
            }
        }
    }
}

// ------------------------------------------------------------------ 笔记与标记

@Composable
private fun NotesTab(record: MeetingRecord, onLocate: (String) -> Unit, onSeek: (Long) -> Unit) {
    data class Row_(val atMs: Long, val tag: com.aihuiji.meeting.core.model.MarkTag?, val text: String?, val speakerId: String?, val segmentId: String?)
    val noteSegs = record.notes.mapNotNull { it.segmentId }.toSet()
    val rows = (record.notes.map { Row_(it.atMs, it.tag, it.text, it.speakerId, it.segmentId) } +
        record.marks.filter { it.segmentId == null || it.segmentId !in noteSegs }.map { m ->
            Row_(m.atMs, m.tag, record.segments.firstOrNull { it.id == m.segmentId }?.text, null, m.segmentId)
        }).sortedBy { it.atMs }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (rows.isEmpty()) item { Text(stringResource(R.string.notes_empty_after), style = Type.Body, color = Palette.Text2) }
        items(rows) { r ->
            Row(
                Modifier.fillMaxWidth().glass(24.dp).pressable { r.segmentId?.let(onLocate) ?: onSeek(r.atMs) }.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(6.dp).height(64.dp).clip(RoundedCornerShape(3.dp)).background(r.tag?.let(Labels::tagColor) ?: Palette.Text2))
                Spacer(Modifier.width(20.dp))
                Text(formatClock(r.atMs), style = Type.Label.copy(fontFeatureSettings = "tnum"), color = Palette.Text2)
                Spacer(Modifier.width(20.dp))
                r.tag?.let { StatusPill(stringResource(Labels.tag(it)), Labels.tagColor(it), icon = Labels.tagIcon(it)); Spacer(Modifier.width(16.dp)) }
                Text(r.text ?: stringResource(R.string.mark_no_text), style = Type.Body, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.GpsFixed, null, tint = Palette.Text2)
            }
        }
    }
}

// ------------------------------------------------------------------ 发言统计

@Composable
private fun StatsTab(record: MeetingRecord) {
    val segs = record.finalSegments
    val total = segs.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) }.coerceAtLeast(1)
    val bySpeaker = segs.groupBy { it.speakerId }.map { (id, list) -> Triple(record.speaker(id), list.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) }, list.size) }
        .sortedByDescending { it.second }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        bySpeaker.forEach { (sp, ms, count) ->
            Row(Modifier.fillMaxWidth().glass(24.dp).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                SpeakerAvatar(sp, size = 64.dp, showChannelBadge = record.dualChannel)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.width(280.dp)) {
                    Text(Labels.speakerName(sp), style = Type.Label)
                    Text(stringResource(R.string.stats_detail, formatClock(ms), count), style = Type.Caption)
                }
                Box(Modifier.weight(1f).height(28.dp).clip(CircleShape).background(Palette.Bg2)) {
                    Box(Modifier.fillMaxWidth(ms.toFloat() / total).fillMaxHeight().clip(CircleShape).background(Palette.speaker(sp?.colorIndex ?: 0)))
                }
                Spacer(Modifier.width(20.dp))
                Text("${ms * 100 / total}%", style = Type.TitleM, modifier = Modifier.width(120.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ S18b trace drawer

@Composable
private fun TraceDrawer(
    record: MeetingRecord,
    item: SummaryItem,
    title: String,
    player: MeetingPlayer,
    onLocate: (String) -> Unit,
    onClose: () -> Unit,
) {
    val segs = record.finalSegments
    val ref = item.sourceRefs.firstOrNull()
    val hitIndex = ref?.let { r -> segs.indexOfFirst { it.id == r.segmentId } } ?: -1
    val playing by player.playing.collectAsStateWithLifecycle()
    Column(
        Modifier
            .width(820.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(topStart = 40.dp, bottomStart = 40.dp))
            .background(Palette.Bg2)
            .border(1.dp, Palette.Stroke, RoundedCornerShape(topStart = 40.dp, bottomStart = 40.dp))
            .padding(40.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.GpsFixed, null, tint = Palette.Brand400, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.trace_title), style = Type.TitleL)
            Spacer(Modifier.weight(1f))
            IconCircleButton(Icons.Outlined.Close, stringResource(R.string.action_close), onClose, size = 72.dp)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = Type.Caption, color = Palette.Brand400)
        Text(item.text, style = Type.Body.copy(fontWeight = FontWeight.Medium))
        Spacer(Modifier.height(24.dp))
        if (hitIndex < 0) {
            Text(stringResource(R.string.trace_inferred), style = Type.Body, color = Palette.Warning)
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            (hitIndex - 2..hitIndex + 2).filter { it in segs.indices }.forEach { i ->
                val s = segs[i]
                val hit = i == hitIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (hit) Palette.Brand700.copy(alpha = 0.35f) else Palette.Bg1.copy(alpha = 0.6f))
                        .padding(16.dp),
                ) {
                    Text(
                        stringResource(if (hit) R.string.trace_hit else if (i < hitIndex) R.string.trace_before else R.string.trace_after),
                        style = Type.Caption,
                        color = if (hit) Palette.TextTrans else Palette.Text2,
                        modifier = Modifier.width(64.dp),
                    )
                    SpeakerAvatar(record.speaker(s.speakerId), size = 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("${Labels.speakerName(record.speaker(s.speakerId))} · ${formatClock(s.startMs)}", style = Type.Caption)
                        Text(s.text, style = Type.Body.copy(fontSize = 24.sp, lineHeight = 34.sp), color = if (hit) Palette.Text1 else Palette.Text2)
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        val hit = segs[hitIndex]
        val from = (hit.startMs - 3_000).coerceAtLeast(0)
        val to = hit.endMs + 3_000
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (player.available) {
                PrimaryButton(
                    stringResource(R.string.trace_play, formatClock(from), formatClock(to)),
                    { if (playing) player.pause() else player.play(from, to) },
                    icon = Icons.Outlined.PlayArrow,
                    height = 80.dp,
                )
                Spacer(Modifier.width(16.dp))
                Waveform(level = if (playing) 0.8f else 0.2f, maxHeight = 56.dp, animate = playing)
            } else {
                Text(stringResource(R.string.trace_no_audio), style = Type.Caption)
            }
        }
        Spacer(Modifier.height(16.dp))
        SecondaryButton(stringResource(R.string.trace_locate), { onLocate(hit.id) }, height = 80.dp)
    }
}

// ------------------------------------------------------------------ S18a template picker / export

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplatePicker(current: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.Bg0.copy(alpha = 0.6f)).pressable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(Modifier.width(1280.dp).clip(RoundedCornerShape(32.dp)).background(Palette.Bg2).pressable(onClick = {}).padding(40.dp)) {
            Text(stringResource(R.string.template_picker_title), style = Type.TitleL)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.template_picker_hint), style = Type.Caption)
            Spacer(Modifier.height(24.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Templates.all.forEach { t ->
                    val selected = t.id == current
                    Column(
                        Modifier
                            .width(280.dp)
                            .height(200.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .then(if (selected) Modifier.background(Palette.BrandGradient) else Modifier.glass(24.dp))
                            .pressable { onPick(t.id) }
                            .padding(24.dp),
                    ) {
                        Icon(Labels.templateIcon(t.id), null, tint = Palette.Text1, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(Labels.template(t.id)), style = Type.TitleM)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            t.cards.map { stringResource(Labels.card(it)) }.joinToString(" · "),
                            style = Type.Caption,
                            color = if (selected) Palette.Text1 else Palette.Text2,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportMenu(onPick: (ExportFormat) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.Bg0.copy(alpha = 0.6f)).pressable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(Modifier.width(720.dp).clip(RoundedCornerShape(32.dp)).background(Palette.Bg2).pressable(onClick = {}).padding(40.dp)) {
            Text(stringResource(R.string.export_title), style = Type.TitleL)
            Spacer(Modifier.height(24.dp))
            ExportOption(Icons.Outlined.PictureAsPdf, "PDF", stringResource(R.string.export_pdf_hint)) { onPick(ExportFormat.PDF) }
            Spacer(Modifier.height(16.dp))
            ExportOption(Icons.Outlined.Description, "Markdown", stringResource(R.string.export_md_hint)) { onPick(ExportFormat.MARKDOWN) }
            Spacer(Modifier.height(16.dp))
            ExportOption(Icons.Outlined.TextSnippet, stringResource(R.string.export_txt), stringResource(R.string.export_txt_hint)) { onPick(ExportFormat.TEXT) }
        }
    }
}

@Composable
private fun ExportOption(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(104.dp).glass(24.dp).pressable(onClick = onClick).padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Palette.Brand400, modifier = Modifier.size(44.dp))
        Spacer(Modifier.width(20.dp))
        Column {
            Text(title, style = Type.Label)
            Text(hint, style = Type.Caption)
        }
    }
}