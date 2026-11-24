package com.aihuiji.meeting.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.aihuiji.meeting.R
import com.aihuiji.meeting.audio.AudioDevices
import com.aihuiji.meeting.container
import com.aihuiji.meeting.ui.components.BrandWordmark
import com.aihuiji.meeting.ui.components.PrimaryButton
import com.aihuiji.meeting.ui.components.SecondaryButton
import com.aihuiji.meeting.ui.components.StatusPill
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type

/** S22 first-run permission guide for APK delivery: explain first, then trigger each system prompt. */
@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var captureUnderstood by remember { mutableStateOf(false) }
    var micProbe by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val micGranted = remember(refresh) { ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val overlay = remember(refresh) { Settings.canDrawOverlays(ctx) }
    val notifications = remember(refresh) {
        Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    fun finish() {
        ctx.container.settings.update { it.copy(onboardingDone = true) }
        onDone()
    }

    Box(Modifier.fillMaxSize().background(Palette.Bg0), contentAlignment = Alignment.Center) {
        Column(Modifier.width(1400.dp)) {
            BrandWordmark(size = 72.dp)
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.onboarding_title), style = Type.TitleL)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.onboarding_sub), style = Type.Body, color = Palette.Text2)
            Spacer(Modifier.height(36.dp))
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                StepRow(1, stringResource(R.string.perm_mic), stringResource(R.string.perm_mic_why), micGranted) {
                    permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                StepRow(2, stringResource(R.string.perm_overlay), stringResource(R.string.perm_overlay_why), overlay) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                }
                StepRow(3, stringResource(R.string.perm_capture), stringResource(R.string.perm_capture_why), captureUnderstood, actionLabel = stringResource(R.string.action_understood)) {
                    captureUnderstood = true
                }
                StepRow(4, stringResource(R.string.perm_notify), stringResource(R.string.perm_notify_why), notifications) {
                    if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            Spacer(Modifier.height(36.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                micProbe?.let { StatusPill(it, Palette.Brand400) }
                Spacer(Modifier.weight(1f))
                val noMic = stringResource(R.string.probe_no_mic)
                val aecOn = stringResource(R.string.probe_aec_on)
                val aecOff = stringResource(R.string.probe_aec_off)
                SecondaryButton(stringResource(R.string.action_detect_mic), {
                    val mic = AudioDevices.bestMic(ctx)
                    micProbe = if (mic == null) noMic else "${mic.name} · ${if (AudioDevices.platformAecAvailable()) aecOn else aecOff}"
                })
                Spacer(Modifier.width(24.dp))
                SecondaryButton(stringResource(R.string.action_later), ::finish)
                Spacer(Modifier.width(24.dp))
                PrimaryButton(stringResource(R.string.action_done), ::finish, enabled = micGranted)
            }
        }
    }
}

@Composable
private fun StepRow(n: Int, title: String, why: String, done: Boolean, actionLabel: String? = null, onAction: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(128.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (done) Palette.Success.copy(alpha = 0.08f) else Palette.Bg1)
            .border(1.dp, if (done) Palette.Success.copy(alpha = 0.4f) else Palette.Stroke, RoundedCornerShape(28.dp))
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(if (done) Palette.Success else Palette.Brand500),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Outlined.Check, null, tint = Palette.Bg0, modifier = Modifier.size(40.dp))
            else Text("$n", style = Type.TitleM)
        }
        Spacer(Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.TitleM)
            Text(why, style = Type.Caption)
        }
        if (!done) PrimaryButton(actionLabel ?: stringResource(R.string.action_grant), onAction, height = 80.dp)
        else Text(stringResource(R.string.perm_done), style = Type.Label, color = Palette.Success)
    }
}
