package top.yeetfactory.deja.core

import top.yeetfactory.deja.data.Category
import top.yeetfactory.deja.data.Device
import top.yeetfactory.deja.data.Observation
import top.yeetfactory.deja.data.Radio
import top.yeetfactory.deja.data.TypeConfidence

/** How new evidence is folded into what is already known, without losing what the user said. */
object Merge {
    fun observation(
        key: String,
        radio: Radio,
        name: String?,
        rssi: Int,
        observedAt: Long?,
        classification: Classification,
    ) = Observation(
        key = key,
        radio = radio,
        name = name,
        category = classification.category,
        rssi = rssi,
        vendor = classification.vendor,
        temporary = classification.temporary,
        evidence = classification.evidence.joinToString("\n"),
        fingerprint = classification.fingerprint,
        observedAt = observedAt,
        descriptor = classification.descriptor,
        typeConfidence = classification.typeConfidence,
        identity = classification.identity,
        addressType = classification.addressType,
    )

    /** Combines two packets from one address in one burst, keeping the better classification. */
    fun packets(
        a: Observation,
        b: Observation,
    ): Observation {
        val best = if (better(b.category, b.typeConfidence, a.category, a.typeConfidence)) b else a
        return best.copy(
            name = b.name ?: a.name,
            rssi = b.rssi,
            vendor = b.vendor ?: a.vendor,
            temporary = a.temporary || b.temporary,
            identity = maxOf(a.identity, b.identity),
            observedAt = b.observedAt ?: a.observedAt,
            minRssi = minOf(a.minRssi, b.minRssi),
            maxRssi = maxOf(a.maxRssi, b.maxRssi),
            readings = a.readings + b.readings,
        )
    }

    /** Stronger evidence wins. Equal evidence only fills a gap, so a device does not flicker. */
    private fun better(
        new: Category,
        newConfidence: TypeConfidence,
        old: Category,
        oldConfidence: TypeConfidence,
    ) = newConfidence > oldConfidence ||
        (newConfidence == oldConfidence && (old == Category.UNKNOWN || new == old))

    /**
     * Applies one observation to a stored device. The user's label, flags and manual category
     * always survive; the automatic conclusion underneath keeps improving so it can be restored.
     */
    fun device(
        previous: Device,
        obs: Observation,
        placeId: Long?,
        now: Long,
    ): Device {
        val adopt = better(obs.category, obs.typeConfidence, previous.autoCategory, previous.typeConfidence)
        val auto = if (adopt) obs.category else previous.autoCategory
        return previous.copy(
            broadcastName = obs.name ?: previous.broadcastName,
            vendor = obs.vendor ?: previous.vendor,
            autoCategory = auto,
            category = if (previous.categoryManual) previous.category else auto,
            descriptor = if (adopt) obs.descriptor ?: previous.descriptor else previous.descriptor,
            typeConfidence = if (adopt) obs.typeConfidence else previous.typeConfidence,
            evidence = if (adopt) obs.evidence else previous.evidence,
            fingerprint = if (adopt) obs.fingerprint else previous.fingerprint,
            identity = maxOf(previous.identity, obs.identity),
            temporary = previous.temporary || obs.temporary,
            addressType = obs.addressType.ifEmpty { previous.addressType },
            lastSeen = now,
            lastRssi = obs.rssi,
            lastPlaceId = placeId ?: previous.lastPlaceId,
            minRssi = if (previous.observations == 0L) obs.minRssi else minOf(previous.minRssi, obs.minRssi),
            maxRssi = if (previous.observations == 0L) obs.maxRssi else maxOf(previous.maxRssi, obs.maxRssi),
            observations = previous.observations + obs.readings,
        )
    }

    fun newDevice(
        obs: Observation,
        placeId: Long?,
        now: Long,
    ) = Device(
        key = obs.key,
        radio = obs.radio,
        broadcastName = obs.name,
        category = obs.category,
        autoCategory = obs.category,
        firstSeen = now,
        lastSeen = now,
        lastRssi = obs.rssi,
        lastPlaceId = placeId,
        temporary = obs.temporary,
        evidence = obs.evidence,
        fingerprint = obs.fingerprint,
        descriptor = obs.descriptor,
        vendor = obs.vendor,
        typeConfidence = obs.typeConfidence,
        identity = obs.identity,
        addressType = obs.addressType,
        minRssi = obs.minRssi,
        maxRssi = obs.maxRssi,
        observations = obs.readings.toLong(),
    )
}
