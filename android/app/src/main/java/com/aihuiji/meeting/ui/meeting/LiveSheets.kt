package com.aihuiji.meeting.ui.meeting

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.SignalWifiConnectedNoInternet4
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aihuiji.meeting.R
import com.aihuiji.meeting.core.model.CaptionSize
import com.aihuiji.meeting.core.model.Lang
import com.aihuiji.meeting.core.model.MarkTag
import com.aihuiji.meeting.core.model.MeetingMode
import com.aihuiji.meeting.core.model.PanelLayout
import com.aihuiji.meeting.core.model.TranslationDisplay
import com.aihuiji.meeting.core.model.formatClock
import com.aihuiji.meeting.core.provider.ProviderLocation
import com.aihuiji.meeting.share.QrImage
import com.aihuiji.meeting.ui.components.BigSwitch
import com.aihuiji.meeting.ui.components.Chip
import com.aihuiji.meeting.ui.components.IconCircleButton
import com.aihuiji.meeting.ui.components.SecondaryButton
import com.aihuiji.meeting.ui.components.ToolAction
import com.aihuiji.meeting.ui.components.glass
import com.aihuiji.meeting.ui.components.pressable
import com.aihuiji.meeting.ui.labels.Labels
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ S17 speed notes

@Composable
fun NotesPanel(model: LiveUiModel, modifier: Modifier = Modifier, onClose: (() -> Unit)?) {
    var draft by remember { mutableStateOf("") }
    var draftTag by remember { mutableStateOf<MarkTag?>(null) }
    val record = model.record
    val entries = remember(record.notes, record.marks) {
        val noteSegs = record.notes.mapNotNull { it.segmentId }.toSet()
        (record.notes.map { NoteEntry(it.atMs, it.tag, it.text, it.speakerId, it.id) } +
            record.marks.filter { it.segmentId == null || it.segmentId !in noteSegs }.map { m ->
                NoteEntry(m.atMs, m.tag, record.segments.firstOrNull { it.id == m.segmentId }?.text, null, null)
            }).sortedBy { it.atMs }
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.notes_title), style = Type.TitleM)
            Spacer(Modifier.weight(1f))
            if (onClose != null) IconCircleButton(Icons.Outlined.Close, stringResource(R.string.action_close), onClose, size = 64.dp)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (entries.isEmpty()) item {
                Text(stringResource(R.string.notes_empty), style = Type.Body.copy(fontSize = 24.sp), color = Palette.Text2)
            }
            items(entries) { e ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Bg2.copy(alpha = 0.7f)).padding(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.width(4.dp).height(56.dp).clip(RoundedCornerShape(2.dp)).background(e.tag?.let(Labels::tagColor) ?: Palette.Text2))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            e.tag?.let { Text(stringResource(Labels.tag(it)), style = Type.Caption, color = Labels.tagColor(it)); Spacer(Modifier.width(10.dp)) }
                            e.speakerId?.let { Text(Labels.speakerName(model.speakers[it]), style = Type.Caption); Spacer(Modifier.width(10.dp)) }
                            Text(formatClock(e.atMs), style = Type.Caption)
                        }
                        e.text?.let { Text(it, style = Type.Body.copy(fontSize = 24.sp, lineHeight = 34.sp)) }
                    }
                    if (e.noteId != null) {
                        IconCircleButton(Icons.Outlined.DeleteOutline, stringResource(R.string.action_delete), { model.controller.removeNote(e.noteId) }, size = 64.dp)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkTag.entries.forEach { tag ->
                Chip(stringResource(Labels.tag(tag)), draftTag == tag, { draftTag = if (draftTag == tag) null else tag }, height = 48.dp, accent = Labels.tagColor(tag))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = Type.Body.copy(fontSize = 26.sp, color = Palette.Text1),
                cursorBrush = SolidColor(Palette.Brand400),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    model.controller.addNote(draft, draftTag); draft = ""
                }),
                modifier = Modifier.weight(1f).height(72.dp).glass(16.dp).padding(horizontal = 20.dp, vertical = 18.dp),
                decorationBox = { inner ->
                    if (draft.isEmpty()) Text(stringResource(R.string.note_input_hint), style = Type.Body.copy(fontSize = 24.sp), color = Palette.Text2)
                    inner()
                },
            )
            Spacer(Modifier.width(12.dp))
            IconCircleButton(Icons.Outlined.Add, stringResource(R.string.action_add_note), {
                model.controller.addNote(draft, draftTag); draft = ""
            }, size = 72.dp, selected = draft.isNotBlank())
        }
    }
}

private data class NoteEntry(val atMs: Long, val tag: MarkTag?, val text: String?, val speakerId: String?, val noteId: String?)

// ------------------------------------------------------------------ S06 more panel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoreSheet(model: LiveUiModel, onClose: () -> Unit, onShare: () -> Unit) {
    val live = model.live
    val s = model.settings
    Box(Modifier.fillMaxSize().background(Palette.Bg0.copy(alpha = 0.5f)).pressable(onClick = onClose), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp)
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(Palette.Bg2)
                .border(1.dp, Palette.Stroke, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .pressable(onClick = {})
                .padding(horizontal = 48.dp, vertical = 28.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(64.dp, 6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Text2.copy(alpha = 0.5f)))
            Spacer(Modifier.height(24.dp))
            SheetRow(stringResource(R.string.more_translation)) {
                BigSwitch(live.translationEnabled, { model.controller.setTranslation(it, live.targetLangs.ifEmpty { listOf(defaultTarget(live.record.primaryLang)) }) })
                Spacer(Modifier.width(24.dp))
                Text(stringResource(R.string.more_target_lang), style = Type.Caption)
                Spacer(Modifier.width(12.dp))
                Lang.entries.filter { it != live.record.primaryLang }.forEach { lang ->
                    val selected = lang in live.targetLangs
                    Chip(lang.nativeName, selected, {
                        val next = if (selected) live.targetLangs - lang else (live.targetLangs + lang).takeLast(2)
                        model.controller.setTranslation(live.translationEnabled || !selected, next)
                    })
                    Spacer(Modifier.width(10.dp))
                }
            }
            SheetRow(stringResource(R.string.more_display)) {
                TranslationDisplay.entries.forEach { d ->
                    Chip(stringResource(Labels.display(d)), s.translationDisplay == d, { model.updateSettings { it.copy(translationDisplay = d) } })
                    Spacer(Modifier.width(10.dp))
                }
            }
            SheetRow(stringResource(R.string.more_font_size)) {
                CaptionSize.entries.forEach { size ->
                    Chip(stringResource(Labels.size(size)), s.captionSize == size, { model.updateSettings { it.copy(captionSize = size) } })
                    Spacer(Modifier.width(10.dp))
                }
                Spacer(Modifier.width(32.dp))
                Text(stringResource(R.string.more_avatars), style = Type.Caption)
                Spacer(Modifier.width(12.dp))
                BigSwitch(s.showAvatars, { v -> model.updateSettings { it.copy(showAvatars = v) } })
                Spacer(Modifier.width(24.dp))
                Text(stringResource(R.string.more_timestamps), style = Type.Caption)
                Spacer(Modifier.width(12.dp))
                BigSwitch(s.showTimestamps, { v -> model.updateSettings { it.copy(showTimestamps = v) } })
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ToolAction(Icons.Outlined.QrCode2, stringResource(R.string.action_share_qr), onShare)
                val toVideo = live.presentation == MeetingMode.IN_ROOM
                ToolAction(
                    if (toVideo) Icons.Outlined.Videocam else Icons.Outlined.Groups,
                    stringResource(if (toVideo) R.string.action_to_video_layout else R.string.action_to_inroom_layout),
                    { model.controller.setPresentation(if (toVideo) MeetingMode.VIDEO else MeetingMode.IN_ROOM); onClose() },
                )
                if (live.presentation == MeetingMode.VIDEO) {
                    Chip(stringResource(R.string.layout_split), live.layout == PanelLayout.SPLIT, { model.controller.setLayout(PanelLayout.SPLIT) }, height = 88.dp)
                    Chip(stringResource(R.string.layout_floating), live.layout == PanelLayout.FLOATING, { model.controller.setLayout(PanelLayout.FLOATING) }, height = 88.dp)
                }
                ToolAction(Icons.Outlined.GraphicEq, stringResource(R.string.action_detect_audio), { model.controller.retryMic(); onClose() })
                if (live.location == ProviderLocation.DEMO) {
                    ToolAction(Icons.Outlined.SignalWifiConnectedNoInternet4, stringResource(R.string.action_simulate_outage), { model.controller.simulateOutage(); onClose() })
                }
            }
        }
    }
}

private fun defaultTarget(primary: Lang) = if (primary == Lang.EN) Lang.ZH else Lang.EN

@Composable
private fun SheetRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(88.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.Label, modifier = Modifier.width(180.dp))
        content()
    }
}

// ------------------------------------------------------------------ S07 share QR

@Composable
fun ShareQrDialog(url: String, code: String, onClose: () -> Unit, expiresAt: Long? = null, title: String = stringResource(R.string.share_live_hint)) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().background(Palette.Bg0.copy(alpha = 0.75f)).pressable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(600.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Palette.Bg2)
                .border(1.dp, Palette.Stroke, RoundedCornerShape(32.dp))
                .pressable(onClick = {})
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                IconCircleButton(Icons.Outlined.Close, stringResource(R.string.action_close), onClose, size = 64.dp)
            }
            Box(Modifier.size(360.dp).clip(RoundedCornerShape(24.dp)).background(Palette.Text1).padding(16.dp)) {
                QrImage(url, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.meeting_code), style = Type.Caption)
            Text(code, style = Type.Display.copy(letterSpacing = 8.sp, fontFeatureSettings = "tnum"))
            Spacer(Modifier.height(12.dp))
            Text(title, style = Type.Body, color = Palette.Text2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(url, style = Type.Caption, color = Palette.TextTrans, textAlign = TextAlign.Center)
            expiresAt?.let {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.share_expires, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(it))), style = Type.Caption)
            }
            Spacer(Modifier.height(20.dp))
            SecondaryButton(stringResource(R.string.action_share_link), {
                ctx.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }, height = 72.dp)
        }
    }
}
