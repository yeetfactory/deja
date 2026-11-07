package dev.rishikesh.deja.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A sighting reduced to the two facts that history views need. */
data class HistoryPoint(val time: Long, val placeId: Long?)

/** One continuous stay near a device: sightings at one place with no long gap between them. */
data class Visit(val placeId: Long?, val start: Long, val end: Long, val sightings: Int)

data class DayRow(val date: LocalDate, val hours: IntArray) {
    val total: Int get() = hours.sum()
}

/**
 * The vocabulary, used the same way everywhere:
 *  - a sighting is one stored reading, at most one per five minutes per place;
 *  - a visit is a run of sightings with no long gap, so a day at home is one visit;
 *  - an encounter is the whole record of one private address.
 */
object History {
    const val VISIT_GAP = 30 * MatchEngine.MINUTE
    const val DAYS = 7

    fun visits(points: List<HistoryPoint>): List<Visit> {
        val visits = mutableListOf<Visit>()
        for (point in points.sortedBy { it.time }) {
            val last = visits.lastOrNull()
            if (last != null && last.placeId == point.placeId && point.time - last.end < VISIT_GAP) {
                visits[visits.lastIndex] = last.copy(end = point.time, sightings = last.sightings + 1)
            } else {
                visits += Visit(point.placeId, point.time, point.time, 1)
            }
        }
        return visits
    }

    /** Oldest day first. Every day is present, even an empty one, so gaps read as gaps. */
    fun week(times: List<Long>, now: Long, zone: ZoneId): List<DayRow> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val rows = (DAYS - 1 downTo 0).map { DayRow(today.minusDays(it.toLong()), IntArray(24)) }
        val byDate = rows.associateBy { it.date }
        for (time in times) {
            val moment = Instant.ofEpochMilli(time).atZone(zone)
            byDate[moment.toLocalDate()]?.let { it.hours[moment.hour]++ }
        }
        return rows
    }
}
