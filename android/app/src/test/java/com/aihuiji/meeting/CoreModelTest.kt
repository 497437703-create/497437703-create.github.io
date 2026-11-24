package com.aihuiji.meeting

import com.aihuiji.meeting.core.model.MeetingCode
import com.aihuiji.meeting.core.model.MeetingState
import com.aihuiji.meeting.core.model.MeetingStateMachine
import com.aihuiji.meeting.core.model.formatClock
import com.aihuiji.meeting.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CoreModelTest {
    @Test
    fun meetingCodesAreSixUnambiguousCharacters() {
        val rnd = Random(42)
        repeat(500) {
            val code = MeetingCode.generate(rnd)
            assertTrue(code, MeetingCode.isValid(code))
            assertFalse(code.any { it in "01IO" })
        }
        assertFalse(MeetingCode.isValid("ABC12O"))
        assertFalse(MeetingCode.isValid("ABCDE"))
    }

    @Test
    fun clockFormat() {
        assertEquals("00:00:00", formatClock(0))
        assertEquals("00:01:05", formatClock(65_400))
        assertEquals("01:02:03", formatClock(3_723_000))
    }

    @Test
    fun stateMachineFollowsLifecycle() {
        val path = listOf(
            MeetingState.IDLE, MeetingState.PREPARING, MeetingState.LIVE, MeetingState.PAUSED,
            MeetingState.LIVE, MeetingState.ENDING, MeetingState.GENERATING, MeetingState.DONE,
        )
        path.zipWithNext().forEach { (a, b) -> assertTrue("$a→$b", MeetingStateMachine.canTransition(a, b)) }
        assertFalse(MeetingStateMachine.canTransition(MeetingState.LIVE, MeetingState.DONE))
        assertFalse(MeetingStateMachine.canTransition(MeetingState.IDLE, MeetingState.LIVE))
        // Template switch / retry regenerate from a finished or failed meeting.
        assertTrue(MeetingStateMachine.canTransition(MeetingState.DONE, MeetingState.GENERATING))
        assertTrue(MeetingStateMachine.canTransition(MeetingState.FAILED, MeetingState.GENERATING))
    }

    @Test
    fun adminPinRules() {
        assertTrue(SettingsStore.isValidPin("1234"))
        assertTrue(SettingsStore.isValidPin("123456"))
        assertFalse(SettingsStore.isValidPin("123"))
        assertFalse(SettingsStore.isValidPin("1234567"))
        assertFalse(SettingsStore.isValidPin("12a4"))
        assertEquals(SettingsStore.hashPin("2580"), SettingsStore.hashPin("2580"))
        assertNotEquals(SettingsStore.hashPin("2580"), SettingsStore.hashPin("0852"))
        assertFalse(SettingsStore.hashPin("2580").contains("2580"))
    }
}
