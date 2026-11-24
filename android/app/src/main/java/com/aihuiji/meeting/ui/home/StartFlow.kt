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

