package com.aihuiji.meeting.core.model

object MeetingStateMachine {
    private val allowed: Map<MeetingState, Set<MeetingState>> = mapOf(
        MeetingState.IDLE to setOf(MeetingState.PREPARING),
        MeetingState.PREPARING to setOf(MeetingState.LIVE, MeetingState.IDLE),
        MeetingState.LIVE to setOf(MeetingState.PAUSED, MeetingState.ENDING),
        MeetingState.PAUSED to setOf(MeetingState.LIVE, MeetingState.ENDING),
        MeetingState.ENDING to setOf(MeetingState.GENERATING, MeetingState.FAILED),
        MeetingState.GENERATING to setOf(MeetingState.DONE, MeetingState.FAILED),
        MeetingState.FAILED to setOf(MeetingState.GENERATING),
        MeetingState.DONE to setOf(MeetingState.GENERATING),
    )

    fun canTransition(from: MeetingState, to: MeetingState): Boolean = to in allowed[from].orEmpty()

    fun require(from: MeetingState, to: MeetingState) {
        check(canTransition(from, to)) { "Illegal meeting transition $from → $to" }
    }
}
