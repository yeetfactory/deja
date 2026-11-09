package dev.rishikesh.deja.core

import dev.rishikesh.deja.data.Place
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A location reading. [accuracy] is the radius in metres the true position is likely inside. */
data class GeoFix(
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val name: String? = null,
)

sealed interface Resolution {
    /** The fix belongs to this place. [updated] carries a refined centre when the fix was good. */
    data class Known(
        val place: Place,
        val updated: Place?,
    ) : Resolution

    /** Two fixes agree on somewhere new, so it is worth creating. */
    data class Create(
        val fix: GeoFix,
    ) : Resolution

    /** Possibly somewhere new. Wait for a second fix, so passing through leaves no place behind. */
    data class Candidate(
        val fix: GeoFix,
    ) : Resolution

    /** Too vague to place at all. Better no place than a false one. */
    data object Unresolved : Resolution
}

/**
 * Turns noisy fixes into stable places. Drift is the enemy: a phone sitting still can report
 * positions a hundred metres apart, and each false new place reads as false movement.
 */
object PlaceClusterer {
    const val BASE_RADIUS_METERS = 120.0
    const val MAX_ACCURACY_BONUS_METERS = 100.0

    /** Fixes vaguer than this are never used. */
    const val MAX_USABLE_ACCURACY = 200f

    /**
     * Fixes vaguer than this can match a place, but not found one. Balanced-power location reports
     * a flat 100 m on real phones, so this has to sit above that or no place would ever exist.
     */
    const val MAX_FOUNDING_ACCURACY = 120f

    /** Fixes vaguer than this can't move a place's centre, so a good centre is not dragged around. */
    const val MAX_REFINING_ACCURACY = 75f

    /** Two candidates this close are the same somewhere new. */
    const val CANDIDATE_RADIUS_METERS = 100.0

    /** Places closer than this are suggested as duplicates. */
    const val DUPLICATE_METERS = 200.0

    private const val MAX_SAMPLES = 200

    fun metersBetween(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a =
            sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 6_371_000 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun metersBetween(
        a: Place,
        b: Place,
    ) = metersBetween(a.lat, a.lng, b.lat, b.lng)

    fun resolve(
        places: List<Place>,
        fix: GeoFix,
        candidate: GeoFix?,
    ): Resolution {
        if (fix.accuracy > MAX_USABLE_ACCURACY) return Resolution.Unresolved

        val nearest = places.minByOrNull { metersBetween(fix.lat, fix.lng, it.lat, it.lng) }
        if (nearest != null) {
            val distance = metersBetween(fix.lat, fix.lng, nearest.lat, nearest.lng)
            val reach = BASE_RADIUS_METERS + min(fix.accuracy.toDouble(), MAX_ACCURACY_BONUS_METERS)
            if (distance <= reach) return Resolution.Known(nearest, refine(nearest, fix))
        }

        if (fix.accuracy > MAX_FOUNDING_ACCURACY) return Resolution.Unresolved
        val confirmed =
            candidate != null &&
                metersBetween(fix.lat, fix.lng, candidate.lat, candidate.lng) <= CANDIDATE_RADIUS_METERS
        return if (confirmed || fix.name != null) Resolution.Create(fix) else Resolution.Candidate(fix)
    }

    /** A running mean. The sample cap keeps a place able to follow a slow correction. */
    private fun refine(
        place: Place,
        fix: GeoFix,
    ): Place? {
        if (fix.accuracy > MAX_REFINING_ACCURACY) return null
        val weight = place.samples.toDouble()
        return place.copy(
            lat = (place.lat * weight + fix.lat) / (weight + 1),
            lng = (place.lng * weight + fix.lng) / (weight + 1),
            samples = min(place.samples + 1, MAX_SAMPLES),
        )
    }

    /** Whether two places are really apart, rather than one place seen through location error. */
    fun apart(
        a: Place?,
        b: Place?,
        accuracy: Float?,
    ): Boolean {
        if (a == null || b == null || a.id == b.id) return false
        val slack = min(accuracy?.toDouble() ?: 50.0, MAX_ACCURACY_BONUS_METERS)
        return metersBetween(a, b) > FollowEngine.MIN_SEPARATION_METERS + slack
    }

    /** Pairs of places close enough that they are probably one place, nearest first. */
    fun duplicates(places: List<Place>): List<Pair<Place, Place>> =
        buildList {
            for (i in places.indices) {
                for (j in i + 1 until places.size) {
                    if (metersBetween(places[i], places[j]) <= DUPLICATE_METERS) add(places[i] to places[j])
                }
            }
        }.sortedBy { metersBetween(it.first, it.second) }
}
