package com.example.speedmeter.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/** First day of the billing cycle that contains [today]. cycleDay is 1..28 so every month has it. */
fun cycleStart(today: LocalDate, cycleDay: Int): LocalDate {
    require(cycleDay in 1..28) { "cycleDay must be 1..28" }
    return if (today.dayOfMonth >= cycleDay) today.withDayOfMonth(cycleDay)
    else today.minusMonths(1).withDayOfMonth(cycleDay)
}

/** The cycle runs from cycleStart (inclusive) to this date (exclusive). */
fun cycleEnd(start: LocalDate): LocalDate = start.plusMonths(1)

fun daysLeftInCycle(today: LocalDate, start: LocalDate): Long =
    ChronoUnit.DAYS.between(today, cycleEnd(start))

/** Alert thresholds (percent of the cap) that [used] has reached. */
fun thresholdsReached(used: Long, cap: Long, thresholds: List<Int> = listOf(80, 100)): List<Int> {
    if (cap <= 0L) return emptyList()
    return thresholds.filter { used * 100L >= cap * it.toLong() }
}

/**
 * The day the cap will be reached at the current pace, or null when it will not be reached this
 * cycle or there is too little data (under 3 days) for a fair guess. If already over, today.
 */
fun projectedCapDate(used: Long, cap: Long, today: LocalDate, start: LocalDate): LocalDate? {
    if (cap <= 0L) return null
    if (used >= cap) return today
    val elapsedDays = ChronoUnit.DAYS.between(start, today) + 1
    if (elapsedDays < 3 || used <= 0L) return null
    val perDay = used.toDouble() / elapsedDays
    val daysToCap = ceil((cap - used) / perDay).toLong()
    val date = today.plusDays(daysToCap)
    return if (date.isBefore(cycleEnd(start))) date else null
}

enum class PlanError { CycleDay, Cap }

/** Server-style validation, kept in one place so the screen and the database agree. Null = valid. */
fun validatePlan(cycleDay: Int, capBytes: Long?): PlanError? = when {
    cycleDay !in 1..28 -> PlanError.CycleDay
    capBytes != null && capBytes <= 0L -> PlanError.Cap
    else -> null
}
