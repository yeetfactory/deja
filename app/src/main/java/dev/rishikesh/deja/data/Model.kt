package dev.rishikesh.deja.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class Radio { BLE, WIFI }

enum class Category { TRACKER, AUDIO, WEARABLE, VEHICLE, TV, NETWORK, BEACON, FIND_MY, COMPUTER, TABLET, PHONE, INPUT, UNKNOWN }

/** NEW_FIND_MY only exists for rows migrated from builds that called every Find My frame a tracker. */
enum class EventType { NEW_TRACKER, NEW_FIND_MY, RETURNED, FOLLOWING }

/** How sure we are about what kind of thing this is. Says nothing about whether it is the same thing. */
enum class TypeConfidence { NONE, LOW, MEDIUM, HIGH }

/**
 * How far this record can be trusted to mean one physical device. Ordered from most to least
 * stable, so a record keeps the least stable level it has ever shown.
 */
enum class IdentityConfidence {
    /** A public or static address. The same address means the same device. */
    STABLE,

    /** The address holds for a while, then changes, such as a Find My broadcast. */
    MAY_CHANGE,

    /** A private address. This record covers this address only, not the device behind it. */
    ADDRESS_ONLY,
}

/** What happened to a possible notification. Being eligible and being shown are separate facts. */
enum class Delivery {
    NOT_ELIGIBLE,
    PENDING,
    POSTED,
    DISABLED_IN_SETTINGS,
    BLOCKED_PERMISSION,
    BLOCKED_CHANNEL,

    /** From before delivery was tracked: it was eligible, but nobody recorded whether it showed. */
    UNKNOWN,
}

@Entity(tableName = "device", indices = [Index(value = ["key"], unique = true)])
data class Device(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val radio: Radio,
    /** Only ever a name the device really advertised. See [vendor] and [descriptor] for fallbacks. */
    val broadcastName: String?,
    /** The category in effect: the user's choice when [categoryManual], otherwise [autoCategory]. */
    val category: Category,
    val label: String? = null,
    val starred: Boolean = false,
    val ignored: Boolean = false,
    val firstSeen: Long,
    val lastSeen: Long,
    val lastRssi: Int,
    val lastPlaceId: Long? = null,
    val lastAlertAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val owned: Boolean = false,
    /** A private-address encounter: one address, kept briefly, never linked to another address. */
    @ColumnInfo(defaultValue = "0") val temporary: Boolean = false,
    @ColumnInfo(defaultValue = "0") val categoryManual: Boolean = false,
    /** One line per fact behind the classification. */
    @ColumnInfo(defaultValue = "''") val evidence: String = "",
    /** A diagnostic summary of the advertisement shape. Never used to match records. */
    @ColumnInfo(defaultValue = "''") val fingerprint: String = "",
    /** What the classifier last concluded, kept so a manual choice can be undone. */
    @ColumnInfo(defaultValue = "'UNKNOWN'") val autoCategory: Category = category,
    /** What the evidence supports, such as "AirPods Pro" or "Apple Find My broadcast · Model unknown". */
    val descriptor: String? = null,
    val vendor: String? = null,
    @ColumnInfo(defaultValue = "'NONE'") val typeConfidence: TypeConfidence = TypeConfidence.NONE,
    @ColumnInfo(defaultValue = "'STABLE'") val identity: IdentityConfidence = IdentityConfidence.STABLE,
    @ColumnInfo(defaultValue = "''") val addressType: String = "",
    @ColumnInfo(defaultValue = "0") val minRssi: Int = lastRssi,
    @ColumnInfo(defaultValue = "0") val maxRssi: Int = lastRssi,
    /** Every reading ever heard, including those too close together to store as a sighting. */
    @ColumnInfo(defaultValue = "0") val observations: Long = 0,
) {
    val code: String get() = "%04d".format(id)
    val title: String get() = deviceTitle(id, label, broadcastName, descriptor, vendor)

    /** True when the user has said something about this device that must outlive retention. */
    val saved: Boolean get() = label != null || starred || owned || ignored
}

fun deviceTitle(id: Long, label: String?, broadcastName: String?, descriptor: String?, vendor: String?): String =
    label ?: broadcastName ?: descriptor ?: vendor ?: "Device %04d".format(id)

@Entity(tableName = "place")
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lng: Double,
    /** How many good fixes the centre is averaged from. */
    @ColumnInfo(defaultValue = "1") val samples: Int = 1,
    /** Named by a person, so it survives cleanup even when nothing refers to it. */
    @ColumnInfo(defaultValue = "0") val userNamed: Boolean = false,
)

@Entity(
    tableName = "sighting",
    foreignKeys = [
        ForeignKey(
            entity = Device::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("deviceId"), Index("time")],
)
data class Sighting(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val placeId: Long?,
    val time: Long,
    val rssi: Int,
    /** Location uncertainty in metres at this moment, or null when it was not recorded. */
    val accuracy: Float? = null,
)

@Entity(
    tableName = "event",
    foreignKeys = [
        ForeignKey(
            entity = Device::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("deviceId"), Index("time")],
)
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val type: EventType,
    val time: Long,
    val fromPlaceId: Long?,
    val toPlaceId: Long?,
    /** Whether the rules said this deserved a notification. See [delivery] for what happened. */
    val eligible: Boolean,
    @ColumnInfo(defaultValue = "'NOT_ELIGIBLE'") val delivery: Delivery = Delivery.NOT_ELIGIBLE,
    @ColumnInfo(defaultValue = "0") val dismissed: Boolean = false,
    /** The evidence in one readable sentence. */
    @ColumnInfo(defaultValue = "''") val summary: String = "",
    /** The timeline behind the alert, encoded by EvidenceCodec. It holds places, so it is history. */
    @ColumnInfo(defaultValue = "''") val evidence: String = "",
)

/** One device heard during a scan burst, before it is matched against memory. */
data class Observation(
    val key: String,
    val radio: Radio,
    val name: String?,
    val category: Category,
    val rssi: Int,
    /** A company fallback like "Apple device", used only until something better is known. */
    val vendor: String? = null,
    val temporary: Boolean = false,
    val evidence: String = "",
    val fingerprint: String = "",
    val observedAt: Long? = null,
    val descriptor: String? = null,
    val typeConfidence: TypeConfidence = TypeConfidence.NONE,
    val identity: IdentityConfidence = IdentityConfidence.STABLE,
    val addressType: String = "",
    val minRssi: Int = rssi,
    val maxRssi: Int = rssi,
    val readings: Int = 1,
)
