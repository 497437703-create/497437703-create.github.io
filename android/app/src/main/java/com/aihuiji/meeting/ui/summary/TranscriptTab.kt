package com.aihuiji.meeting.ui.summary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aihuiji.meeting.R
import com.aihuiji.meeting.audio.MeetingPlayer
import com.aihuiji.meeting.audio.WavWriter
import com.aihuiji.meeting.core.model.MeetingRecord
import com.aihuiji.meeting.core.model.formatClock
import com.aihuiji.meeting.ui.components.Chip
import com.aihuiji.meeting.ui.components.IconCircleButton
import com.aihuiji.meeting.ui.components.SpeakerAvatar
import com.aihuiji.meeting.ui.components.glass
import com.aihuiji.meeting.ui.components.pressable
import com.aihuiji.meeting.ui.labels.Labels
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** S10b full transcript with speaker filter, search, and the S19 waveform / marker timeline. */
@Composable
fun TranscriptTab(record: MeetingRecord, player: MeetingPlayer, focusSegmentId: String?) {
    var speakerFilter by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val segs = record.finalSegments.filter { (speakerFilter == null || it.speakerId == speakerFilter) && (query.isBlank() || it.text.contains(query, true) || it.translations.values.any { t -> t.contains(query, true) }) }
    val listState = rememberLazyListState()
    val position by player.position.collectAsStateWithLifecycle()
    val playing by player.playing.collectAsStateWithLifecycle()
    var focused by remember(focusSegmentId) { mutableStateOf(focusSegmentId) }

    LaunchedEffect(focusSegmentId) {
        val idx = segs.indexOfFirst { it.id == focusSegmentId }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }
    val playingSeg = if (playing) record.finalSegments.lastOrNull { it.startMs <= position }?.id else null

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Chip(stringResource(R.string.filter_all), speakerFilter == null, { speakerFilter = null }) }
                items(record.speakers) { sp ->
                    Chip(Labels.speakerName(sp), speakerFilter == sp.id, { speakerFilter = if (speakerFilter == sp.id) null else sp.id }, accent = Palette.speaker(sp.colorIndex))
                }
            }
            Spacer(Modifier.width(20.dp))
            Row(Modifier.width(520.dp).height(64.dp).glass(32.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, tint = Palette.Text2)
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = Type.Label.copy(color = Palette.Text1),
                    cursorBrush = SolidColor(Palette.Brand400),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text(stringResource(R.string.search_hint), style = Type.Label, color = Palette.Text2)
                        inner()
                    },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(segs, key = { it.id }) { s ->
                val sp = record.speaker(s.speakerId)
                val highlight = s.id == focused || s.id == playingSeg
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (highlight) Palette.Brand700.copy(alpha = 0.3f) else Palette.Bg1.copy(alpha = 0.6f))
                        .pressable {
                            focused = s.id
                            player.play(s.startMs)
                        }
                        .padding(20.dp),
                ) {
                    SpeakerAvatar(sp, size = 48.dp, showChannelBadge = record.dualChannel)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(Labels.speakerName(sp), style = Type.Label, color = Palette.speaker(sp?.colorIndex ?: 0))
                            Spacer(Modifier.width(16.dp))
                            Text(formatClock(s.startMs), style = Type.Caption.copy(fontFeatureSettings = "tnum"))
                            record.marks.filter { it.segmentId == s.id }.forEach { m ->
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(Labels.tag(m.tag)), style = Type.Caption, color = Labels.tagColor(m.tag))
                            }
                        }
                        Text(highlightQuery(s.text, query), style = Type.Body.copy(fontSize = 26.sp, lineHeight = 38.sp))
                        s.translations.forEach { (lang, t) ->
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(fontSize = 16.sp, color = Palette.TextTrans.copy(alpha = 0.7f))) { append(lang.tag + "  ") }
                                    append(t)
                                },
                                style = Type.Body.copy(fontSize = 22.sp, lineHeight = 32.sp),
                                color = Palette.TextTrans,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Timeline(record, player, position, playing)
    }
}

private fun highlightQuery(text: String, q: String) = buildAnnotatedString {
    if (q.isBlank()) { append(text); return@buildAnnotatedString }
    var i = 0
    while (i < text.length) {
        val hit = text.indexOf(q, i, ignoreCase = true)
        if (hit < 0) { append(text.substring(i)); break }
        append(text.substring(i, hit))
        withStyle(SpanStyle(background = Palette.Brand500.copy(alpha = 0.5f))) { append(text.substring(hit, hit + q.length)) }
        i = hit + q.length
    }
}

/**
 * S19 timeline: speaker activity blocks, recorded-audio envelope, mark dots, draggable playhead.
 */
@Composable
private fun Timeline(record: MeetingRecord, player: MeetingPlayer, position: Long, playing: Boolean) {
    val duration = maxOf(record.durationMs, player.durationMs, 1L)
    val envelope by produceState(FloatArray(0), record.audioFiles) {
        value = withContext(Dispatchers.IO) {
            record.audioFiles.values.map { WavWriter.envelope(File(it), 600) }.reduceOrNull { a, b ->
                FloatArray(maxOf(a.size, b.size)) { i -> maxOf(a.getOrElse(i) { 0f }, b.getOrElse(i) { 0f }) }
            } ?: FloatArray(0)
        }
    }
    Row(Modifier.fillMaxWidth().height(140.dp).glass(24.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        IconCircleButton(
            if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            stringResource(if (playing) R.string.action_pause else R.string.action_play),
            { if (playing) player.pause() else player.play() },
            size = 80.dp,
            selected = playing,
        )
        Spacer(Modifier.width(20.dp))
        Text(formatClock(position), style = Type.Label.copy(fontFeatureSettings = "tnum"))
        Spacer(Modifier.width(20.dp))
        Box(Modifier.weight(1f).height(96.dp)) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(duration) {
                        detectTapGestures { o -> player.seek((o.x / size.width * duration).toLong()) }
                    }
                    .pointerInput(duration) {
                        detectHorizontalDragGestures { change, _ -> player.seek((change.position.x / size.width * duration).toLong().coerceIn(0, duration)) }
                    },
            ) {
                val w = size.width
                val h = size.height
                val trackTop = h * 0.3f
                val trackH = h * 0.6f
                drawRoundRect(Palette.Bg2, Offset(0f, trackTop), Size(w, trackH), CornerRadius(12f))
                record.finalSegments.forEach { s ->
                    val x0 = s.startMs.toFloat() / duration * w
                    val x1 = (s.endMs.toFloat() / duration * w).coerceAtLeast(x0 + 3f)
                    val c = Palette.speaker(record.speaker(s.speakerId)?.colorIndex ?: 0)
                    drawRoundRect(c.copy(alpha = 0.55f), Offset(x0, trackTop + trackH * 0.15f), Size(x1 - x0, trackH * 0.7f), CornerRadius(6f))
                }
                if (envelope.isNotEmpty()) {
                    val bw = w / envelope.size
                    envelope.forEachIndexed { i, v ->
                        val bh = (v * trackH).coerceAtLeast(1f)
                        drawRect(Palette.Text1.copy(alpha = 0.55f), Offset(i * bw, trackTop + (trackH - bh) / 2), Size(bw * 0.6f, bh))
                    }
                }
                record.marks.forEach { m ->
                    val x = m.atMs.toFloat() / duration * w
                    drawCircle(Labels.tagColor(m.tag), radius = 9f, center = Offset(x, trackTop * 0.45f))
                }
                record.notes.forEach { n ->
                    val x = n.atMs.toFloat() / duration * w
                    drawCircle(Palette.Brand400, radius = 6f, center = Offset(x, trackTop * 0.45f))
                }
                val px = position.toFloat() / duration * w
                drawRect(Palette.Text1, Offset(px - 1.5f, 0f), Size(3f, h))
            }
        }
        Spacer(Modifier.width(20.dp))
        Text(formatClock(duration), style = Type.Caption.copy(fontFeatureSettings = "tnum"))
    }
}
