package dev.rishikesh.deja.core

import dev.rishikesh.deja.data.Device
import dev.rishikesh.deja.data.IdentityConfidence
import kotlin.math.max
import kotlin.math.min

/** One stored sighting with its place, as the following rules need it. */
data class TrailPoint(
    val time: Long,
    val placeId: Long,
    val placeName: String,
    val lat: Double,
    val lng: Double,
    val accuracy: Float?,
    val rssi: Int,
)

/** A stretch of the trail spent at one place. */
data class EvidenceStep(
    val start: Long,
    val end: Long,
    val placeId: Long,
    val placeName: String,
    val readings: Int,
    val strongestRssi: Int,
    val accuracy: Float?,
)

data class Assessment(
    val following: Boolean,
    val summary: String,
    val steps: List<EvidenceStep>,
)

/**
 * Decides whether one device really has moved with you. Being near a device proves little: drift
 * makes one place look like several, and a neighbour's gear is heard at home every day. So it
 * takes repeated readings across places that are provably apart, in sequence, over real time.
 */
object FollowEngine {
    const val WINDOW = MatchEngine.DAY
    const val MIN_PLACES = 3
    const val MIN_OBSERVATIONS = 5
    const val MIN_SPAN = 30 * MatchEngine.MINUTE

    /** Places must be this far apart, plus their location error, to count as different. */
    const val MIN_SEPARATION_METERS = 300.0

    /** The furthest two places must be this far apart: a walk, not a wander around a block. */
    const val MIN_SPREAD_METERS = 800.0

    private const val DEFAULT_ACCURACY = 50.0
    private const val MAX_ACCURACY = 150.0

    /**
     * A private address is excluded even when it repeats: it can only mean this one address, and
     * in practice the ones that keep up with you are your own phone and watch.
     */
    fun eligible(device: Device): Boolean =
        !device.owned && !device.ignored && device.identity != IdentityConfidence.ADDRESS_ONLY

    private class Cluster(val lat: Double, val lng: Double, val slack: Double, val id: Int)

    private fun slack(points: List<TrailPoint>): Double {
        val values = points.mapNotNull { it.accuracy?.toDouble() }.sorted()
        val median = values.getOrNull(values.size / 2) ?: DEFAULT_ACCURACY
        return min(median, MAX_ACCURACY)
    }

    /** Groups places that cannot be told apart, so duplicates and drift collapse into one. */
    private fun cluster(trail: List<TrailPoint>): Map<Long, Cluster> {
        val clusters = mutableListOf<Cluster>()
        val assigned = HashMap<Long, Cluster>()
        trail.groupBy { it.placeId }.forEach { (placeId, points) ->
            val first = points.first()
            val own = slack(points)
            val match = clusters.firstOrNull {
                PlaceClusterer.metersBetween(first.lat, first.lng, it.lat, it.lng) <=
                    MIN_SEPARATION_METERS + own + it.slack
            }
            assigned[placeId] = match ?: Cluster(first.lat, first.lng, own, clusters.size).also { clusters += it }
        }
        return assigned
    }

    private fun steps(trail: List<TrailPoint>, clusters: Map<Long, Cluster>): List<EvidenceStep> {
        val steps = mutableListOf<MutableList<TrailPoint>>()
        for (point in trail) {
            val current = steps.lastOrNull()
            if (current != null && clusters[current.last().placeId] === clusters[point.placeId]) {
                current += point
            } else {
                steps += mutableListOf(point)
            }
        }
        return steps.map { points ->
            EvidenceStep(
                start = points.first().time,
                end = points.last().time,
                placeId = points.first().placeId,
                placeName = points.first().placeName,
                readings = points.size,
                strongestRssi = points.maxOf { it.rssi },
                accuracy = points.mapNotNull { it.accuracy }.minOrNull(),
            )
        }
    }

    private fun spread(clusters: Collection<Cluster>): Double {
        var widest = 0.0
        val list = clusters.toList()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                widest = max(widest, PlaceClusterer.metersBetween(list[i].lat, list[i].lng, list[j].lat, list[j].lng))
            }
        }
        return widest
    }

    fun assess(points: List<TrailPoint>): Assessment {
        val trail = points.sortedBy { it.time }
        if (trail.isEmpty()) return Assessment(false, "No located sightings in the last day.", emptyList())

        val clusters = cluster(trail)
        val distinct = clusters.values.toSet()
        val steps = steps(trail, clusters)
        val span = trail.last().time - trail.first().time
        val widest = spread(distinct)

        val following = distinct.size >= MIN_PLACES &&
            trail.size >= MIN_OBSERVATIONS &&
            span >= MIN_SPAN &&
            widest >= MIN_SPREAD_METERS

        val summary = "Seen ${trail.size} times at ${distinct.size} separate " +
            "${if (distinct.size == 1) "place" else "places"} over ${duration(span)}" +
            if (distinct.size > 1) ", up to ${distance(widest)} apart." else "."
        return Assessment(following, summary, steps)
    }

    fun duration(ms: Long): String {
        val minutes = ms / MatchEngine.MINUTE
        return when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    fun distance(meters: Double): String =
        if (meters < 1000) "${(meters / 10).toInt() * 10} m" else "%.1f km".format(meters / 1000)
}

/** Stores a timeline as text in the event row, so the alert stays explainable as sightings age out. */
object EvidenceCodec {
    private const val FIELDS = 7

    fun encode(steps: List<EvidenceStep>): String = steps.joinToString("\n") {
        listOf(it.start, it.end, it.placeId, it.readings, it.strongestRssi, it.accuracy ?: "", it.placeName.replace('\n', ' '))
            .joinToString("|")
    }

    fun decode(text: String): List<EvidenceStep> = text.lineSequence().mapNotNull { line ->
        val parts = line.split("|", limit = FIELDS)
        if (parts.size < FIELDS) return@mapNotNull null
        EvidenceStep(
            start = parts[0].toLongOrNull() ?: return@mapNotNull null,
            end = parts[1].toLongOrNull() ?: return@mapNotNull null,
            placeId = parts[2].toLongOrNull() ?: return@mapNotNull null,
            readings = parts[3].toIntOrNull() ?: return@mapNotNull null,
            strongestRssi = parts[4].toIntOrNull() ?: return@mapNotNull null,
            accuracy = parts[5].toFloatOrNull(),
            placeName = parts[6],
        )
    }.toList()
}
