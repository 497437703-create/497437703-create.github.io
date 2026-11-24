package com.aihuiji.meeting.ui.home

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aihuiji.meeting.R
import com.aihuiji.meeting.container
import com.aihuiji.meeting.core.model.CaptionSize
import com.aihuiji.meeting.core.model.Lang
import com.aihuiji.meeting.core.model.MeetingConfig
import com.aihuiji.meeting.core.model.MeetingMode
import com.aihuiji.meeting.core.model.PanelLayout
import com.aihuiji.meeting.core.model.Templates
import com.aihuiji.meeting.device.CheckItem
import com.aihuiji.meeting.device.CheckResult
import com.aihuiji.meeting.device.CheckStatus
import com.aihuiji.meeting.service.ProjectionConsent
import com.aihuiji.meeting.ui.components.BigSwitch
import com.aihuiji.meeting.ui.components.Chip
import com.aihuiji.meeting.ui.components.IconCircleButton
import com.aihuiji.meeting.ui.components.PrimaryButton
import com.aihuiji.meeting.ui.components.SecondaryButton
import com.aihuiji.meeting.ui.components.StatusPill
import com.aihuiji.meeting.ui.components.Waveform
import com.aihuiji.meeting.ui.components.pressable
import com.aihuiji.meeting.ui.labels.Labels
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type
import com.aihuiji.meeting.vc.VcLauncher
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Step { SHEET, CHECK, CAPTURE }

/** S03 → S16b → capture consent card → meeting start (two touches on the happy path). */
@Composable
fun StartFlow(mode: MeetingMode, serverDown: Boolean, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val container = ctx.container
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var step by remember { mutableStateOf(Step.SHEET) }
    var config by remember {
        mutableStateOf(
            MeetingConfig(
                mode = mode,
                layout = settings.defaultLayout,
                primaryLang = settings.primaryLang,
                autoDetect = settings.autoDetect,
                translationEnabled = settings.translationEnabled,
                targetLangs = settings.targetLangs.filter { it != settings.primaryLang },
                templateId = settings.templateId,
                profileId = settings.activeProfileId,
                recordOnly = serverDown,
            ),
        )
    }

    val inRoomTitle = stringResource(R.string.title_inroom)
    val videoTitle = stringResource(R.string.title_video)
    val floatFallback = stringResource(R.string.toast_floating_needs_permission)
    val vcMissing = stringResource(R.string.toast_vc_missing)

    fun launch(consent: ProjectionConsent?) {
        var cfg = config
        if (cfg.mode == MeetingMode.VIDEO && cfg.layout == PanelLayout.FLOATING && !Settings.canDrawOverlays(ctx)) {
            cfg = cfg.copy(layout = PanelLayout.SPLIT)
            Toast.makeText(ctx, floatFallback, Toast.LENGTH_LONG).show()
        }
        container.settings.update {
            it.copy(
                primaryLang = cfg.primaryLang, autoDetect = cfg.autoDetect, translationEnabled = cfg.translationEnabled,
                targetLangs = cfg.targetLangs.ifEmpty { it.targetLangs }, templateId = cfg.templateId, defaultLayout = cfg.layout,
            )
        }
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
        container.meetingController.start(cfg, "${if (cfg.mode == MeetingMode.IN_ROOM) inRoomTitle else videoTitle} · $stamp", consent)
        if (cfg.mode == MeetingMode.VIDEO) {
            val app = VcLauncher.installedApp(ctx, settings.vcAppPackage)
            when {
                app == null -> Toast.makeText(ctx, vcMissing, Toast.LENGTH_LONG).show()
                cfg.layout == PanelLayout.SPLIT -> VcLauncher.launchAdjacent(ctx, app.packageName)
                else -> VcLauncher.launchFullscreen(ctx, app.packageName)
            }
        }
        onCancel()
    }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        launch(if (result.resultCode == Activity.RESULT_OK && data != null) ProjectionConsent(result.resultCode, data) else null)
    }

    when (step) {
        Step.SHEET -> PreMeetingSheet(
            config = config,
            serverDown = serverDown,
            onChange = { config = it },
            onCancel = onCancel,
            onStart = { step = Step.CHECK },
        )
        Step.CHECK -> SelfCheckOverlay(
            mode = mode,
            standalone = false,
            recordOnly = config.recordOnly,
            onProceed = { if (mode == MeetingMode.VIDEO) step = Step.CAPTURE else launch(null) },
            onCancel = { step = Step.SHEET },
        )
        Step.CAPTURE -> CaptureConsentCard(
            onContinue = {
                val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            },
            onSkip = { launch(null) },
            onCancel = { step = Step.SHEET },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreMeetingSheet(
    config: MeetingConfig,
    serverDown: Boolean,
    onChange: (MeetingConfig) -> Unit,
    onCancel: () -> Unit,
    onStart: () -> Unit,
) {
    val ctx = LocalContext.current
    val settings by ctx.container.settings.settings.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Palette.Bg0.copy(alpha = 0.6f)).pressable(onClick = onCancel), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(true, enter = slideInHorizontally { it / 3 } + fadeIn()) {
            Column(
                Modifier
                    .width(1000.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 40.dp, bottomStart = 40.dp))
                    .background(Palette.Bg2)
                    .border(1.dp, Palette.Stroke, RoundedCornerShape(topStart = 40.dp, bottomStart = 40.dp))
                    .pressable(onClick = {})
                    .padding(48.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (config.mode == MeetingMode.IN_ROOM) R.string.start_inroom else R.string.start_video),
                        style = Type.TitleL,
                    )
                    Spacer(Modifier.weight(1f))
                    IconCircleButton(Icons.Outlined.Close, stringResource(R.string.action_close), onCancel, size = 72.dp)
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Field(stringResource(R.string.field_recognition_lang)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Chip(stringResource(R.string.lang_auto), config.autoDetect, { onChange(config.copy(autoDetect = !config.autoDetect)) })
                            Lang.entries.forEach { lang ->
                                Chip(lang.nativeName, config.primaryLang == lang, {
                                    onChange(config.copy(primaryLang = lang, targetLangs = config.targetLangs.filter { it != lang }))
                                })
                            }
                        }
                    }
                    Field(stringResource(R.string.field_translation)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BigSwitch(config.translationEnabled, { on ->
                                val targets = config.targetLangs.ifEmpty { listOf(if (config.primaryLang == Lang.EN) Lang.ZH else Lang.EN) }
                                onChange(config.copy(translationEnabled = on, targetLangs = targets))
                            })
                            Spacer(Modifier.width(20.dp))
                            Text(stringResource(R.string.field_translation_hint), style = Type.Caption)
                        }
                        if (config.translationEnabled) {
                            Spacer(Modifier.height(12.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Lang.entries.filter { it != config.primaryLang }.forEach { lang ->
                                    val sel = lang in config.targetLangs
                                    Chip(lang.nativeName, sel, {
                                        val next = if (sel) config.targetLangs - lang else (config.targetLangs + lang).takeLast(2)
                                        onChange(config.copy(targetLangs = next))
                                    })
                                }
                            }
                        }
                    }
                    Field(stringResource(R.string.field_template)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Templates.all.forEach { t ->
                                Chip(stringResource(Labels.template(t.id)), config.templateId == t.id, { onChange(config.copy(templateId = t.id)) }, icon = Labels.templateIcon(t.id))
                            }
                        }
                    }
                    if (config.mode == MeetingMode.VIDEO) {
                        Field(stringResource(R.string.field_panel_layout)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Chip(stringResource(R.string.layout_split_default), config.layout == PanelLayout.SPLIT, { onChange(config.copy(layout = PanelLayout.SPLIT)) })
                                Chip(stringResource(R.string.layout_floating), config.layout == PanelLayout.FLOATING, { onChange(config.copy(layout = PanelLayout.FLOATING)) })
                            }
                        }
                    }
                    Field(stringResource(R.string.field_font_size)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CaptionSize.entries.forEach { size ->
                                Chip(stringResource(Labels.size(size)), settings.captionSize == size, { ctx.container.settings.update { it.copy(captionSize = size) } })
                            }
                        }
                    }
                    Field(stringResource(R.string.field_server_profile)) {
                        val p = settings.profiles.firstOrNull { it.id == config.profileId } ?: settings.activeProfile
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(
                                stringResource(Labels.profile(p.kind)) + if (p.demoBackend || p.baseUrl.isBlank()) stringResource(R.string.demo_suffix) else "",
                                if (serverDown) Palette.Danger else Palette.Success,
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(stringResource(R.string.field_server_profile_hint), style = Type.Caption)
                        }
                    }
                    if (serverDown) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.WarningAmber, null, tint = Palette.Warning, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.record_only_hint), style = Type.Body, color = Palette.Warning)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                PrimaryButton(
                    stringResource(if (serverDown) R.string.action_check_and_record else R.string.action_check_and_start),
                    onStart,
                    modifier = Modifier.fillMaxWidth(),
                    height = 96.dp,
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, style = Type.Label, color = Palette.Text2)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** S16b device-ready overlay: status pills light up one by one (400 ms) — §7 设备就绪胶囊. */
@Composable
fun SelfCheckOverlay(
    mode: MeetingMode,
    standalone: Boolean,
    onProceed: () -> Unit,
    onCancel: () -> Unit,
    recordOnly: Boolean = false,
) {
    val ctx = LocalContext.current
    val container = ctx.container
    val results = remember { mutableStateListOf<CheckResult>() }
    var finished by remember { mutableStateOf(false) }
    var run by remember { mutableIntStateOf(0) }

    LaunchedEffect(run) {
        results.clear()
        finished = false
        val checker = container.deviceChecker
        val bundle = container.providers.create(container.settings.value.activeProfile)
        val items = buildList {
            add(suspend { checker.mic() })
            if (mode == MeetingMode.VIDEO) add(suspend { checker.camera() })
            add(suspend { checker.network() })
            add(suspend { checker.server(bundle.gateway) })
            add(suspend { checker.storage() })
            add(suspend { checker.permissions() })
        }
        for (item in items) {
            delay(400)
            results += item()
        }
        finished = true
    }
    val hasError = results.any { it.status == CheckStatus.ERROR && !(recordOnly && it.item == CheckItem.SERVER) }
    LaunchedEffect(finished, hasError) {
        if (finished && !hasError && !standalone) {
            delay(900)
            onProceed()
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Bg0.copy(alpha = 0.8f)).pressable(onClick = {}), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(980.dp).clip(RoundedCornerShape(40.dp)).background(Palette.Bg2).border(1.dp, Palette.Stroke, RoundedCornerShape(40.dp)).padding(48.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.self_check_title), style = Type.TitleL)
                Spacer(Modifier.weight(1f))
                if (!finished) Waveform(level = 0.6f, maxHeight = 48.dp, barWidth = 6.dp, gap = 5.dp)
            }
            Spacer(Modifier.height(28.dp))
            results.forEach { r ->
                CheckRow(r)
                Spacer(Modifier.height(14.dp))
            }
            if (finished && !hasError && !standalone) {
                Spacer(Modifier.height(8.dp))
                StatusPill(stringResource(R.string.ai_started), Palette.Brand400, icon = Icons.Outlined.CheckCircle)
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SecondaryButton(stringResource(if (standalone) R.string.action_close else R.string.action_back), onCancel)
                if (finished) SecondaryButton(stringResource(R.string.action_recheck), { run++ })
                if (finished && hasError && !standalone) {
                    PrimaryButton(stringResource(R.string.action_start_anyway), onProceed)
                }
            }
        }
    }
}

@Composable
private fun CheckRow(r: CheckResult) {
    val (icon, color) = when (r.status) {
        CheckStatus.OK -> Icons.Outlined.CheckCircle to Palette.Success
        CheckStatus.WARN -> Icons.Outlined.WarningAmber to Palette.Warning
        CheckStatus.ERROR -> Icons.Outlined.ErrorOutline to Palette.Danger
    }
    val title = when (r.item) {
        CheckItem.MIC -> when (r.status) {
            CheckStatus.OK -> stringResource(R.string.check_mic_ok, r.detail)
            CheckStatus.WARN -> stringResource(R.string.check_mic_builtin)
            CheckStatus.ERROR -> stringResource(R.string.check_mic_missing)
        }
        CheckItem.CAMERA -> stringResource(if (r.status == CheckStatus.OK) R.string.check_camera_ok else R.string.check_camera_missing)
        CheckItem.NETWORK -> stringResource(if (r.status == CheckStatus.ERROR) R.string.check_network_down else R.string.check_network_ok, r.detail)
        CheckItem.SERVER -> stringResource(if (r.status == CheckStatus.OK) R.string.check_server_ok else R.string.check_server_down, r.detail)
        CheckItem.STORAGE -> stringResource(R.string.check_storage, r.detail)
        CheckItem.PERMISSIONS -> stringResource(
            when (r.status) {
                CheckStatus.OK -> R.string.check_perm_ok
                CheckStatus.WARN -> R.string.check_perm_overlay
                CheckStatus.ERROR -> R.string.check_perm_mic
            },
        )
    }
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(16.dp))
        Text(title, style = Type.Label)
    }
}

/** Explains the Android capture dialog before it appears (§6.16), so hosts know to tap 立即开始. */
@Composable
private fun CaptureConsentCard(onContinue: () -> Unit, onSkip: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.Bg0.copy(alpha = 0.8f)).pressable(onClick = {}), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(980.dp).clip(RoundedCornerShape(40.dp)).background(Palette.Bg2).border(1.dp, Palette.Stroke, RoundedCornerShape(40.dp)).padding(56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.Cast, null, tint = Palette.Brand400, modifier = Modifier.size(96.dp))
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.capture_title), style = Type.TitleL, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.capture_body), style = Type.Body, color = Palette.Text2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SecondaryButton(stringResource(R.string.action_back), onCancel)
                SecondaryButton(stringResource(R.string.capture_skip), onSkip)
                PrimaryButton(stringResource(R.string.capture_continue), onContinue)
            }
        }
    }
}
