package com.aihuiji.meeting.ui.meeting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aihuiji.meeting.R
import com.aihuiji.meeting.container
import com.aihuiji.meeting.core.model.AppSettings
import com.aihuiji.meeting.core.model.CaptionSize
import com.aihuiji.meeting.core.model.Lang
import com.aihuiji.meeting.ui.components.BigSwitch
import com.aihuiji.meeting.ui.components.BrandLogo
import com.aihuiji.meeting.ui.components.Chip
import com.aihuiji.meeting.ui.components.PrimaryButton
import com.aihuiji.meeting.ui.components.SecondaryButton
import com.aihuiji.meeting.ui.labels.Labels
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type

class IdleActions(
    val start: () -> Unit,
    val openRecords: () -> Unit,
    val openSettings: () -> Unit,
    val openMinutes: (String) -> Unit,
    val takeAway: (String) -> Unit,
    val minimize: () -> Unit,
    val close: () -> Unit,
)

/**
 * The floating window before a meeting runs: the same bar chrome as the live captions, with the single
 * start control, the language / font menus and a way into the saved meetings.
 */
@Composable
fun IdleBarContent(actions: IdleActions, chrome: BarChrome, modifier: Modifier = Modifier) {
    val container = LocalContext.current.container
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val generation by container.meetingController.generation.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf<BarMenu?>(null) }
    var seenMinutes by remember { mutableStateOf<String?>(null) }
    val update = container.settings::update

    val shape = RoundedCornerShape(28.dp)
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(BarRing)
                .clip(shape)
                .background(Palette.Bg0.copy(alpha = 0.96f))
                .background(Palette.GlassTopHighlight)
                .border(1.5.dp, Color(0x40FFFFFF), shape),
        ) {
            IdleToolbar(settings, actions, chrome, menu, onMenu = { menu = if (menu == it) null else it }, update = update)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Stroke))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val bodyW = maxWidth
                val bodyH = maxHeight
                IdleBody(
                    settings = settings,
                    generation = generation?.takeIf { it.meetingId != seenMinutes },
                    onStart = actions.start,
                    onMinutes = { id -> seenMinutes = id; actions.openMinutes(id) },
                    onTakeAway = { id -> seenMinutes = id; actions.takeAway(id) },
                    onRecords = actions.openRecords,
                )
                if (menu != null) {
                    Box(
                        Modifier.fillMaxSize().background(Palette.Bg0.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { menu = null } },
                    )
                    val menuShape = RoundedCornerShape(24.dp)
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .widthIn(max = minOf(780.dp, bodyW - 24.dp))
                            .heightIn(max = bodyH - 24.dp)
                            .clip(menuShape)
                            .background(Palette.Bg2)
                            .border(1.dp, Palette.Stroke, menuShape)
                            .pointerInput(Unit) { detectTapGestures { } }
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                    ) {
                        when (menu) {
                            BarMenu.LANGUAGE -> IdleLanguageMenu(settings, update)
                            BarMenu.FONT -> IdleFontMenu(settings, update)
                            null -> Unit
                        }
                    }
                }
            }
        }
        ResizeGrips(chrome)
    }
}

@Composable
private fun IdleToolbar(
    settings: AppSettings,
    actions: IdleActions,
    chrome: BarChrome,
    menu: BarMenu?,
    onMenu: (BarMenu) -> Unit,
    update: ((AppSettings) -> AppSettings) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(88.dp)) {
        val full = maxWidth >= 1380.dp
        val medium = maxWidth >= 1000.dp
        Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).fillMaxHeight().then(chrome.move(null)), verticalAlignment = Alignment.CenterVertically) {
                BrandLogo(size = 52.dp)
                Spacer(Modifier.width(12.dp))
                if (medium) {
                    Text(stringResource(R.string.app_name), style = Type.Label.copy(fontSize = 28.sp), maxLines = 1)
                    Spacer(Modifier.width(16.dp))
                }
                if (full) {
                    Row(
                        Modifier.height(48.dp).clip(CircleShape).background(Palette.Bg2.copy(alpha = 0.85f)).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Palette.Success))
                        Spacer(Modifier.width(10.dp))
                        val profile = settings.activeProfile
                        val backend = stringResource(if (profile.isCloud) R.string.backend_cloud_short else R.string.backend_onprem_short)
                        val label = if (profile.demoBackend) "$backend（${stringResource(R.string.backend_demo_short)}）" else backend
                        Text(stringResource(R.string.idle_ready_backend, label), style = Type.Label.copy(fontSize = 22.sp), maxLines = 1)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val targets = if (settings.translationEnabled) settings.targetLangs.filter { it != settings.primaryLang }.joinToString("·") { it.tag } else ""
                BarPill(
                    icon = Icons.Outlined.Translate,
                    text = if (medium) stringResource(R.string.bar_language) + if (targets.isNotEmpty()) " · $targets" else "" else null,
                    onClick = { onMenu(BarMenu.LANGUAGE) },
                    active = menu == BarMenu.LANGUAGE,
                    dot = settings.translationEnabled,
                )
                BarPill(
                    icon = Icons.Outlined.TextFields,
                    text = if (medium) stringResource(Labels.barFont(settings.captionSize)) else null,
                    onClick = { onMenu(BarMenu.FONT) },
                    active = menu == BarMenu.FONT,
                )
                BarPill(
                    icon = Icons.Outlined.Videocam,
                    text = if (medium) stringResource(R.string.field_remote_audio) else null,
                    onClick = { update { it.copy(remoteAudio = !it.remoteAudio) } },
                    active = settings.remoteAudio,
                    chevron = false,
                )
                BarDivider()
                BarIcon(Icons.Outlined.History, stringResource(R.string.idle_records), actions.openRecords)
                BarIcon(Icons.Outlined.Settings, stringResource(R.string.action_settings), actions.openSettings)
                BarIcon(Icons.Outlined.Remove, stringResource(R.string.action_minimize), actions.minimize)
                BarIcon(Icons.Outlined.Close, stringResource(R.string.idle_close), actions.close)
            }
        }
    }
}

@Composable
private fun IdleBody(
    settings: AppSettings,
    generation: com.aihuiji.meeting.meeting.Generation?,
    onStart: () -> Unit,
    onMinutes: (String) -> Unit,
    onTakeAway: (String) -> Unit,
    onRecords: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PrimaryButton(
                stringResource(R.string.start_meeting),
                onStart,
                modifier = Modifier.width(420.dp),
                icon = Icons.Outlined.Mic,
                height = 96.dp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(if (settings.remoteAudio) R.string.idle_video_on else R.string.idle_hint),
                style = Type.Caption,
                color = Palette.Text2,
                textAlign = TextAlign.Center,
            )
            if (generation != null) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    when {
                        generation.error != null -> {
                            Text(stringResource(R.string.idle_failed), style = Type.Label, color = Palette.Warning)
                            SecondaryButton(stringResource(R.string.idle_records), onRecords, height = 64.dp)
                        }
                        generation.done -> {
                            Text(stringResource(R.string.idle_generated), style = Type.Label, color = Palette.Success)
                            PrimaryButton(stringResource(R.string.idle_save_local), { onTakeAway(generation.meetingId) }, icon = Icons.Outlined.SaveAlt, height = 64.dp)
                            PrimaryButton(stringResource(R.string.idle_take_qr), { onTakeAway(generation.meetingId) }, icon = Icons.Outlined.QrCode2, height = 64.dp)
                            SecondaryButton(stringResource(R.string.idle_view_minutes), { onMinutes(generation.meetingId) }, height = 64.dp)
                        }
                        else -> Text(
                            stringResource(R.string.idle_generating, (generation.progress * 100).toInt()),
                            style = Type.Label,
                            color = Palette.Brand400,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleLanguageMenu(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val primary = settings.primaryLang
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.more_translation), style = Type.Label)
            Spacer(Modifier.width(20.dp))
            BigSwitch(settings.translationEnabled, { on ->
                update {
                    val fallback = if (it.primaryLang == Lang.EN) Lang.ZH else Lang.EN
                    val targets = it.targetLangs.filter { l -> l != it.primaryLang }.ifEmpty { listOf(fallback) }
                    it.copy(translationEnabled = on, targetLangs = targets)
                }
            })
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.field_recognition_lang), style = Type.Caption)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip(stringResource(R.string.lang_auto), settings.autoDetect, { update { it.copy(autoDetect = !it.autoDetect) } })
            Lang.entries.forEach { lang ->
                Chip(lang.nativeName, primary == lang, {
                    update { it.copy(primaryLang = lang, targetLangs = it.targetLangs.filter { l -> l != lang }) }
                })
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.more_target_lang), style = Type.Caption)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Lang.entries.filter { it != primary }.forEach { lang ->
                val selected = lang in settings.targetLangs
                Chip(lang.nativeName, selected && settings.translationEnabled, {
                    update {
                        val next = if (selected) it.targetLangs - lang else (it.targetLangs + lang).takeLast(2)
                        it.copy(targetLangs = next, translationEnabled = it.translationEnabled || !selected)
                    }
                })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleFontMenu(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    Column {
        Text(stringResource(R.string.more_font_size), style = Type.Caption)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CaptionSize.entries.forEach { size ->
                Chip(stringResource(Labels.size(size)), settings.captionSize == size, { update { it.copy(captionSize = size) } })
            }
        }
    }
}
