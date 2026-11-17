package com.adam.insightjournal.insights

import com.adam.insightjournal.data.model.JournalEntry
import com.adam.insightjournal.data.model.Mood
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** The average mood of the entries written about one day. */
data class DayMood(val date: LocalDate, val average: Double, val entryCount: Int)

/** Mood numbers for one stretch of days. Built by [moodStats]; plain data for the screen to draw. */
data class MoodStats(
    val from: LocalDate,
    val to: LocalDate,
    /** One item per day that has a rated entry, oldest first. Days without one are absent. */
    val days: List<DayMood>,
    /** Average over rated entries, or null when there are none. */
    val average: Double?,
    /** The same average for the equally long stretch just before [from], for "up/down from". */
    val previousAverage: Double?,
    /** How many entries got each mood, for every value [Mood.MIN]..[Mood.MAX]. */
    val distribution: Map<Int, Int>,
    /** Average mood per weekday; weekdays without a rated entry are absent. */
    val byWeekday: Map<DayOfWeek, Double>,
    val ratedEntryCount: Int,
)

/**
 * Mood statistics for entries dated [from]..[to] (both included). Entries
 * without a mood are ignored. Averages are per entry, so a day with two entries
 * counts twice in [MoodStats.average]; [MoodStats.days] averages within the day.
 */
fun moodStats(
    entries: List<JournalEntry>,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): MoodStats {
    val rated = entries.mapNotNull { entry ->
        entry.mood?.takeIf(Mood::isValid)?.let { entry.entryDate.atZone(zone).toLocalDate() to it }
    }
    val inRange = rated.filter { (date, _) -> date in from..to }

    val length = to.toEpochDay() - from.toEpochDay() + 1
    val previousFrom = from.minusDays(length)
    val previous = rated.filter { (date, _) -> date >= previousFrom && date < from }

    return MoodStats(
        from = from,
        to = to,
        days = inRange.groupBy({ it.first }, { it.second })
            .map { (date, moods) -> DayMood(date, moods.average(), moods.size) }
            .sortedBy { it.date },
        average = inRange.map { it.second }.averageOrNull(),
        previousAverage = previous.map { it.second }.averageOrNull(),
        distribution = Mood.all.associateWith { value -> inRange.count { it.second == value } },
        byWeekday = inRange.groupBy({ it.first.dayOfWeek }, { it.second }).mapValues { it.value.average() },
        ratedEntryCount = inRange.size,
    )
}

private fun List<Int>.averageOrNull(): Double? = if (isEmpty()) null else average()

/** Runs of consecutive days with at least one entry. */
data class Streaks(val current: Int, val longest: Int)

/**
 * [Streaks.current] counts back from [today]; a streak that reached yesterday is
 * still alive (today isn't over yet), one that ended earlier is 0.
 */
fun journalingStreaks(
    entries: List<JournalEntry>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Streaks {
    val days = entries.map { it.entryDate.atZone(zone).toLocalDate() }.toSortedSet()
    if (days.isEmpty()) return Streaks(0, 0)

    var longest = 0
    var run = 0
    var previous: LocalDate? = null
    for (day in days) {
        run = if (previous != null && previous.plusDays(1) == day) run + 1 else 1
        longest = maxOf(longest, run)
        previous = day
    }

    var current = 0
    var day = if (today in days) today else today.minusDays(1)
    while (day in days) {
        current++
        day = day.minusDays(1)
    }
    return Streaks(current, longest)
}
