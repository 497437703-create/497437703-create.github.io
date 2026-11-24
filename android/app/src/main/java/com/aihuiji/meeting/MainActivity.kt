package com.aihuiji.meeting

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aihuiji.meeting.core.model.MeetingState
import com.aihuiji.meeting.ui.history.HistoryScreen
import com.aihuiji.meeting.ui.home.HomeScreen
import com.aihuiji.meeting.ui.meeting.LiveMeetingScreen
import com.aihuiji.meeting.ui.meeting.RawPointer
import com.aihuiji.meeting.ui.onboarding.PermissionsScreen
import com.aihuiji.meeting.ui.onboarding.SplashScreen
import com.aihuiji.meeting.ui.settings.SettingsScreen
import com.aihuiji.meeting.ui.summary.GeneratingScreen
import com.aihuiji.meeting.ui.summary.SummaryScreen
import com.aihuiji.meeting.ui.theme.AiHuijiTheme

sealed interface Route {
    data object Splash : Route
    data object Permissions : Route
    data object Home : Route
    data object Live : Route
    data class Generating(val id: String) : Route
    data class Summary(val id: String) : Route
    data object History : Route
    data object Settings : Route
}

class MainActivity : ComponentActivity() {
    private val stack: SnapshotStateList<Route> = mutableStateListOf(Route.Splash)
    private var pendingDeepLink by mutableStateOf<Uri?>(null)
    private var overlayGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        immersive()
        pendingDeepLink = intent?.data
        overlayGranted = Settings.canDrawOverlays(this)
        if (savedInstanceState != null) stack.apply { clear(); add(Route.Home) }
        setContent { AiHuijiTheme { AppNav() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingDeepLink = intent.data
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = Settings.canDrawOverlays(this)
    }

    /** Palm rejection for large boards: drop very large contact areas (forearm/palm resting on glass). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        RawPointer.update(ev)
        val palmPx = 40f * resources.displayMetrics.density
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.touchMajor > palmPx * 3) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun immersive() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun push(r: Route) {
        if (stack.lastOrNull() != r) stack.add(r)
    }

    private fun replaceTop(r: Route) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack.add(r)
    }

    private fun home() {
        stack.clear()
        stack.add(Route.Home)
    }

    @Composable
    private fun AppNav() {
        val controller = container.meetingController
        val live by controller.live.collectAsStateWithLifecycle()
        val generation by controller.generation.collectAsStateWithLifecycle()
        val settings by container.settings.settings.collectAsStateWithLifecycle()

        val running = live != null
        val ending = live?.record?.state == MeetingState.ENDING
        val generationId = generation?.meetingId
        LaunchedEffect(running, ending, overlayGranted, generationId) {
            val topRoute = stack.lastOrNull()
            when {
                ending && generationId != null ->
                    if (topRoute == Route.Live) replaceTop(Route.Generating(generationId))
                    else if (topRoute !is Route.Generating) push(Route.Generating(generationId))
                running && !overlayGranted -> push(Route.Live)
                running && topRoute == Route.Live -> stack.removeAt(stack.lastIndex)
                !running && topRoute == Route.Live -> if (generationId != null) replaceTop(Route.Generating(generationId)) else home()
            }
        }
        LaunchedEffect(pendingDeepLink, stack.firstOrNull()) {
            val uri = pendingDeepLink ?: return@LaunchedEffect
            if (stack.firstOrNull() == Route.Splash) return@LaunchedEffect
            pendingDeepLink = null
            val arg = uri.pathSegments.firstOrNull() ?: return@LaunchedEffect
            when (uri.host) {
                "summary" -> container.repository.get(arg)?.let { push(Route.Summary(it.id)) }
                "meeting" -> container.repository.findByCode(arg)?.let { push(Route.Summary(it.id)) }
            }
        }

        val top = stack.lastOrNull() ?: Route.Home
        BackHandler(enabled = stack.size > 1 && top != Route.Live) { stack.removeAt(stack.lastIndex) }

        AnimatedContent(top, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { route ->
            when (route) {
                Route.Splash -> SplashScreen {
                    stack.clear()
                    stack.add(Route.Home)
                    if (!settings.onboardingDone) stack.add(Route.Permissions)
                }
                Route.Permissions -> PermissionsScreen(onDone = { stack.removeAt(stack.lastIndex) })
                Route.Home -> HomeScreen(
                    onOpenMeeting = { id ->
                        val rec = container.repository.get(id)
                        push(if (rec?.summary != null) Route.Summary(id) else Route.Generating(id))
                    },
                    onHistory = { push(Route.History) },
                    onSettings = { push(Route.Settings) },
                    onOpenLive = { push(Route.Live) },
                )
                Route.Live -> LiveMeetingScreen(onMinimize = { if (stack.lastOrNull() == Route.Live) stack.removeAt(stack.lastIndex) })
                is Route.Generating -> GeneratingScreen(route.id, onDone = { replaceTop(Route.Summary(route.id)) }, onHome = ::home)
                is Route.Summary -> SummaryScreen(route.id, onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else home() })
                Route.History -> HistoryScreen(
                    onOpen = { id ->
                        val rec = container.repository.get(id)
                        push(if (rec?.summary != null) Route.Summary(id) else Route.Generating(id))
                    },
                    onBack = { stack.removeAt(stack.lastIndex) },
                )
                Route.Settings -> SettingsScreen(onBack = { stack.removeAt(stack.lastIndex) }, onPermissions = { push(Route.Permissions) })
            }
        }
    }
}
