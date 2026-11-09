package dev.rishikesh.deja.data

import androidx.room.withTransaction
import dev.rishikesh.deja.core.Csv
import dev.rishikesh.deja.core.EvidenceCodec
import dev.rishikesh.deja.core.FollowEngine
import dev.rishikesh.deja.core.GeoFix
import dev.rishikesh.deja.core.Glance
import dev.rishikesh.deja.core.GlanceEngine
import dev.rishikesh.deja.core.MatchEngine
import dev.rishikesh.deja.core.Merge
import dev.rishikesh.deja.core.PlaceClusterer
import dev.rishikesh.deja.core.Resolution
import dev.rishikesh.deja.core.Verdict
import java.time.Instant
import kotlin.math.min

data class Alert(
    val eventId: Long,
    val device: Device,
    val type: EventType,
    val fromPlace: String?,
    val toPlace: String?,
    val summary: String,
)

data class IngestResult(
    val seen: List<Device>,
    val returningIds: Set<Long>,
    val alerts: List<Alert>,
)

/** The user's alert switches, as the rules need them. */
data class AlertPrefs(
    val following: Boolean = true,
    val watched: Boolean = true,
    val newTracker: Boolean = false,
)

/** What to keep, and for how long. */
data class RetentionPolicy(
    val historyDays: Int = 30,
    val unnamedDays: Int = 30,
    val encounterDays: Int = 7,
    val keepSavedHistory: Boolean = true,
)

class Repository(
    private val db: DejaDatabase,
) {
    val dao: DejaDao = db.dao()

    /** A possible new place, held until a second fix agrees with it. */
    private var candidate: GeoFix? = null

    /** Finds the place at this fix, or null when the fix cannot honestly be placed yet. */
    suspend fun resolvePlace(fix: GeoFix): Place? =
        db.withTransaction {
            when (val resolution = PlaceClusterer.resolve(dao.places(), fix, candidate)) {
                is Resolution.Known -> {
                    candidate = null
                    resolution.updated?.let { dao.updatePlace(it) }
                    resolution.updated ?: resolution.place
                }

                is Resolution.Create -> {
                    candidate = null
                    val id = dao.insertPlace(Place(name = fix.name ?: "New place", lat = fix.lat, lng = fix.lng))
                    val name = fix.name ?: "Place $id"
                    if (fix.name == null) dao.autoNamePlace(id, name)
                    Place(id = id, name = name, lat = fix.lat, lng = fix.lng)
                }

                is Resolution.Candidate -> {
                    candidate = resolution.fix
                    null
                }

                Resolution.Unresolved -> {
                    null
                }
            }
        }

    suspend fun ingest(
        batch: List<Observation>,
        place: Place?,
        accuracy: Float?,
        now: Long,
        prefs: AlertPrefs = AlertPrefs(),
    ): IngestResult =
        db.withTransaction {
            val places = dao.places().associateBy { it.id }
            val seen = mutableListOf<Device>()
            val returning = mutableSetOf<Long>()
            val alerts = mutableListOf<Alert>()

            for (obs in batch) {
                val previous = dao.deviceByKey(obs.key)
                val time = obs.observedAt ?: now
                var device =
                    if (previous != null) {
                        Merge.device(previous, obs, place?.id, time)
                    } else {
                        val fresh = Merge.newDevice(obs, place?.id, time)
                        fresh.copy(id = dao.insertDevice(fresh))
                    }

                val lastHere = dao.lastSightingAt(device.id, place?.id)
                if (lastHere == null || time - lastHere >= SIGHTING_INTERVAL) {
                    dao.insertSighting(
                        Sighting(deviceId = device.id, placeId = place?.id, time = time, rssi = obs.rssi, accuracy = accuracy),
                    )
                }

                val from = previous?.lastPlaceId
                val moved = PlaceClusterer.apart(places[from], place, accuracy)
                val judged = judge(previous, device, time, moved)
                if (judged != null) {
                    val (verdict, summary, evidence) = judged
                    val allowed =
                        when (verdict.type) {
                            EventType.FOLLOWING -> prefs.following
                            EventType.RETURNED -> prefs.watched
                            EventType.NEW_TRACKER -> prefs.newTracker
                            EventType.NEW_FIND_MY -> false
                        }
                    val delivery =
                        when {
                            !verdict.notify -> Delivery.NOT_ELIGIBLE
                            allowed -> Delivery.PENDING
                            else -> Delivery.DISABLED_IN_SETTINGS
                        }
                    val eventId =
                        dao.insertEvent(
                            Event(
                                deviceId = device.id,
                                type = verdict.type,
                                time = time,
                                fromPlaceId = from,
                                toPlaceId = place?.id,
                                eligible = verdict.notify,
                                delivery = delivery,
                                summary = summary,
                                evidence = evidence,
                            ),
                        )
                    if (verdict.type == EventType.RETURNED || verdict.type == EventType.FOLLOWING) {
                        returning += device.id
                        // The cooldown runs even when the alert is switched off, or this would log every burst.
                        if (verdict.notify) device = device.copy(lastAlertAt = time)
                    }
                    if (delivery == Delivery.PENDING) {
                        alerts += Alert(eventId, device, verdict.type, places[from]?.name, place?.name, summary)
                    }
                }
                dao.updateDevice(device)
                seen += device
            }
            IngestResult(seen, returning, alerts)
        }

    private suspend fun judge(
        previous: Device?,
        device: Device,
        now: Long,
        moved: Boolean,
    ): Triple<Verdict, String, String>? {
        if (previous != null && FollowEngine.eligible(device) && MatchEngine.cooledDown(previous, now)) {
            val since = now - FollowEngine.WINDOW
            if (dao.distinctPlacesSince(device.id, since) >= FollowEngine.MIN_PLACES) {
                val assessment = FollowEngine.assess(dao.trail(device.id, since))
                if (assessment.following) {
                    return Triple(
                        Verdict(EventType.FOLLOWING, notify = true),
                        assessment.summary,
                        EvidenceCodec.encode(assessment.steps),
                    )
                }
            }
        }
        val verdict = MatchEngine.judge(previous, device, now, moved) ?: return null
        val summary =
            when (verdict.type) {
                EventType.NEW_TRACKER -> {
                    device.evidence
                        .lineSequence()
                        .firstOrNull()
                        .orEmpty()
                }

                EventType.RETURNED -> {
                    "Away for ${FollowEngine.duration(now - previous!!.lastSeen)}."
                }

                else -> {
                    ""
                }
            }
        return Triple(verdict, summary, "")
    }

    /** The summary for the top of Nearby. */
    suspend fun glance(
        inRange: List<Long>,
        placeId: Long?,
        dayStart: Long,
        now: Long,
    ): Glance {
        val places = dao.places().associateBy { it.id }
        val withYou = GlanceEngine.withYou(dao.devicePlacesSince(dayStart), places)
        if (placeId == null) return Glance(withYou = withYou)
        return GlanceEngine.sort(
            withYou = withYou,
            inRange = inRange,
            firstHeard = dao.firstHeardAt(placeId, inRange),
            placeFirstHeard = dao.placeFirstHeard(placeId),
            now = now,
        )
    }

    /** Saves only the user's choices, so a scan running at the same moment is not overwritten. */
    suspend fun updateFlags(device: Device) = dao.updateFlags(device.id, device.label, device.starred, device.ignored, device.owned)

    /** The device stops alerting, so its open alerts are settled too. */
    suspend fun markMine(deviceId: Long) =
        db.withTransaction {
            dao.markOwned(deviceId)
            dao.dismissEventsFor(deviceId)
        }

    suspend fun markIgnored(deviceId: Long) =
        db.withTransaction {
            dao.markIgnored(deviceId)
            dao.dismissEventsFor(deviceId)
        }

    /** Forgets where and when a device was seen, while keeping its name and flags. */
    suspend fun deleteDeviceHistory(deviceId: Long) =
        db.withTransaction {
            dao.deleteSightings(deviceId)
            dao.deleteEvents(deviceId)
            dao.resetDeviceHistory(deviceId)
            dao.deleteUnusedPlaces()
        }

    suspend fun deleteDevice(deviceId: Long) =
        db.withTransaction {
            dao.deleteDevice(deviceId)
            dao.deleteUnusedPlaces()
        }

    suspend fun clearAll() =
        db.withTransaction {
            dao.deleteAllDevices()
            dao.deleteAllPlaces()
            candidate = null
        }

    /** Folds [fromId] into [intoId], so everything that happened there now happened at [intoId]. */
    suspend fun mergePlaces(
        fromId: Long,
        intoId: Long,
    ) = db.withTransaction {
        if (fromId == intoId) return@withTransaction
        val places = dao.places().associateBy { it.id }
        val from = places[fromId] ?: return@withTransaction
        val into = places[intoId] ?: return@withTransaction

        dao.moveSightings(fromId, intoId)
        dao.moveDevicePlaces(fromId, intoId)
        dao.moveEventOrigins(fromId, intoId)
        dao.moveEventDestinations(fromId, intoId)
        for (event in dao.eventsWithEvidence()) {
            val steps = EvidenceCodec.decode(event.evidence)
            if (steps.none { it.placeId == fromId }) continue
            val moved = steps.map { if (it.placeId == fromId) it.copy(placeId = intoId, placeName = into.name) else it }
            dao.updateEvidence(event.id, EvidenceCodec.encode(moved))
        }

        val total = (from.samples + into.samples).toDouble()
        dao.updatePlace(
            into.copy(
                lat = (from.lat * from.samples + into.lat * into.samples) / total,
                lng = (from.lng * from.samples + into.lng * into.samples) / total,
                samples = min(from.samples + into.samples, 200),
            ),
        )
        dao.deletePlace(fromId)
    }

    /** Applies retention. Named devices keep their row however old their history gets. */
    suspend fun prune(
        now: Long,
        policy: RetentionPolicy,
    ) = db.withTransaction {
        val history = now - policy.historyDays * MatchEngine.DAY
        dao.pruneSightings(history, policy.keepSavedHistory)
        dao.pruneEvents(history, policy.keepSavedHistory)
        dao.pruneDevices(now - policy.unnamedDays * MatchEngine.DAY)
        dao.pruneEncounters(now - policy.encounterDays * MatchEngine.DAY)
        dao.clearOrphanedLastPlaces()
        dao.deleteUnusedPlaces()
    }

    /** The observations behind an event: its evidence window, or the last day for a simple event. */
    suspend fun exportEvent(eventId: Long): String? {
        val event = dao.eventById(eventId) ?: return null
        val steps = EvidenceCodec.decode(event.evidence)
        val from = steps.minOfOrNull { it.start } ?: (event.time - MatchEngine.DAY)
        val rows = dao.sightingsBetween(event.deviceId, from, event.time)
        return buildString {
            appendLine(Csv.row(listOf("device", "event", "event_time", "summary")))
            appendLine(Csv.row(listOf(event.deviceName, event.type.name, iso(event.time), event.summary)))
            appendLine()
            appendLine(Csv.row(listOf("stop_start", "stop_end", "place", "readings", "strongest_rssi_dbm", "accuracy_m")))
            steps.forEach {
                appendLine(Csv.row(listOf(iso(it.start), iso(it.end), it.placeName, it.readings, it.strongestRssi, it.accuracy)))
            }
            appendLine()
            appendLine(Csv.row(listOf("sighting_time", "place", "rssi_dbm", "accuracy_m")))
            rows.forEach { appendLine(Csv.row(listOf(iso(it.time), it.placeName, it.rssi, it.accuracy))) }
        }
    }

    private fun iso(time: Long) = Instant.ofEpochMilli(time).toString()

    companion object {
        const val SIGHTING_INTERVAL = 5 * MatchEngine.MINUTE
    }
}
