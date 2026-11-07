package dev.rishikesh.deja.core

import dev.rishikesh.deja.data.Category
import dev.rishikesh.deja.data.IdentityConfidence
import dev.rishikesh.deja.data.TypeConfidence

enum class AddressKind { PUBLIC, RANDOM, UNKNOWN }

/** One advertisement, as received. */
data class Advert(
    val address: String,
    val kind: AddressKind,
    val name: String? = null,
    val manufacturer: Map<Int, ByteArray> = emptyMap(),
    val services: Set<Int> = emptySet(),
    val serviceData: Map<Int, ByteArray> = emptyMap(),
)

/** One type-length-value frame inside Apple manufacturer data. */
class AppleFrame(val type: Int, val payload: ByteArray)

data class Classification(
    val category: Category,
    /** What the evidence supports, in words. Null when there is nothing better than the category. */
    val descriptor: String?,
    val vendor: String?,
    val typeConfidence: TypeConfidence,
    val identity: IdentityConfidence,
    /** True when this record should be kept as a short-lived encounter. */
    val temporary: Boolean,
    val addressType: String,
    val evidence: List<String>,
    val fingerprint: String,
)

/**
 * Decides what an advertisement is, and separately how far its address can be trusted.
 *
 * Everything here is what a transmitter claims about itself, and anything can claim anything, so
 * a single signal never reaches [TypeConfidence.HIGH] unless it is a purpose-specific service ID.
 * Where the evidence stops, the descriptor says "Model unknown" rather than guessing.
 *
 * Private addresses are classified and remembered as encounters, but this class deliberately has
 * no way to say two addresses are the same device. The fingerprint is for diagnostics only.
 */
object BleClassifier {
    private const val APPLE = 0x004C

    private const val FRAME_PROXIMITY_PAIRING = 0x07
    private const val FRAME_AIRPLAY_TARGET = 0x09
    private const val FRAME_MAGIC_SWITCH = 0x0B
    private const val FRAME_HANDOFF = 0x0C
    private const val FRAME_NEARBY_INFO = 0x10
    private const val FRAME_FIND_MY = 0x12
    private const val FIND_MY_SEPARATED_LENGTH = 0x19

    private const val TILE_A = 0xFEED
    private const val TILE_B = 0xFEEC
    private const val CHIPOLO = 0xFE33
    private const val PEBBLEBEE = 0xFA25
    private const val SAMSUNG_TAG = 0xFD5A
    private const val EDDYSTONE = 0xFEAA
    private const val HEART_RATE = 0x180D
    private const val AUDIO_STREAM = 0x184E
    private const val HID = 0x1812

    const val MODEL_UNKNOWN = "Model unknown"
    const val FIND_MY_UNKNOWN = "Apple Find My broadcast · $MODEL_UNKNOWN"

    private val VENDORS = mapOf(
        APPLE to "Apple",
        0x0006 to "Microsoft",
        0x00E0 to "Google",
        0x0075 to "Samsung",
        0x0087 to "Garmin",
        0x009E to "Bose",
        0x012D to "Sony",
        0x0171 to "Amazon",
        0x038F to "Xiaomi",
    )

    /** The two model bytes of a Proximity Pairing frame, in the order they arrive. */
    private val AUDIO_MODELS = mapOf(
        0x0220 to "AirPods",
        0x0F20 to "AirPods (2nd generation)",
        0x1320 to "AirPods (3rd generation)",
        0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro (2nd generation)",
        0x0A20 to "AirPods Max",
        0x0320 to "Powerbeats",
        0x0B20 to "Powerbeats Pro",
        0x0520 to "BeatsX",
        0x0620 to "Beats Solo3",
        0x0920 to "Beats Studio3",
        0x0C20 to "Beats Solo Pro",
        0x1020 to "Beats Flex",
        0x1120 to "Beats Studio Buds",
        0x1220 to "Beats Fit Pro",
        0x1620 to "Beats Studio Buds+",
        0x1720 to "Beats Studio Pro",
    )

    private class NameRule(val category: Category, pattern: String) {
        val regex = Regex(pattern)
    }

    /** Whole words only, so "Oxford" is not a Ford and "Husband's phone" is not a fitness band. */
    private val NAME_RULES = listOf(
        NameRule(Category.TRACKER, """\b(tile|smart ?tag\d?|airtag|chipolo|pebblebee)\b"""),
        NameRule(Category.AUDIO, """\b(airpods|buds\d?|bose|jabra|beats|headphones?|headset|earbuds|speaker|soundbar|jbl|wh-\w+|wf-\w+)\b"""),
        NameRule(Category.WEARABLE, """\b(watch|band|fitbit|garmin|whoop|oura|polar)\b"""),
        NameRule(Category.COMPUTER, """\b(macbook|imac|mac ?mini|mac ?studio|mac ?pro|laptop|thinkpad|desktop)\b"""),
        NameRule(Category.TABLET, """\b(ipad|galaxy tab|tablet)\b"""),
        NameRule(Category.PHONE, """\b(iphone|phone|galaxy s\d+|pixel \d+\w*)\b"""),
        NameRule(Category.INPUT, """\b(keyboard|mouse|trackpad|mx master)\b"""),
        NameRule(Category.VEHICLE, """\b(tesla|bmw|audi|vw|mercedes|toyota|ford|my car|carplay)\b"""),
        NameRule(Category.TV, """\b(tv|bravia|roku|chromecast|fire stick)\b"""),
    )

    /** A readable fallback from the Bluetooth company ID, such as "Apple device". */
    fun vendor(manufacturer: Map<Int, ByteArray>): String? =
        manufacturer.keys.firstNotNullOfOrNull { VENDORS[it] }?.let { "$it device" }

    /** Walks the frames in order, and keeps whatever survives if the data is cut short. */
    fun appleTlvs(manufacturer: Map<Int, ByteArray>): List<AppleFrame> {
        val data = manufacturer[APPLE] ?: return emptyList()
        val frames = mutableListOf<AppleFrame>()
        var i = 0
        while (i < data.size) {
            val type = data[i].toInt() and 0xFF
            val length = data.getOrNull(i + 1)?.toInt()?.and(0xFF)
            if (length == null) {
                frames += AppleFrame(type, ByteArray(0))
                break
            }
            val end = minOf(i + 2 + length, data.size)
            frames += AppleFrame(type, data.copyOfRange(minOf(i + 2, data.size), end))
            i += 2 + length
        }
        return frames
    }

    /** The frame types inside an Apple advertisement, which say what it is announcing. */
    fun appleFrames(manufacturer: Map<Int, ByteArray>): List<Int> = appleTlvs(manufacturer).map { it.type }

    private fun findMy(frames: List<AppleFrame>) = frames.firstOrNull { it.type == FRAME_FIND_MY }

    /** The long form of the frame, used when the accessory has been away from its owner. */
    private fun AppleFrame.separated() = payload.size >= FIND_MY_SEPARATED_LENGTH

    fun isFindMySeparated(manufacturer: Map<Int, ByteArray>): Boolean =
        findMy(appleTlvs(manufacturer))?.separated() == true

    private fun isFindMyNearOwner(manufacturer: Map<Int, ByteArray>): Boolean =
        findMy(appleTlvs(manufacturer))?.separated() == false

    /** True when this address will change soon, so it says nothing beyond this encounter. */
    fun isRotating(
        address: String,
        kind: AddressKind,
        manufacturer: Map<Int, ByteArray>,
    ): Boolean {
        if (isFindMyNearOwner(manufacturer)) return true
        if (isFindMySeparated(manufacturer)) return false
        val topBits = address.take(2).toIntOrNull(16)?.shr(6) ?: return true
        return when (kind) {
            AddressKind.PUBLIC -> false
            AddressKind.RANDOM -> topBits != 0b11
            AddressKind.UNKNOWN -> topBits == 0b01
        }
    }

    private fun addressType(address: String, kind: AddressKind): String {
        val topBits = address.take(2).toIntOrNull(16)?.shr(6)
        return when {
            kind == AddressKind.PUBLIC -> "Public"
            topBits == null -> "Unreadable"
            kind == AddressKind.RANDOM && topBits == 0b11 -> "Random static"
            kind == AddressKind.RANDOM && topBits == 0b01 -> "Private, resolvable"
            kind == AddressKind.RANDOM && topBits == 0b00 -> "Private, non-resolvable"
            kind == AddressKind.RANDOM -> "Random, reserved bits"
            else -> "Not reported by Android"
        }
    }

    private data class Finding(
        val category: Category,
        val descriptor: String?,
        val confidence: TypeConfidence,
        val because: String,
    )

    private fun fromTrackerService(advert: Advert): Finding? {
        fun tracker(brand: String, id: Int) = Finding(
            Category.TRACKER,
            "$brand tracker · $MODEL_UNKNOWN",
            TypeConfidence.HIGH,
            "Advertises the $brand service 0x%04X".format(id),
        )
        val ids = advert.services + advert.serviceData.keys
        return when {
            TILE_A in ids -> tracker("Tile", TILE_A)
            TILE_B in ids -> tracker("Tile", TILE_B)
            CHIPOLO in ids -> tracker("Chipolo", CHIPOLO)
            PEBBLEBEE in ids -> tracker("Pebblebee", PEBBLEBEE)
            SAMSUNG_TAG in ids -> tracker("Samsung", SAMSUNG_TAG)
            else -> null
        }
    }

    private fun fromProximityPairing(frames: List<AppleFrame>): Finding? {
        val payload = frames.firstOrNull { it.type == FRAME_PROXIMITY_PAIRING }?.payload ?: return null
        if (payload.size < 3) {
            return Finding(
                Category.AUDIO,
                "AirPods or Beats · $MODEL_UNKNOWN",
                TypeConfidence.LOW,
                "Apple Proximity Pairing frame, too short to read a model",
            )
        }
        val code = (payload[1].toInt() and 0xFF shl 8) or (payload[2].toInt() and 0xFF)
        val model = AUDIO_MODELS[code]
        return if (model != null) {
            Finding(Category.AUDIO, model, TypeConfidence.MEDIUM, "Proximity Pairing frame claims model 0x%04X".format(code))
        } else {
            Finding(
                Category.AUDIO,
                "AirPods or Beats · $MODEL_UNKNOWN",
                TypeConfidence.MEDIUM,
                "Proximity Pairing frame with unrecognised model 0x%04X".format(code),
            )
        }
    }

    private fun fromName(name: String?): Finding? {
        val text = name?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val rule = NAME_RULES.firstOrNull { it.regex.containsMatchIn(text) } ?: return null
        return Finding(rule.category, null, TypeConfidence.MEDIUM, "Advertised name \"$name\" suggests ${rule.category.name.lowercase()}")
    }

    /**
     * Any Apple product can send this frame, and that alone identifies no model. The status bits
     * are read the way AirGuard reads them, but published notes on the frame have only ever seen
     * them as zero, and on real devices they turn up set on ordinary Apple gear. So they may add a
     * word to the description, and they can never make something a tracker on their own.
     */
    private fun fromFindMy(frames: List<AppleFrame>): Finding? {
        val frame = findMy(frames) ?: return null
        val because = if (frame.separated()) "Find My frame, long form: away from its owner" else "Find My frame, short form"
        val hint = if (frame.separated()) (frame.payload[0].toInt() and 0x30) shr 4 else 0
        return when (hint) {
            1 -> Finding(Category.FIND_MY, "Apple Find My broadcast · Possible AirTag", TypeConfidence.LOW, "$because; unconfirmed status bits hint at AirTag")
            2 -> Finding(Category.FIND_MY, "Apple Find My broadcast · Possible accessory", TypeConfidence.LOW, "$because; unconfirmed status bits hint at a third-party accessory")
            3 -> Finding(Category.AUDIO, "Possibly AirPods · $MODEL_UNKNOWN", TypeConfidence.LOW, "$because; unconfirmed status bits hint at AirPods")
            else -> Finding(Category.FIND_MY, FIND_MY_UNKNOWN, TypeConfidence.LOW, because)
        }
    }

    private fun fromFindHub(advert: Advert): Finding? {
        val data = advert.serviceData[EDDYSTONE]
        val frame = data?.firstOrNull()?.toInt()?.and(0xFF) ?: return null
        if (frame and 0xFE != 0x40) return null
        return Finding(
            Category.FIND_MY,
            "Google Find Hub broadcast · $MODEL_UNKNOWN",
            TypeConfidence.LOW,
            "Eddystone service data with frame 0x%02X".format(frame),
        )
    }

    private fun fromAppleHints(frames: List<AppleFrame>): Finding? {
        val types = frames.map { it.type }
        return when {
            FRAME_MAGIC_SWITCH in types -> Finding(
                Category.WEARABLE,
                "Possible Apple Watch · $MODEL_UNKNOWN",
                TypeConfidence.LOW,
                "Apple Magic Switch frame",
            )
            FRAME_AIRPLAY_TARGET in types -> Finding(
                Category.UNKNOWN,
                "AirPlay receiver · $MODEL_UNKNOWN",
                TypeConfidence.LOW,
                "Apple AirPlay Target frame",
            )
            FRAME_HANDOFF in types || FRAME_NEARBY_INFO in types -> Finding(
                Category.UNKNOWN,
                "Apple device · $MODEL_UNKNOWN",
                TypeConfidence.LOW,
                "Apple Handoff or Nearby Info frame, sent by many Apple products",
            )
            else -> null
        }
    }

    private fun fromServices(services: Set<Int>): Finding? = when {
        AUDIO_STREAM in services -> Finding(Category.AUDIO, null, TypeConfidence.MEDIUM, "Advertises Audio Stream Control 0x184E")
        HEART_RATE in services -> Finding(Category.WEARABLE, null, TypeConfidence.MEDIUM, "Advertises Heart Rate 0x180D")
        HID in services -> Finding(Category.INPUT, null, TypeConfidence.MEDIUM, "Advertises Human Interface Device 0x1812")
        EDDYSTONE in services -> Finding(Category.BEACON, null, TypeConfidence.LOW, "Advertises Eddystone 0xFEAA")
        else -> null
    }

    fun classify(advert: Advert): Classification {
        val frames = appleTlvs(advert.manufacturer)
        val vendor = vendor(advert.manufacturer)

        val named = fromName(advert.name)
        val pairing = fromProximityPairing(frames)
        val found = fromTrackerService(advert)
            ?: pairing
            ?: named
            ?: fromFindMy(frames)
            ?: fromFindHub(advert)
            ?: fromServices(advert.services)
            ?: fromAppleHints(frames)

        // Two independent claims agreeing is the only way a claim becomes strong.
        val agreed = pairing != null && named != null && named.category == pairing.category
        val confidence = when {
            agreed -> TypeConfidence.HIGH
            found != null -> found.confidence
            vendor != null -> TypeConfidence.LOW
            else -> TypeConfidence.NONE
        }

        val evidence = buildList {
            found?.let { add(it.because) }
            if (agreed) named?.let { add(it.because) }
            if (vendor != null) add("Manufacturer data from ${vendor.removeSuffix(" device")}")
            if (found == null && vendor == null) add("Nothing in the advertisement identifies the device type")
        }

        val rotating = isRotating(advert.address, advert.kind, advert.manufacturer)
        val findMySeparated = isFindMySeparated(advert.manufacturer)
        val identity = when {
            rotating -> IdentityConfidence.ADDRESS_ONLY
            findMySeparated -> IdentityConfidence.MAY_CHANGE
            else -> IdentityConfidence.STABLE
        }

        return Classification(
            category = found?.category ?: Category.UNKNOWN,
            descriptor = found?.descriptor,
            vendor = vendor,
            typeConfidence = confidence,
            identity = identity,
            temporary = rotating || findMySeparated,
            addressType = addressType(advert.address, advert.kind),
            evidence = evidence,
            fingerprint = fingerprint(advert, frames, vendor),
        )
    }

    /** Wi-Fi access points. A locally administered BSSID may be randomised, or a hotspot's. */
    fun classifyWifi(bssid: String, ssid: String?): Classification {
        val local = bssid.take(2).toIntOrNull(16)?.let { it and 0x02 != 0 } ?: true
        return Classification(
            category = Category.NETWORK,
            descriptor = if (ssid == null) "Hidden Wi-Fi network" else null,
            vendor = null,
            typeConfidence = TypeConfidence.HIGH,
            identity = if (local) IdentityConfidence.MAY_CHANGE else IdentityConfidence.STABLE,
            temporary = false,
            addressType = if (local) "Locally administered BSSID" else "Manufacturer-assigned BSSID",
            evidence = listOf(
                "Found by a Wi-Fi scan",
                if (ssid == null) "The network hides its name" else "Network name \"$ssid\"",
            ),
            fingerprint = "wifi;hidden=${ssid == null};local=$local",
        )
    }

    /** Describes the shape of the advertisement. Holds no address, and is never used for matching. */
    private fun fingerprint(advert: Advert, frames: List<AppleFrame>, vendor: String?): String = buildList {
        add("vendor=${vendor?.removeSuffix(" device") ?: advert.manufacturer.keys.sorted().joinToString("+") { "%04X".format(it) }}")
        if (frames.isNotEmpty()) add("frames=" + frames.joinToString(",") { "%02X".format(it.type) })
        val ids = (advert.services + advert.serviceData.keys).sorted()
        if (ids.isNotEmpty()) add("services=" + ids.joinToString(",") { "%04X".format(it) })
        add("named=${!advert.name.isNullOrBlank()}")
    }.joinToString(";")
}
