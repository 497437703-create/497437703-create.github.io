package com.aihuiji.meeting

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.content.Context
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
import com.aihuiji.meeting.ui.history.HistoryScreen
import com.aihuiji.meeting.ui.meeting.RawPointer
import com.aihuiji.meeting.ui.onboarding.PermissionsScreen
import com.aihuiji.meeting.ui.settings.SettingsScreen
import com.aihuiji.meeting.ui.summary.GeneratingScreen
import com.aihuiji.meeting.ui.summary.SummaryScreen
import com.aihuiji.meeting.ui.theme.AiHuijiTheme

sealed interface Route {
    data object Permissions : Route
    data object History : Route
    data object Settings : Route
    data class Generating(val id: String) : Route
    data class Summary(val id: String, val offer: Boolean = false) : Route
}

/**
 * The full-size app: saved meetings, minutes and settings. It is never shown on launch; the floating
 * window opens it only when the user asks for records, settings or the finished minutes.
 */
class MainActivity : ComponentActivity() {
    private val stack: SnapshotStateList<Route> = mutableStateListOf(Route.History)
    private var pendingDeepLink by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        immersive()
        if (savedInstanceState == null) applyIntent(intent)
        setContent { AiHuijiTheme { AppNav() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(intent: Intent?) {
        pendingDeepLink = intent?.data
        stack.clear()
        stack.add(Route.History)
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.getStringExtra(EXTRA_ROUTE)) {
            ROUTE_SETTINGS -> stack.add(Route.Settings)
            ROUTE_SUMMARY -> if (id != null) {
                stack.add(
                    if (container.repository.get(id)?.summary != null) Route.Summary(id, offer = intent.getBooleanExtra(EXTRA_OFFER, false))
                    else Route.Generating(id),
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
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

    private fun openRecord(id: String) {
        push(if (container.repository.get(id)?.summary != null) Route.Summary(id) else Route.Generating(id))
    }

    @Composable
    private fun AppNav() {
        LaunchedEffect(pendingDeepLink) {
            val uri = pendingDeepLink ?: return@LaunchedEffect
            pendingDeepLink = null
            val arg = uri.pathSegments.firstOrNull() ?: return@LaunchedEffect
            when (uri.host) {
                "summary" -> container.repository.get(arg)?.let { push(Route.Summary(it.id)) }
                "meeting" -> container.repository.findByCode(arg)?.let { push(Route.Summary(it.id)) }
            }
        }

        val top = stack.lastOrNull() ?: Route.History
        BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

        AnimatedContent(top, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { route ->
            when (route) {
                Route.History -> HistoryScreen(
                    onOpen = ::openRecord,
                    onSettings = { push(Route.Settings) },
                    onBack = { finish() },
                )
                Route.Permissions -> PermissionsScreen(onDone = { stack.removeAt(stack.lastIndex) })
                is Route.Generating -> GeneratingScreen(route.id, onDone = { replaceTop(Route.Summary(route.id, offer = true)) }, onHome = { finish() })
                is Route.Summary -> SummaryScreen(route.id, offerTakeaway = route.offer, onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish() })
                Route.Settings -> SettingsScreen(onBack = { stack.removeAt(stack.lastIndex) }, onPermissions = { push(Route.Permissions) })
            }
        }
    }

    companion object {
        private const val EXTRA_ROUTE = "route"
        private const val EXTRA_ID = "id"
        private const val EXTRA_OFFER = "offer"
        const val ROUTE_HISTORY = "history"
        const val ROUTE_SETTINGS = "settings"
        const val ROUTE_SUMMARY = "summary"

        fun open(context: Context, route: String, id: String? = null, offerTakeaway: Boolean = false) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_ROUTE, route)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_OFFER, offerTakeaway)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }
}
