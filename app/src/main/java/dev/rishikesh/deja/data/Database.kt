package dev.rishikesh.deja.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import dev.rishikesh.deja.core.HistoryPoint
import dev.rishikesh.deja.core.TrailPoint
import kotlinx.coroutines.flow.Flow

data class EventRow(
    val id: Long,
    val deviceId: Long,
    val type: EventType,
    val time: Long,
    val eligible: Boolean,
    val delivery: Delivery,
    val dismissed: Boolean,
    val summary: String,
    val evidence: String,
    val deviceLabel: String?,
    val broadcastName: String?,
    val descriptor: String?,
    val vendor: String?,
    val category: Category,
    val owned: Boolean,
    val ignored: Boolean,
    val fromPlace: String?,
    val toPlace: String?,
) {
    val deviceName: String get() = deviceTitle(deviceId, deviceLabel, broadcastName, descriptor, vendor)
}

data class SightingRow(
    val time: Long,
    val rssi: Int,
    val placeId: Long?,
    val placeName: String?,
    val accuracy: Float?,
)

data class Totals(
    val devices: Int = 0,
    val encounters: Int = 0,
    val sightings: Int = 0,
    val events: Int = 0,
    val places: Int = 0,
)

data class PlaceRow(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double,
    val userNamed: Boolean,
    val sightings: Int,
    val devices: Int,
    val lastSeen: Long?,
)

enum class DeviceFilter(val label: String, val where: String, val explanation: String) {
    REMEMBERED("Remembered", "temporary = 0", "Devices with an address stable enough to recognise again"),
    MINE("Mine", "owned = 1", "Devices you marked as yours"),
    WATCHING("Watching", "starred = 1", "Devices that notify you when they come back"),
    IGNORED("Ignored", "ignored = 1", "Hidden from Nearby, and never alert"),
    TRACKERS("Trackers", "category = 'TRACKER'", "Devices with evidence of being a tracker"),
    FIND_MY("Find My", "category = 'FIND_MY'", "Finding-network broadcasts of unknown model. Not proof of a tracker."),
    NETWORKS("Networks", "category = 'NETWORK'", "Wi-Fi access points"),
    PRIVATE("Private addresses", "temporary = 1", "Each entry is one address, not one device. One device can appear many times."),
}

enum class DeviceSort(val label: String, val order: String) {
    RECENT("Recent", "lastSeen DESC"),
    NAME("Name", "COALESCE(label, broadcastName, descriptor, vendor, 'zzzz') COLLATE NOCASE ASC, lastSeen DESC"),
    FIRST_SEEN("First seen", "firstSeen DESC"),
    SIGNAL("Signal", "lastRssi DESC"),
    MOST_HEARD("Most heard", "observations DESC"),
}

/** Builds the device list query, so filtering and limits happen in SQLite rather than in memory. */
object DeviceQuery {
    private fun where(search: String, filter: DeviceFilter): Pair<String, Array<Any>> {
        val needle = search.trim()
        if (needle.isEmpty()) return filter.where to emptyArray()
        val like = "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val columns = listOf("label", "broadcastName", "descriptor", "vendor", "`key`")
        val clause = columns.joinToString(" OR ") { "$it LIKE ? ESCAPE '\\'" }
        return "(${filter.where}) AND ($clause)" to Array(columns.size) { like }
    }

    fun list(search: String, filter: DeviceFilter, sort: DeviceSort, limit: Int): SupportSQLiteQuery {
        val (clause, args) = where(search, filter)
        return SimpleSQLiteQuery("SELECT * FROM device WHERE $clause ORDER BY ${sort.order} LIMIT $limit", args)
    }

    fun count(search: String, filter: DeviceFilter): SupportSQLiteQuery {
        val (clause, args) = where(search, filter)
        return SimpleSQLiteQuery("SELECT COUNT(*) FROM device WHERE $clause", args)
    }
}

private const val SAVED_IDS =
    "SELECT id FROM device WHERE label IS NOT NULL OR starred = 1 OR owned = 1 OR ignored = 1"

private const val EVENT_ROW = """
    SELECT e.id, e.deviceId, e.type, e.time, e.eligible, e.delivery, e.dismissed, e.summary, e.evidence,
           d.label AS deviceLabel, d.broadcastName, d.descriptor, d.vendor, d.category, d.owned, d.ignored,
           f.name AS fromPlace, t.name AS toPlace
    FROM event e
    JOIN device d ON d.id = e.deviceId
    LEFT JOIN place f ON f.id = e.fromPlaceId
    LEFT JOIN place t ON t.id = e.toPlaceId
"""

@Dao
interface DejaDao {
    @Query("SELECT * FROM device WHERE `key` = :key")
    suspend fun deviceByKey(key: String): Device?

    @Insert
    suspend fun insertDevice(device: Device): Long

    @Update
    suspend fun updateDevice(device: Device)

    @Query("UPDATE device SET label = :label, starred = :starred, ignored = :ignored, owned = :owned WHERE id = :id")
    suspend fun updateFlags(id: Long, label: String?, starred: Boolean, ignored: Boolean, owned: Boolean)

    @Query("UPDATE device SET owned = 1, ignored = 0 WHERE id = :id")
    suspend fun markOwned(id: Long)

    @Query("UPDATE device SET ignored = 1 WHERE id = :id")
    suspend fun markIgnored(id: Long)

    @Query("UPDATE device SET category = :category, categoryManual = 1 WHERE id = :id")
    suspend fun setCategory(id: Long, category: Category)

    @Query("UPDATE device SET category = autoCategory, categoryManual = 0 WHERE id = :id")
    suspend fun resetCategory(id: Long)

    @Query("SELECT * FROM device WHERE id = :id")
    suspend fun deviceById(id: Long): Device?

    @Query("SELECT * FROM device WHERE id = :id")
    fun watchDevice(id: Long): Flow<Device?>

    @RawQuery(observedEntities = [Device::class])
    fun watchDeviceList(query: SupportSQLiteQuery): Flow<List<Device>>

    @RawQuery(observedEntities = [Device::class])
    fun watchDeviceCount(query: SupportSQLiteQuery): Flow<Int>

    @Query("DELETE FROM device WHERE id = :id")
    suspend fun deleteDevice(id: Long)

    @Query("UPDATE device SET lastPlaceId = NULL, observations = 0, lastAlertAt = 0 WHERE id = :id")
    suspend fun resetDeviceHistory(id: Long)

    @Query("SELECT MAX(time) FROM sighting WHERE deviceId = :deviceId AND placeId IS :placeId")
    suspend fun lastSightingAt(deviceId: Long, placeId: Long?): Long?

    @Insert
    suspend fun insertSighting(sighting: Sighting)

    @Query(
        """SELECT COUNT(DISTINCT placeId) FROM sighting
           WHERE deviceId = :deviceId AND placeId IS NOT NULL AND time >= :since""",
    )
    suspend fun distinctPlacesSince(deviceId: Long, since: Long): Int

    @Query(
        """SELECT s.time, s.placeId, p.name AS placeName, p.lat, p.lng, s.accuracy, s.rssi
           FROM sighting s JOIN place p ON p.id = s.placeId
           WHERE s.deviceId = :deviceId AND s.time >= :since ORDER BY s.time""",
    )
    suspend fun trail(deviceId: Long, since: Long): List<TrailPoint>

    @Query(
        """SELECT s.time, s.rssi, s.placeId, p.name AS placeName, s.accuracy FROM sighting s
           LEFT JOIN place p ON p.id = s.placeId
           WHERE s.deviceId = :deviceId ORDER BY s.time DESC LIMIT :limit""",
    )
    fun watchSightings(deviceId: Long, limit: Int): Flow<List<SightingRow>>

    @Query(
        """SELECT s.time, s.rssi, s.placeId, p.name AS placeName, s.accuracy FROM sighting s
           LEFT JOIN place p ON p.id = s.placeId
           WHERE s.deviceId = :deviceId AND s.time BETWEEN :from AND :to ORDER BY s.time""",
    )
    suspend fun sightingsBetween(deviceId: Long, from: Long, to: Long): List<SightingRow>

    /** Every sighting of a device, unpaged, so totals and charts are never cut off by a page size. */
    @Query("SELECT time, placeId FROM sighting WHERE deviceId = :deviceId ORDER BY time")
    fun watchHistoryPoints(deviceId: Long): Flow<List<HistoryPoint>>

    @Query("DELETE FROM sighting WHERE deviceId = :deviceId")
    suspend fun deleteSightings(deviceId: Long)

    @Query("SELECT * FROM place")
    suspend fun places(): List<Place>

    @Query("SELECT * FROM place")
    fun watchPlaces(): Flow<List<Place>>

    @Query(
        """SELECT p.id, p.name, p.lat, p.lng, p.userNamed,
                  COUNT(s.id) AS sightings,
                  COUNT(DISTINCT s.deviceId) AS devices,
                  MAX(s.time) AS lastSeen
           FROM place p LEFT JOIN sighting s ON s.placeId = p.id
           GROUP BY p.id ORDER BY lastSeen IS NULL, lastSeen DESC""",
    )
    fun watchPlaceRows(): Flow<List<PlaceRow>>

    @Insert
    suspend fun insertPlace(place: Place): Long

    @Update
    suspend fun updatePlace(place: Place)

    @Query("UPDATE place SET name = :name, userNamed = 1 WHERE id = :id")
    suspend fun renamePlace(id: Long, name: String)

    @Query("UPDATE place SET name = :name WHERE id = :id")
    suspend fun autoNamePlace(id: Long, name: String)

    @Query("DELETE FROM place WHERE id = :id")
    suspend fun deletePlace(id: Long)

    @Query("UPDATE sighting SET placeId = :to WHERE placeId = :from")
    suspend fun moveSightings(from: Long, to: Long)

    @Query("UPDATE device SET lastPlaceId = :to WHERE lastPlaceId = :from")
    suspend fun moveDevicePlaces(from: Long, to: Long)

    @Query("UPDATE event SET fromPlaceId = :to WHERE fromPlaceId = :from")
    suspend fun moveEventOrigins(from: Long, to: Long)

    @Query("UPDATE event SET toPlaceId = :to WHERE toPlaceId = :from")
    suspend fun moveEventDestinations(from: Long, to: Long)

    @Insert
    suspend fun insertEvent(event: Event): Long

    @Query("SELECT * FROM event WHERE evidence != ''")
    suspend fun eventsWithEvidence(): List<Event>

    @Query("UPDATE event SET evidence = :evidence WHERE id = :id")
    suspend fun updateEvidence(id: Long, evidence: String)

    @Query("UPDATE event SET delivery = :delivery WHERE id = :id")
    suspend fun setDelivery(id: Long, delivery: Delivery)

    @Query("UPDATE event SET dismissed = 1 WHERE id = :id")
    suspend fun dismissEvent(id: Long)

    @Query("UPDATE event SET dismissed = 1 WHERE deviceId = :deviceId")
    suspend fun dismissEventsFor(deviceId: Long)

    @Query("DELETE FROM event WHERE deviceId = :deviceId")
    suspend fun deleteEvents(deviceId: Long)

    @Query("$EVENT_ROW WHERE e.time BETWEEN :from AND :to ORDER BY e.time DESC LIMIT :limit")
    fun watchEvents(from: Long, to: Long, limit: Int): Flow<List<EventRow>>

    @Query("SELECT COUNT(*) FROM event WHERE time BETWEEN :from AND :to")
    fun watchEventCount(from: Long, to: Long): Flow<Int>

    @Query("$EVENT_ROW WHERE e.id = :id")
    fun watchEvent(id: Long): Flow<EventRow?>

    @Query("$EVENT_ROW WHERE e.id = :id")
    suspend fun eventById(id: Long): EventRow?

    @Query("$EVENT_ROW WHERE e.type = 'FOLLOWING' AND e.dismissed = 0 AND d.owned = 0 AND d.ignored = 0 AND e.time >= :since ORDER BY e.time DESC LIMIT 1")
    fun watchActiveWarning(since: Long): Flow<EventRow?>

    @Query(
        """SELECT (SELECT COUNT(*) FROM device WHERE temporary = 0) AS devices,
                  (SELECT COUNT(*) FROM device WHERE temporary = 1) AS encounters,
                  (SELECT COUNT(*) FROM sighting) AS sightings,
                  (SELECT COUNT(*) FROM event) AS events,
                  (SELECT COUNT(*) FROM place) AS places""",
    )
    fun watchTotals(): Flow<Totals>

    @Query("DELETE FROM sighting WHERE time < :before AND (:keepSaved = 0 OR deviceId NOT IN ($SAVED_IDS))")
    suspend fun pruneSightings(before: Long, keepSaved: Boolean)

    @Query("DELETE FROM event WHERE time < :before AND (:keepSaved = 0 OR deviceId NOT IN ($SAVED_IDS))")
    suspend fun pruneEvents(before: Long, keepSaved: Boolean)

    @Query("DELETE FROM device WHERE temporary = 0 AND lastSeen < :before AND id NOT IN ($SAVED_IDS)")
    suspend fun pruneDevices(before: Long)

    @Query("DELETE FROM device WHERE temporary = 1 AND lastSeen < :before AND id NOT IN ($SAVED_IDS)")
    suspend fun pruneEncounters(before: Long)

    /** A device's last place is location history too, so it goes when its sightings have gone. */
    @Query(
        """UPDATE device SET lastPlaceId = NULL WHERE lastPlaceId IS NOT NULL
           AND id NOT IN (SELECT deviceId FROM sighting WHERE placeId IS NOT NULL)""",
    )
    suspend fun clearOrphanedLastPlaces()

    @Query(
        """DELETE FROM place WHERE userNamed = 0
           AND id NOT IN (SELECT placeId FROM sighting WHERE placeId IS NOT NULL)
           AND id NOT IN (SELECT fromPlaceId FROM event WHERE fromPlaceId IS NOT NULL)
           AND id NOT IN (SELECT toPlaceId FROM event WHERE toPlaceId IS NOT NULL)
           AND id NOT IN (SELECT lastPlaceId FROM device WHERE lastPlaceId IS NOT NULL)""",
    )
    suspend fun deleteUnusedPlaces(): Int

    @Query("DELETE FROM device")
    suspend fun deleteAllDevices()

    @Query("DELETE FROM place")
    suspend fun deleteAllPlaces()
}

/**
 * Version 1 kept no evidence, so what it called a tracker has to be reassessed from what it left
 * behind. A row named only by the Apple fallback can only have got there through a Find My frame,
 * which proves nothing about the model, so those rows are honestly downgraded. Every label, flag,
 * sighting and event is kept.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf(
            "owned INTEGER NOT NULL DEFAULT 0",
            "temporary INTEGER NOT NULL DEFAULT 0",
            "categoryManual INTEGER NOT NULL DEFAULT 0",
            "evidence TEXT NOT NULL DEFAULT ''",
            "fingerprint TEXT NOT NULL DEFAULT ''",
            "autoCategory TEXT NOT NULL DEFAULT 'UNKNOWN'",
            "descriptor TEXT",
            "vendor TEXT",
            "typeConfidence TEXT NOT NULL DEFAULT 'NONE'",
            "identity TEXT NOT NULL DEFAULT 'STABLE'",
            "addressType TEXT NOT NULL DEFAULT ''",
            "minRssi INTEGER NOT NULL DEFAULT 0",
            "maxRssi INTEGER NOT NULL DEFAULT 0",
            "observations INTEGER NOT NULL DEFAULT 0",
        ).forEach { db.execSQL("ALTER TABLE device ADD COLUMN $it") }

        db.execSQL(
            """UPDATE device SET autoCategory = category, minRssi = lastRssi, maxRssi = lastRssi,
               observations = (SELECT COUNT(*) FROM sighting WHERE sighting.deviceId = device.id),
               evidence = 'Saved by an earlier version, which recorded no evidence',
               typeConfidence = CASE WHEN category = 'UNKNOWN' THEN 'NONE' ELSE 'LOW' END""",
        )
        db.execSQL("UPDATE device SET typeConfidence = 'HIGH' WHERE category = 'NETWORK'")

        // Earlier versions stored a fallback in the name column. A fallback is not a real name.
        db.execSQL(
            """UPDATE device SET vendor = broadcastName, broadcastName = NULL
               WHERE broadcastName IN ('Apple device', 'Microsoft device', 'Google device',
                   'Samsung device', 'Garmin device', 'Bose device', 'Sony device',
                   'Amazon device', 'Xiaomi device')""",
        )
        db.execSQL("UPDATE device SET descriptor = broadcastName, broadcastName = NULL WHERE broadcastName = 'Hidden network'")
        db.execSQL("UPDATE device SET descriptor = 'Hidden Wi-Fi network' WHERE descriptor = 'Hidden network'")

        db.execSQL(
            """UPDATE device SET category = 'FIND_MY', autoCategory = 'FIND_MY', typeConfidence = 'LOW',
                   identity = 'MAY_CHANGE', temporary = 1,
                   descriptor = 'Apple Find My broadcast · Model unknown',
                   evidence = 'An earlier version labelled this a tracker because of a Find My frame. That frame does not identify a model.'
               WHERE category = 'TRACKER' AND radio = 'BLE' AND vendor = 'Apple device'""",
        )

        db.execSQL("ALTER TABLE place ADD COLUMN samples INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE place ADD COLUMN userNamed INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE place SET userNamed = 1 WHERE name NOT GLOB 'Place [0-9]*'")

        db.execSQL("ALTER TABLE sighting ADD COLUMN accuracy REAL")

        db.execSQL("ALTER TABLE event RENAME COLUMN notified TO eligible")
        db.execSQL("ALTER TABLE event ADD COLUMN delivery TEXT NOT NULL DEFAULT 'NOT_ELIGIBLE'")
        db.execSQL("ALTER TABLE event ADD COLUMN dismissed INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE event ADD COLUMN summary TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE event ADD COLUMN evidence TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE event SET delivery = 'UNKNOWN' WHERE eligible = 1")
        db.execSQL(
            """UPDATE event SET type = 'NEW_FIND_MY' WHERE type = 'NEW_TRACKER'
               AND deviceId IN (SELECT id FROM device WHERE category = 'FIND_MY')""",
        )
    }
}

@Database(
    entities = [Device::class, Place::class, Sighting::class, Event::class],
    version = 2,
    exportSchema = true,
)
abstract class DejaDatabase : RoomDatabase() {
    abstract fun dao(): DejaDao

    companion object {
        const val NAME = "deja.db"

        fun create(context: Context): DejaDatabase =
            Room.databaseBuilder(context, DejaDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
