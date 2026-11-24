package com.aihuiji.meeting.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.OpenInFull
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.aihuiji.meeting.MainActivity
import com.aihuiji.meeting.R
import com.aihuiji.meeting.ui.components.DangerButton
import com.aihuiji.meeting.ui.components.IconCircleButton
import com.aihuiji.meeting.ui.components.SecondaryButton
import com.aihuiji.meeting.ui.components.SpeakerAvatar
import com.aihuiji.meeting.ui.components.Waveform
import com.aihuiji.meeting.ui.components.pressable
import com.aihuiji.meeting.ui.meeting.LiveActions
import com.aihuiji.meeting.ui.meeting.MeetingPanel
import com.aihuiji.meeting.ui.meeting.rememberLiveUiModel
import com.aihuiji.meeting.ui.theme.AiHuijiTheme
import com.aihuiji.meeting.ui.theme.Palette
import com.aihuiji.meeting.ui.theme.Type
import com.aihuiji.meeting.core.model.MarkTag

/**
 * S05-B floating AI panel drawn over the conference app. Default: snapped to the right edge,
 * clear of the conference app's top and bottom bars; collapses to a 96 dp bubble. The window is
 * not focusable, so touches outside it go straight to the conference app.
 */
class FloatingPanelService : LifecycleService(), SavedStateRegistryOwner {
    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private var view: ComposeView? = null
    private lateinit var params: WindowManager.LayoutParams
    private val wm by lazy { getSystemService(WindowManager::class.java) }

    override fun onCreate() {
        savedState.performRestore(null)
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (view == null && Settings.canDrawOverlays(this)) addPanel()
        return START_NOT_STICKY
    }

    private fun addPanel() {
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        val width = (560 * density).toInt()
        params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - width - (48 * density).toInt()
            y = (96 * density).toInt()
        }
        val compose = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingPanelService)
            setViewTreeSavedStateRegistryOwner(this@FloatingPanelService)
            setContent { AiHuijiTheme { FloatingContent(::moveBy, ::resize, ::openApp) } }
        }
        wm.addView(compose, params)
        view = compose
    }

    private fun moveBy(dx: Float, dy: Float) {
        val v = view ?: return
        params.x = (params.x + dx).toInt().coerceAtLeast(0)
        params.y = (params.y + dy).toInt().coerceAtLeast(0)
        wm.updateViewLayout(v, params)
    }

    private fun resize(collapsed: Boolean) {
        val v = view ?: return
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        params.width = if (collapsed) (120 * density).toInt() else (560 * density).toInt()
        params.x = screenW - params.width - (24 * density).toInt()
        wm.updateViewLayout(v, params)
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    override fun onDestroy() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        super.onDestroy()
    }

    companion object {
        fun show(context: Context) {
            if (Settings.canDrawOverlays(context)) context.startService(Intent(context, FloatingPanelService::class.java))
        }

        fun hide(context: Context) {
            context.stopService(Intent(context, FloatingPanelService::class.java))
        }
    }
}

@Composable
private fun FloatingContent(moveBy: (Float, Float) -> Unit, resize: (Boolean) -> Unit, openApp: () -> Unit) {
    val model = rememberLiveUiModel() ?: return
    var collapsed by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    val drag = Modifier.pointerInput(Unit) { detectDragGestures { change, amount -> change.consume(); moveBy(amount.x, amount.y) } }

    if (collapsed) {
        Box(
            Modifier.padding(12.dp).size(96.dp).clip(CircleShape).background(Palette.BrandGradient).then(drag)
                .pressable { collapsed = false; resize(false) },
            contentAlignment = Alignment.Center,
        ) {
            if (model.activeSpeaker != null) SpeakerAvatar(model.activeSpeaker, size = 52.dp, active = true)
            else Waveform(level = model.level(), maxHeight = 48.dp, barWidth = 6.dp, gap = 4.dp, color = Palette.Text1)
        }
        return
    }

    val actions = LiveActions(
        openMore = openApp,
        openShare = openApp,
        openEnd = { confirmEnd = true },
        toggleNotes = {},
        mark = { model.controller.addMark(MarkTag.KEY) },
        segmentMenu = { seg -> model.controller.excerpt(seg.id) },
    )
    MeetingPanel(
        model = model,
        actions = actions,
        modifier = Modifier.fillMaxWidth().height(820.dp),
        floating = true,
        header = {
            Row(Modifier.fillMaxWidth().height(64.dp).then(drag), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DragIndicator, stringResource(R.string.action_drag), tint = Palette.Text2, modifier = Modifier.size(40.dp))
                Spacer(Modifier.weight(1f))
                IconCircleButton(Icons.Outlined.OpenInFull, stringResource(R.string.action_maximize), {
                    model.controller.setPresentation(com.aihuiji.meeting.core.model.MeetingMode.IN_ROOM)
                    openApp()
                }, size = 56.dp)
                Spacer(Modifier.width(8.dp))
                IconCircleButton(Icons.Outlined.CloseFullscreen, stringResource(R.string.action_collapse), { collapsed = true; resize(true) }, size = 56.dp)
            }
            if (confirmEnd) {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(stringResource(R.string.end_title), style = Type.Label)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecondaryButton(stringResource(R.string.action_continue_meeting), { confirmEnd = false }, height = 72.dp)
                        DangerButton(stringResource(R.string.action_end), {
                            confirmEnd = false
                            model.controller.end()
                            openApp()
                        }, height = 72.dp)
                    }
                }
            }
        },
    )
}
