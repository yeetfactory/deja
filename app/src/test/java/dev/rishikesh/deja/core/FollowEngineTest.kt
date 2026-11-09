package dev.rishikesh.deja.core

import dev.rishikesh.deja.core.MatchEngine.MINUTE
import dev.rishikesh.deja.data.Category
import dev.rishikesh.deja.data.Device
import dev.rishikesh.deja.data.IdentityConfidence
import dev.rishikesh.deja.data.Radio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowEngineTest {
    private val home = Triple(1L, 52.5200, 13.4050)
    private val cafe = Triple(2L, 52.5300, 13.4200)
    private val gym = Triple(3L, 52.5100, 13.3900)

    private fun at(
        place: Triple<Long, Double, Double>,
        minute: Int,
        accuracy: Float? = 20f,
    ) = TrailPoint(
        time = minute * MINUTE,
        placeId = place.first,
        placeName = "Place ${place.first}",
        lat = place.second,
        lng = place.third,
        accuracy = accuracy,
        rssi = -60,
    )

    private val trail = listOf(at(home, 0), at(home, 10), at(cafe, 40), at(cafe, 50), at(gym, 90), at(gym, 100))

    private fun device(
        owned: Boolean = false,
        ignored: Boolean = false,
        identity: IdentityConfidence = IdentityConfidence.STABLE,
    ) = Device(
        key = "ble:x",
        radio = Radio.BLE,
        broadcastName = null,
        category = Category.TRACKER,
        firstSeen = 0,
        lastSeen = 0,
        lastRssi = -60,
        owned = owned,
        ignored = ignored,
        identity = identity,
    )

    @Test fun `repeated readings across three separate places over time is following`() {
        assertTrue(FollowEngine.assess(trail).following)
    }

    @Test fun `the evidence explains itself`() {
        val assessment = FollowEngine.assess(trail)
        assertEquals(3, assessment.steps.size)
        assertEquals("Place 1", assessment.steps.first().placeName)
        assertTrue(assessment.summary, assessment.summary.startsWith("Seen 6 times at 3 separate places over 1 h 40 min"))
    }

    @Test fun `too few observations is not following`() {
        assertFalse(FollowEngine.assess(listOf(at(home, 0), at(cafe, 40), at(gym, 90))).following)
    }

    @Test fun `two places is not following`() {
        assertFalse(FollowEngine.assess(trail.filter { it.placeId != 3L }).following)
    }

    @Test fun `a short burst of readings is not following`() {
        val quick = listOf(at(home, 0), at(home, 1), at(cafe, 2), at(cafe, 3), at(gym, 4), at(gym, 5))
        assertFalse(FollowEngine.assess(quick).following)
    }

    @Test fun `location drift around one spot is not movement`() {
        val a = Triple(1L, 52.5200, 13.4050)
        val b = Triple(2L, 52.5209, 13.4050) // about 100 m north
        val c = Triple(3L, 52.5200, 13.4065) // about 100 m east
        val drift = listOf(at(a, 0), at(a, 10), at(b, 40), at(b, 50), at(c, 90), at(c, 100))
        val assessment = FollowEngine.assess(drift)
        assertFalse(assessment.following)
        assertEquals(1, assessment.steps.size)
    }

    @Test fun `places that vague fixes cannot tell apart count as one`() {
        val a = Triple(1L, 52.5200, 13.4050)
        val b = Triple(2L, 52.5240, 13.4050) // about 440 m north
        val c = Triple(3L, 52.5300, 13.4200)
        val vague = listOf(at(a, 0, 120f), at(a, 10, 120f), at(b, 40, 120f), at(b, 50, 120f), at(c, 90, 120f), at(c, 100, 120f))
        assertFalse(FollowEngine.assess(vague).following)
    }

    @Test fun `three spots inside a neighbourhood are not enough spread`() {
        val a = Triple(1L, 52.5200, 13.4050)
        val b = Triple(2L, 52.5235, 13.4050) // about 390 m
        val c = Triple(3L, 52.5255, 13.4050) // about 610 m from the first
        val near = listOf(at(a, 0, 10f), at(a, 10, 10f), at(b, 40, 10f), at(b, 50, 10f), at(c, 90, 10f), at(c, 100, 10f))
        assertFalse(FollowEngine.assess(near).following)
    }

    @Test fun `an empty trail is handled`() {
        assertFalse(FollowEngine.assess(emptyList()).following)
    }

    @Test fun `owned and ignored devices are excluded`() {
        assertFalse(FollowEngine.eligible(device(owned = true)))
        assertFalse(FollowEngine.eligible(device(ignored = true)))
    }

    @Test fun `only an uncertain identity is excluded`() {
        assertTrue(FollowEngine.eligible(device()))
        assertTrue(FollowEngine.eligible(device(identity = IdentityConfidence.MAY_CHANGE)))
        assertFalse(FollowEngine.eligible(device(identity = IdentityConfidence.ADDRESS_ONLY)))
    }

    @Test fun `evidence survives encoding, including an awkward place name`() {
        val steps = listOf(EvidenceStep(1, 2, 3, "Caf|é, home", 4, -50, 12.5f), EvidenceStep(5, 6, 7, "Gym", 1, -70, null))
        assertEquals(steps, EvidenceCodec.decode(EvidenceCodec.encode(steps)))
    }

    @Test fun `damaged evidence is skipped rather than fatal`() {
        assertEquals(emptyList<EvidenceStep>(), EvidenceCodec.decode("not|evidence"))
        assertEquals(emptyList<EvidenceStep>(), EvidenceCodec.decode(""))
    }
}
