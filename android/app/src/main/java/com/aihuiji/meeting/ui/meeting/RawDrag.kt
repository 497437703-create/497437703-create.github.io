package com.aihuiji.meeting.ui.meeting

import android.view.MotionEvent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.hypot

/**
 * Drag that reports totals in *screen* pixels since touch-down. A window that moves or resizes under the
 * finger would make view-relative deltas jitter; raw coordinates stay exact.
 */
@Composable
fun Modifier.rawDrag(
    slopPx: Float? = null,
    onStart: () -> Unit = {},
    onDrag: (dx: Float, dy: Float) -> Unit,
    onEnd: (moved: Boolean) -> Unit = {},
): Modifier {
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    return pointerInput(slopPx) {
        val slop = slopPx ?: viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val origin = rawPosition(currentEvent) ?: return@awaitEachGesture
            start()
            var moved = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val now = rawPosition(event)
                if (now != null) {
                    val dx = now.x - origin.x
                    val dy = now.y - origin.y
                    if (!moved && hypot(dx, dy) > slop) moved = true
                    if (moved) drag(dx, dy)
                }
                change.consume()
                if (!change.pressed) break
            }
            end(moved)
        }
    }
}

private fun rawPosition(@Suppress("UNUSED_PARAMETER") event: PointerEvent): Offset = RawPointer.position

/**
 * Screen-space position of the first pointer, published by whichever window hosts the Compose content
 * (activity or overlay view) from its `dispatchTouchEvent`, i.e. before Compose handles the event.
 */
object RawPointer {
    @Volatile
    var position: Offset = Offset.Zero
        private set

    fun update(ev: MotionEvent) {
        position = Offset(ev.rawX, ev.rawY)
    }
}
