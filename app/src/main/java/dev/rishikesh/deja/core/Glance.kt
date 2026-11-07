package dev.rishikesh.deja.core

import dev.rishikesh.deja.data.Place

/** One device having been at one place, reduced to what the summary needs. */
data class DevicePlace(val deviceId: Long, val placeId: Long)

/** When a device was first heard at the current place. */
data class FirstHeard(val deviceId: Long, val firstHere: Long)

/** The answer to "is anything unusual around me?", for the top of the Nearby screen. */
data class Glance(
    /** At more than one of your places today. Includes devices that aren't in range right now. */
    val withYou: Set<Long> = emptySet(),
    val newHere: Set<Long> = emptySet(),
    val regulars: Set<Long> = emptySet(),
    /** Too little history at this place to say what is new, so nothing is called new. */
    val learning: Boolean = false,
    val placeKnown: Boolean = false,
)

/**
 * Early hints, deliberately looser than [FollowEngine]. Being at two places today is worth a look,
 * and is never an alert. Only addresses stable enough to recognise are counted, because a private
 * address can't say whether it was here yesterday.
 */
object GlanceEngine {
    /** Until a place has this much history, everything there would look new. */
    const val LEARNING_MS = MatchEngine.DAY

    /** A device first heard here more recently than this is new here. */
    const val NEW_MS = MatchEngine.DAY

    /** Devices seen at places that are really apart, so one drifting place doesn't count twice. */
    fun withYou(rows: List<DevicePlace>, places: Map<Long, Place>): Set<Long> =
        rows.groupBy { it.deviceId }.filterValues { visits ->
            val seen = visits.mapNotNull { places[it.placeId] }
            seen.indices.any { i ->
                (i + 1 until seen.size).any { j ->
                    PlaceClusterer.metersBetween(seen[i], seen[j]) > FollowEngine.MIN_SEPARATION_METERS
                }
            }
        }.keys

    /**
     * @param inRange the stable devices heard around now.
     * @param firstHeard when each of them was first heard at this place.
     * @param placeFirstHeard the earliest sighting of anything here, or null with no history.
     */
    fun sort(
        withYou: Set<Long>,
        inRange: List<Long>,
        firstHeard: List<FirstHeard>,
        placeFirstHeard: Long?,
        now: Long,
    ): Glance {
        val learning = placeFirstHeard == null || now - placeFirstHeard < LEARNING_MS
        if (learning) return Glance(withYou = withYou, learning = true, placeKnown = true)
        val first = firstHeard.associate { it.deviceId to it.firstHere }
        // Nothing on record here means this is its first time, so it's new.
        val (fresh, known) = inRange.partition { id -> first[id]?.let { now - it < NEW_MS } ?: true }
        return Glance(withYou, fresh.toSet(), known.toSet(), learning = false, placeKnown = true)
    }

    fun headline(glance: Glance): String {
        val count = glance.withYou.size
        return when {
            count == 0 -> "Nothing unusual"
            count == 1 -> "1 device has been at more than one of your places today"
            else -> "$count devices have been at more than one of your places today"
        }
    }

    fun detail(glance: Glance, nearby: Int): String {
        val heard = "$nearby nearby"
        return when {
            !glance.placeKnown -> "$heard · place not known yet"
            glance.learning -> "$heard · learning this place"
            else -> "$heard · ${glance.newHere.size} new here"
        }
    }
}
