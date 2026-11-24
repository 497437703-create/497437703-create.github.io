package com.aihuiji.meeting.core.model

import kotlin.random.Random

/** F-01: six uppercase alphanumerics without look-alike characters (0/O, 1/I). */
object MeetingCode {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 6

    fun generate(random: Random = Random.Default): String =
        buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }
}

fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
