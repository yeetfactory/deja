package top.yeetfactory.deja.core

import top.yeetfactory.deja.data.Category
import top.yeetfactory.deja.data.Device
import top.yeetfactory.deja.data.EventType
import top.yeetfactory.deja.data.IdentityConfidence

data class Verdict(
    val type: EventType,
    val notify: Boolean,
)

/** The rules for when a sighting is worth recording as an event, and worth a ping. */
object MatchEngine {
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR

    /** A device must vanish for this long before it counts as coming back. */
    const val RETURN_GAP = 30 * MINUTE

    /** After this long, a return counts even at the same place. */
    const val LONG_GAP = 6 * HOUR

    const val ALERT_COOLDOWN = 6 * HOUR

    fun cooledDown(
        device: Device,
        now: Long,
    ) = now - device.lastAlertAt >= ALERT_COOLDOWN

    /**
     * Following is judged separately by [FollowEngine], which needs the device's whole recent trail.
     *
     * @param previous the device as it was before this sighting, or null if never seen.
     * @param current the device with this sighting applied.
     * @param moved whether the last place and this one are really apart, beyond location error.
     */
    fun judge(
        previous: Device?,
        current: Device,
        now: Long,
        moved: Boolean,
    ): Verdict? {
        if (current.ignored) return null

        val newTracker = current.category == Category.TRACKER && previous?.category != Category.TRACKER
        if (newTracker && !current.owned) return Verdict(EventType.NEW_TRACKER, notify = true)
        if (previous == null) return null

        // A private address coming back says nothing, and your own gear only matters if watched.
        if (current.identity == IdentityConfidence.ADDRESS_ONLY) return null
        if (current.owned && !current.starred) return null

        val gap = now - previous.lastSeen
        val returned = gap >= RETURN_GAP && (moved || gap >= LONG_GAP)
        if (!returned) return null

        return Verdict(EventType.RETURNED, notify = current.starred && cooledDown(previous, now))
    }
}
