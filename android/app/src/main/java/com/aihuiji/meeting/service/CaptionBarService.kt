package com.aihuiji.meeting.service

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.MotionEvent
import android.widget.FrameLayout
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.aihuiji.meeting.LauncherActivity
import com.aihuiji.meeting.MainActivity
import com.aihuiji.meeting.R
import com.aihuiji.meeting.container
import com.aihuiji.meeting.core.model.BarBounds
import com.aihuiji.meeting.core.model.IconPos
import com.aihuiji.meeting.meeting.MicStatus
import com.aihuiji.meeting.ui.meeting.BarActions
import com.aihuiji.meeting.ui.meeting.BarChrome
import com.aihuiji.meeting.ui.meeting.CaptionBarContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aihuiji.meeting.ui.meeting.EDGE_ALL
import com.aihuiji.meeting.ui.meeting.IdleActions
import com.aihuiji.meeting.ui.meeting.IdleBarContent
import com.aihuiji.meeting.ui.meeting.EDGE_B
import com.aihuiji.meeting.ui.meeting.EDGE_L
import com.aihuiji.meeting.ui.meeting.EDGE_R
import com.aihuiji.meeting.ui.meeting.EDGE_T
import com.aihuiji.meeting.ui.meeting.LiveDialogs
import com.aihuiji.meeting.ui.meeting.LiveOverlay
import com.aihuiji.meeting.ui.meeting.MicAlert
import com.aihuiji.meeting.ui.meeting.MinimizedIcon
import com.aihuiji.meeting.ui.meeting.MinimizedIconSize
import com.aihuiji.meeting.ui.meeting.RawPointer
import com.aihuiji.meeting.ui.meeting.rawDrag
import com.aihuiji.meeting.ui.meeting.rememberLiveUiModel
import com.aihuiji.meeting.ui.theme.AiHuijiTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The caption bar: a resizable panel docked to the bottom of the display, drawn over whatever app is
 * in front (the conference app for video meetings). Minimising animates the same window down to a
 * small pulsing app icon. The window is not focusable, so every touch outside it reaches the app below.
 */
class CaptionBarService : LifecycleService(), SavedStateRegistryOwner {
    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val controller get() = container.meetingController
    private val settingsStore get() = container.settings

    private var barView: OverlayRoot? = null
    private var dialogView: OverlayRoot? = null
    private lateinit var params: WindowManager.LayoutParams

    private var dialog by mutableStateOf<LiveOverlay?>(null)
    private var micDismissed by mutableStateOf<MicStatus?>(null)

    private var screenW = 0
    private var screenH = 0
    private var density = 1f
    private val barRect = Rect()
    private val iconRect = Rect()
    private var iconPlaced = false
    private var minimized = false
    private var gestureStart = Rect()
    private var animator: ValueAnimator? = null

    override fun onCreate() {
        savedState.performRestore(null)
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (barView == null) attach() else controller.setBarMinimized(false)
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val notification = MeetingForegroundService.buildNotification(this, R.string.notif_window)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    // ---------------------------------------------------------------- window

    private fun attach() {
        density = resources.displayMetrics.density
        val bounds = wm.currentWindowMetrics.bounds
        screenW = bounds.width()
        screenH = bounds.height()

        restoreRects()
        minimized = controller.barMinimized.value
        val start = if (minimized) iconRect else barRect
        params = WindowManager.LayoutParams(
            start.width(),
            start.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = start.left
            y = start.top
        }
        val view = composeView { BarRoot() }
        wm.addView(view, params)
        barView = view

        lifecycleScope.launch {
            controller.barMinimized.collect { applyMinimized(it) }
        }
        lifecycleScope.launch {
            combine(controller.live, snapshotFlow { dialog }, snapshotFlow { micDismissed }) { live, d, dismissed ->
                val mic = live?.mic
                d != null || (mic != null && mic !is MicStatus.Ok && mic != dismissed)
            }.distinctUntilChanged().collect { setDialogVisible(it) }
        }
    }

    private fun composeView(content: @Composable () -> Unit): OverlayRoot = OverlayRoot(this).apply {
        setViewTreeLifecycleOwner(this@CaptionBarService)
        setViewTreeSavedStateRegistryOwner(this@CaptionBarService)
        addView(ComposeView(context).apply { setContent { AiHuijiTheme { content() } } })
    }

    private fun setDialogVisible(visible: Boolean) {
        if (visible && dialogView == null) {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            val view = composeView { DialogRoot() }
            wm.addView(view, lp)
            dialogView = view
        } else if (!visible) {
            dialogView?.let { runCatching { wm.removeView(it) } }
            dialogView = null
        }
    }

    // ---------------------------------------------------------------- geometry

    private fun restoreRects() {
        val saved = settingsStore.value.captionBar
        val minW = (MIN_W_DP * density).toInt()
        val minH = (MIN_H_DP * density).toInt()
        val maxW = screenW
        val maxH = (screenH * MAX_H_FRACTION).toInt()
        val w: Int
        val h: Int
        val x: Int
        val y: Int
        if (saved.isSet) {
            w = saved.w.coerceIn(minW, maxW)
            h = saved.h.coerceIn(minH, maxH)
            x = saved.x.coerceIn(0, max(0, screenW - w))
            y = saved.y.coerceIn(0, max(0, screenH - h))
        } else {
            w = min((DEFAULT_W_DP * density).toInt(), (screenW * 0.9f).toInt())
            h = min((DEFAULT_H_DP * density).toInt(), (screenH * 0.42f).toInt())
            x = (screenW - w) / 2
            y = screenH - h
        }
        barRect.set(x, y, x + w, y + h)

        val size = (MinimizedIconSize.value * density).toInt()
        val pos = settingsStore.value.captionIcon
        iconPlaced = pos.isSet
        val ix = if (pos.isSet) pos.x else barRect.right - size - (BAR_RING_DP * density).toInt()
        val iy = if (pos.isSet) pos.y else barRect.bottom - size
        iconRect.set(
            ix.coerceIn(0, max(0, screenW - size)),
            iy.coerceIn(0, max(0, screenH - size)),
            ix.coerceIn(0, max(0, screenW - size)) + size,
            iy.coerceIn(0, max(0, screenH - size)) + size,
        )
    }

    private fun currentRect() = Rect(params.x, params.y, params.x + params.width, params.y + params.height)

    private fun applyRect(rect: Rect) {
        val view = barView ?: return
        params.x = rect.left
        params.y = rect.top
        params.width = rect.width()
        params.height = rect.height()
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun applyMinimized(target: Boolean) {
        if (target == minimized || barView == null) return
        minimized = target
        val from = currentRect()
        if (target) {
            barRect.set(from)
            if (!iconPlaced) {
                val size = iconRect.width()
                val pad = (BAR_RING_DP * density).toInt()
                val x = (from.right - size - pad).coerceIn(0, max(0, screenW - size))
                val y = (from.bottom - size).coerceIn(0, max(0, screenH - size))
                iconRect.set(x, y, x + size, y + size)
            }
        }
        animateTo(from, if (target) iconRect else barRect)
    }

    private fun animateTo(from: Rect, to: Rect) {
        animator?.cancel()
        val end = Rect(to)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                fun lerp(a: Int, b: Int) = (a + (b - a) * f).roundToInt()
                applyRect(Rect(lerp(from.left, end.left), lerp(from.top, end.top), lerp(from.right, end.right), lerp(from.bottom, end.bottom)))
            }
            start()
        }
    }

    private fun beginGesture() {
        animator?.cancel()
        gestureStart = currentRect()
    }

    private fun dragMove(dx: Float, dy: Float) {
        val s = gestureStart
        val x = (s.left + dx).roundToInt().coerceIn(0, max(0, screenW - s.width()))
        val y = (s.top + dy).roundToInt().coerceIn(0, max(0, screenH - s.height()))
        applyRect(Rect(x, y, x + s.width(), y + s.height()))
    }

    private fun dragResize(mask: Int, dx: Float, dy: Float) {
        val s = gestureStart
        val minW = (MIN_W_DP * density).toInt()
        val minH = (MIN_H_DP * density).toInt()
        val maxW = screenW
        val maxH = (screenH * MAX_H_FRACTION).toInt()
        var l = s.left
        var t = s.top
        var r = s.right
        var b = s.bottom
        if (mask and EDGE_L != 0) l = (s.left + dx).roundToInt().coerceIn(max(0, r - maxW), r - minW)
        if (mask and EDGE_R != 0) r = (s.right + dx).roundToInt().coerceIn(l + minW, min(screenW, l + maxW))
        if (mask and EDGE_T != 0) t = (s.top + dy).roundToInt().coerceIn(max(0, b - maxH), b - minH)
        if (mask and EDGE_B != 0) b = (s.bottom + dy).roundToInt().coerceIn(t + minH, min(screenH, t + maxH))
        applyRect(Rect(l, t, r, b))
    }

    private fun endGesture() {
        val rect = currentRect()
        if (minimized) {
            iconRect.set(rect)
            iconPlaced = true
            settingsStore.update { it.copy(captionIcon = IconPos(rect.left, rect.top)) }
        } else {
            barRect.set(rect)
            settingsStore.update { it.copy(captionBar = BarBounds(rect.left, rect.top, rect.width(), rect.height())) }
        }
    }

    private fun setKeyboard(on: Boolean) {
        val view = barView ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val next = if (on) params.flags and flag.inv() else params.flags or flag
        if (next == params.flags) return
        params.flags = next
        runCatching { wm.updateViewLayout(view, params) }
    }

    // ---------------------------------------------------------------- content

    private val chrome = BarChrome(
        edges = EDGE_ALL,
        move = { onTap ->
            Modifier.rawDrag(
                onStart = ::beginGesture,
                onDrag = ::dragMove,
                onEnd = { moved ->
                    if (moved) endGesture() else onTap?.invoke()
                },
            )
        },
        resize = { mask ->
            Modifier.rawDrag(
                slopPx = 0f,
                onStart = ::beginGesture,
                onDrag = { dx, dy -> dragResize(mask, dx, dy) },
                onEnd = { endGesture() },
            )
        },
    )

    private val idleActions by lazy {
        IdleActions(
            start = { LauncherActivity.requestStart(this, settingsStore.value.remoteAudio) },
            openRecords = { MainActivity.open(this, MainActivity.ROUTE_HISTORY) },
            openSettings = { MainActivity.open(this, MainActivity.ROUTE_SETTINGS) },
            openMinutes = { MainActivity.open(this, MainActivity.ROUTE_SUMMARY, it) },
            minimize = { controller.setBarMinimized(true) },
            close = { stopSelf() },
        )
    }

    private val actions by lazy {
        BarActions(
            minimize = { controller.setBarMinimized(true) },
            openMore = { dialog = LiveOverlay.More },
            openShare = { dialog = LiveOverlay.Share },
            openEnd = { dialog = LiveOverlay.End },
            segmentMenu = { dialog = LiveOverlay.SegmentMenu(it) },
            wantKeyboard = ::setKeyboard,
        )
    }

    @Composable
    private fun BarRoot() {
        val model = rememberLiveUiModel()
        val mini by controller.barMinimized.collectAsStateWithLifecycle()
        Crossfade(targetState = mini, animationSpec = tween(220), label = "bar") { isMini ->
            when {
                isMini -> MinimizedIcon(model, chrome, onExpand = { controller.setBarMinimized(false) })
                model != null -> CaptionBarContent(model, actions, chrome)
                else -> IdleBarContent(idleActions, chrome)
            }
        }
    }

    @Composable
    private fun DialogRoot() {
        val model = rememberLiveUiModel() ?: return
        Box(Modifier.fillMaxSize()) {
            LiveDialogs(model, dialog, onOverlay = { dialog = it })
            val mic = model.live.mic
            MicAlert(model, dismissed = mic == micDismissed, onDismiss = { micDismissed = mic })
        }
    }

    override fun onDestroy() {
        animator?.cancel()
        dialogView?.let { runCatching { wm.removeView(it) } }
        barView?.let { runCatching { wm.removeView(it) } }
        dialogView = null
        barView = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val MIN_W_DP = 900
        private const val MIN_H_DP = 260
        private const val DEFAULT_W_DP = 1660
        private const val DEFAULT_H_DP = 360
        private const val NOTIFICATION_ID = 1002
        private const val MAX_H_FRACTION = 0.85f
        private const val BAR_RING_DP = 24

        fun show(context: Context) {
            if (Settings.canDrawOverlays(context)) context.startForegroundService(Intent(context, CaptionBarService::class.java))
        }

        fun hide(context: Context) {
            context.stopService(Intent(context, CaptionBarService::class.java))
        }
    }
}

/** Overlay root: publishes raw touch coordinates for window drag/resize and drops palm-sized contacts. */
private class OverlayRoot(context: Context) : FrameLayout(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        RawPointer.update(ev)
        val palmPx = 40f * resources.displayMetrics.density
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.touchMajor > palmPx * 3) return true
        return super.dispatchTouchEvent(ev)
    }
}
