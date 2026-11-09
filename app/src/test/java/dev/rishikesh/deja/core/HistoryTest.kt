package dev.rishikesh.deja.core

import dev.rishikesh.deja.core.MatchEngine.HOUR
import dev.rishikesh.deja.core.MatchEngine.MINUTE
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HistoryTest {
    @Test fun `sightings with no long gap are one visit`() {
        val visits = History.visits(listOf(0L, 5L, 10L, 20L).map { HistoryPoint(it * MINUTE, 1) })
        assertEquals(1, visits.size)
        assertEquals(4, visits.single().sightings)
    }

    @Test fun `a long gap starts a separate visit`() {
        assertEquals(2, History.visits(listOf(HistoryPoint(0, 1), HistoryPoint(HOUR, 1))).size)
    }

    @Test fun `a change of place starts a separate visit`() {
        assertEquals(2, History.visits(listOf(HistoryPoint(0, 1), HistoryPoint(MINUTE, 2))).size)
    }

    @Test fun `order does not matter`() {
        assertEquals(1, History.visits(listOf(HistoryPoint(5 * MINUTE, 1), HistoryPoint(0, 1))).size)
    }

    @Test fun `the week always has seven days, oldest first, even when empty`() {
        val now =
            LocalDate
                .of(2026, 11, 7)
                .atTime(12, 0)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        val week = History.week(emptyList(), now, ZoneOffset.UTC)
        assertEquals(7, week.size)
        assertEquals(LocalDate.of(2026, 11, 1), week.first().date)
        assertEquals(LocalDate.of(2026, 11, 7), week.last().date)
    }

    @Test fun `the week counts far more than one page of sightings`() {
        val now =
            LocalDate
                .of(2026, 11, 7)
                .atTime(12, 0)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        val times = List(1000) { now - it * 5 * MINUTE }
        assertEquals(1000, History.week(times, now, ZoneOffset.UTC).sumOf { it.total })
    }

    @Test fun `older than a week is left out`() {
        val now =
            LocalDate
                .of(2026, 11, 7)
                .atTime(12, 0)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        assertEquals(0, History.week(listOf(now - 8 * MatchEngine.DAY), now, ZoneOffset.UTC).sumOf { it.total })
    }

    @Test fun `hours follow the local clock across a daylight saving change`() {
        val zone = ZoneId.of("Europe/Berlin")
        val now =
            LocalDate
                .of(2026, 10, 26)
                .atTime(12, 0)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        val sunday =
            LocalDate
                .of(2026, 10, 25)
                .atTime(23, 30)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        val row = History.week(listOf(sunday), now, zone).single { it.date == LocalDate.of(2026, 10, 25) }
        assertEquals(1, row.hours[23])
    }
}
