package dev.rishikesh.deja.core

import dev.rishikesh.deja.core.MatchEngine.ALERT_COOLDOWN
import dev.rishikesh.deja.core.MatchEngine.HOUR
import dev.rishikesh.deja.core.MatchEngine.MINUTE
import dev.rishikesh.deja.data.Category
import dev.rishikesh.deja.data.Device
import dev.rishikesh.deja.data.EventType
import dev.rishikesh.deja.data.IdentityConfidence
import dev.rishikesh.deja.data.Radio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchEngineTest {
    private val now = 100 * MatchEngine.DAY

    private fun device(
        lastSeenAgo: Long = MINUTE,
        category: Category = Category.AUDIO,
        starred: Boolean = false,
        ignored: Boolean = false,
        owned: Boolean = false,
        identity: IdentityConfidence = IdentityConfidence.STABLE,
        alertedAgo: Long = 10 * MatchEngine.DAY,
    ) = Device(
        id = 1,
        key = "ble:x",
        radio = Radio.BLE,
        broadcastName = null,
        category = category,
        starred = starred,
        ignored = ignored,
        owned = owned,
        identity = identity,
        firstSeen = 0,
        lastSeen = now - lastSeenAgo,
        lastRssi = -60,
        lastPlaceId = 1,
        lastAlertAt = now - alertedAgo,
    )

    private fun judge(previous: Device?, current: Device = previous ?: device(), moved: Boolean = false) =
        MatchEngine.judge(previous, current, now, moved)

    @Test fun `a new ordinary device creates no event`() {
        assertNull(judge(null, device()))
    }

    @Test fun `a new tracker is logged and may notify`() {
        assertEquals(EventType.NEW_TRACKER, judge(null, device(category = Category.TRACKER))?.type)
    }

    @Test fun `a new find my broadcast is not an event`() {
        assertNull(judge(null, device(category = Category.FIND_MY)))
    }

    @Test fun `a device reclassified as a tracker gets its event then`() {
        val verdict = judge(device(category = Category.UNKNOWN), device(category = Category.TRACKER))
        assertEquals(EventType.NEW_TRACKER, verdict?.type)
    }

    @Test fun `a device already known as a tracker does not repeat that event`() {
        val tracker = device(category = Category.TRACKER)
        assertNull(judge(tracker, tracker))
    }

    @Test fun `your own tracker is not news`() {
        assertNull(judge(null, device(category = Category.TRACKER, owned = true)))
    }

    @Test fun `a device that never left is not a return`() {
        assertNull(judge(device(5 * MINUTE), moved = true))
    }

    @Test fun `the neighbours TV coming back to the same place soon is ignored`() {
        assertNull(judge(device(2 * HOUR, Category.TV)))
    }

    @Test fun `a return at a new place is logged but only pings when starred`() {
        val plain = judge(device(2 * HOUR), moved = true)
        assertEquals(EventType.RETURNED, plain?.type)
        assertFalse(plain!!.notify)
        assertTrue(judge(device(2 * HOUR, starred = true), moved = true)!!.notify)
    }

    @Test fun `a long absence counts as a return at the same place`() {
        assertEquals(EventType.RETURNED, judge(device(7 * HOUR, starred = true))?.type)
    }

    @Test fun `return alerts respect the cooldown`() {
        val recent = device(2 * HOUR, starred = true, alertedAgo = ALERT_COOLDOWN - HOUR)
        assertFalse(judge(recent, moved = true)!!.notify)
    }

    @Test fun `ignored devices never produce events`() {
        assertNull(judge(device(MatchEngine.DAY, Category.TRACKER, ignored = true), moved = true))
    }

    @Test fun `owned devices only return when watched`() {
        assertNull(judge(device(2 * HOUR, owned = true), moved = true))
        assertEquals(EventType.RETURNED, judge(device(2 * HOUR, owned = true, starred = true), moved = true)?.type)
    }

    @Test fun `a private address cannot return`() {
        assertNull(judge(device(7 * HOUR, identity = IdentityConfidence.ADDRESS_ONLY), moved = true))
    }

    @Test fun `no movement and a short gap is no return`() {
        assertNull(judge(device(2 * HOUR), moved = false))
    }
}
