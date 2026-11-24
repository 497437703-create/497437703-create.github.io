package com.aihuiji.meeting.ui.meeting

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aihuiji.meeting.R
import com.aihuiji.meeting.ui.components.BrandWordmark
import com.aihuiji.meeting.ui.components.PrimaryButton
import com.aihuiji.meeting.ui.components.SecondaryButton
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type

/**
 * In-app stand-in for the floating caption bar, used when "display over other apps" is not granted:
 * the same bar, docked at the bottom of the app, resizable from its top edge.
 */
@Composable
fun LiveMeetingScreen(onMinimize: () -> Unit) {
    val model = rememberLiveUiModel() ?: return
    val ctx = LocalContext.current
    val density = LocalDensity.current
    var overlay by remember { mutableStateOf<LiveOverlay?>(null) }
    var barHeight by remember { mutableStateOf(400.dp) }
    var startHeight by remember { mutableStateOf(barHeight) }

    BackHandler { overlay = LiveOverlay.End }

    val chrome = remember {
        BarChrome(
            edges = EDGE_T,
            move = { Modifier },
            resize = { _ ->
                Modifier.rawDrag(
                    slopPx = 0f,
                    onStart = { startHeight = barHeight },
                    onDrag = { _, dy -> barHeight = (startHeight - with(density) { dy.toDp() }).coerceIn(260.dp, 900.dp) },
                )
            },
        )
    }
    val actions = BarActions(
        minimize = onMinimize,
        openMore = { overlay = LiveOverlay.More },
        openShare = { overlay = LiveOverlay.Share },
        openEnd = { overlay = LiveOverlay.End },
        segmentMenu = { overlay = LiveOverlay.SegmentMenu(it) },
        wantKeyboard = {},
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Bg0)) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 120.dp).width(1100.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandWordmark(size = 80.dp)
            Spacer(Modifier.height(40.dp))
            Text(stringResource(R.string.fallback_title), style = Type.TitleL, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.fallback_body), style = Type.Body, color = Palette.Text2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                PrimaryButton(stringResource(R.string.overlay_grant), {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", ctx.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                })
                SecondaryButton(stringResource(R.string.fallback_back_home), onMinimize)
            }
        }
        val maxBar: Dp = maxHeight - 80.dp
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(barHeight.coerceAtMost(maxBar))) {
            CaptionBarContent(model, actions, chrome)
        }
        LiveDialogs(model, overlay, onOverlay = { overlay = it })
        val mic = model.live.mic
        var dismissed by remember(mic) { mutableStateOf(false) }
        MicAlert(model, dismissed, onDismiss = { dismissed = true })
    }
}
