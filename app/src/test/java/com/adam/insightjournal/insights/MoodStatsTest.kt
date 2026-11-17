package com.adam.insightjournal.insights

import com.adam.insightjournal.data.model.JournalEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

class MoodStatsTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 16) // a Monday

    private fun entry(daysAgo: Long, mood: Int? = null) = JournalEntry(
        id = "e$daysAgo-$mood",
        entryDate = today.minusDays(daysAgo).atTime(12, 0).toInstant(zone),
        mood = mood,
    )

    private fun stats(entries: List<JournalEntry>, days: Long = 7) =
        moodStats(entries, from = today.minusDays(days - 1), to = today, zone = zone)

    @Test
    fun `no rated entries gives empty stats`() {
        val result = stats(listOf(entry(0), entry(1)))
        assertNull(result.average)
        assertNull(result.previousAverage)
        assertEquals(0, result.ratedEntryCount)
        assertEquals(emptyList<DayMood>(), result.days)
        assertEquals(mapOf(1 to 0, 2 to 0, 3 to 0, 4 to 0, 5 to 0), result.distribution)
    }

    @Test
    fun `average and distribution cover only the range, both ends included`() {
        val result = stats(listOf(entry(0, 5), entry(6, 3), entry(7, 1)))
        assertEquals(4.0, result.average!!, 1e-9)
        assertEquals(2, result.ratedEntryCount)
        assertEquals(1, result.distribution[5])
        assertEquals(1, result.distribution[3])
        assertEquals(0, result.distribution[1])
    }

    @Test
    fun `previous average is the equally long stretch just before`() {
        // Range is days 0..6 ago; previous is days 7..13 ago; day 14 is outside both.
        val result = stats(listOf(entry(0, 4), entry(7, 2), entry(13, 4), entry(14, 5)))
        assertEquals(3.0, result.previousAverage!!, 1e-9)
    }

    @Test
    fun `entries on the same day are averaged into one point, oldest day first`() {
        val result = stats(listOf(entry(0, 5), entry(0, 2), entry(2, 4)))
        assertEquals(
            listOf(DayMood(today.minusDays(2), 4.0, 1), DayMood(today, 3.5, 2)),
            result.days,
        )
    }

    @Test
    fun `weekday averages only include weekdays that have a mood`() {
        // today is a Monday; 7 days ago too; yesterday was a Sunday.
        val result = stats(listOf(entry(0, 5), entry(7, 3), entry(1, 2)), days = 30)
        assertEquals(mapOf(DayOfWeek.MONDAY to 4.0, DayOfWeek.SUNDAY to 2.0), result.byWeekday)
    }

    @Test
    fun `a mood outside the scale is ignored`() {
        val result = stats(listOf(entry(0, 9), entry(1, 0), entry(2, 4)))
        assertEquals(1, result.ratedEntryCount)
        assertEquals(4.0, result.average!!, 1e-9)
    }

    @Test
    fun `the day is taken in the given time zone`() {
        // 23:30 UTC on the 15th is already the 16th in UTC+2.
        val late = JournalEntry(id = "late", entryDate = today.minusDays(1).atTime(23, 30).toInstant(zone), mood = 4)
        val plusTwo = ZoneOffset.ofHours(2)
        assertEquals(today, moodStats(listOf(late), today, today, plusTwo).days.single().date)
        assertEquals(0, moodStats(listOf(late), today, today, zone).ratedEntryCount)
    }

    // ---- streaks ----

    private fun streaks(vararg daysAgo: Long) = journalingStreaks(daysAgo.map { entry(it) }, today, zone)

    @Test
    fun `no entries means no streak`() {
        assertEquals(Streaks(0, 0), streaks())
    }

    @Test
    fun `current streak counts back from today`() {
        assertEquals(Streaks(current = 3, longest = 3), streaks(0, 1, 2, 4))
    }

    @Test
    fun `a streak that reached yesterday is still current`() {
        assertEquals(Streaks(current = 2, longest = 2), streaks(1, 2))
    }

    @Test
    fun `a streak that ended before yesterday is over but still the longest`() {
        assertEquals(Streaks(current = 0, longest = 3), streaks(2, 3, 4))
    }

    @Test
    fun `several entries on one day count once`() {
        assertEquals(Streaks(current = 2, longest = 2), streaks(0, 0, 0, 1))
    }

    @Test
    fun `longest streak can be older than the current one`() {
        assertEquals(Streaks(current = 1, longest = 4), streaks(0, 10, 11, 12, 13))
    }
}
