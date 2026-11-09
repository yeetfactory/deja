package top.yeetfactory.deja.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yeetfactory.deja.data.Category
import top.yeetfactory.deja.data.Device
import top.yeetfactory.deja.data.IdentityConfidence
import top.yeetfactory.deja.data.Observation
import top.yeetfactory.deja.data.Radio
import top.yeetfactory.deja.data.TypeConfidence

class MergeTest {
    private fun obs(
        category: Category = Category.UNKNOWN,
        confidence: TypeConfidence = TypeConfidence.NONE,
        descriptor: String? = null,
        name: String? = null,
        rssi: Int = -60,
        identity: IdentityConfidence = IdentityConfidence.STABLE,
    ) = Observation(
        key = "ble:x",
        radio = Radio.BLE,
        name = name,
        category = category,
        rssi = rssi,
        typeConfidence = confidence,
        descriptor = descriptor,
        identity = identity,
        evidence = "evidence for $category",
    )

    private val findMy = obs(Category.FIND_MY, TypeConfidence.LOW, "Apple Find My broadcast · Model unknown")
    private val airpods = obs(Category.AUDIO, TypeConfidence.MEDIUM, "AirPods Pro")

    private fun stored(observation: Observation) = Merge.newDevice(observation, null, 1)

    @Test fun `better evidence updates an old classification`() {
        val merged = Merge.device(stored(findMy), airpods, null, 2)
        assertEquals(Category.AUDIO, merged.category)
        assertEquals("AirPods Pro", merged.descriptor)
        assertEquals(TypeConfidence.MEDIUM, merged.typeConfidence)
        assertEquals("evidence for AUDIO", merged.evidence)
    }

    @Test fun `weaker evidence does not undo a better classification`() {
        val merged = Merge.device(stored(airpods), findMy, null, 2)
        assertEquals(Category.AUDIO, merged.category)
        assertEquals("AirPods Pro", merged.descriptor)
    }

    @Test fun `equal evidence does not make a device flicker`() {
        val merged = Merge.device(stored(findMy), obs(Category.WEARABLE, TypeConfidence.LOW), null, 2)
        assertEquals(Category.FIND_MY, merged.category)
    }

    @Test fun `equal evidence can fill in an unknown`() {
        val merged = Merge.device(stored(obs(confidence = TypeConfidence.LOW)), findMy, null, 2)
        assertEquals(Category.FIND_MY, merged.category)
    }

    @Test fun `a manual category survives better evidence`() {
        val manual = stored(findMy).copy(category = Category.PHONE, categoryManual = true)
        val merged = Merge.device(manual, airpods, null, 2)
        assertEquals(Category.PHONE, merged.category)
        assertTrue(merged.categoryManual)
    }

    @Test fun `the automatic answer keeps improving under a manual category`() {
        val manual = stored(findMy).copy(category = Category.PHONE, categoryManual = true)
        assertEquals(Category.AUDIO, Merge.device(manual, airpods, null, 2).autoCategory)
    }

    @Test fun `labels and flags survive every observation`() {
        val mine = stored(findMy).copy(label = "My bag", starred = true, owned = true, ignored = true)
        val merged = Merge.device(mine, airpods, null, 2)
        assertEquals("My bag", merged.label)
        assertTrue(merged.starred && merged.owned && merged.ignored)
    }

    @Test fun `a real name is kept when later packets have none`() {
        val named = stored(obs(name = "Headphones"))
        assertEquals("Headphones", Merge.device(named, obs(), null, 2).broadcastName)
    }

    @Test fun `signal readings accumulate`() {
        val first = stored(obs(rssi = -60))
        val merged = Merge.device(Merge.device(first, obs(rssi = -40), null, 2), obs(rssi = -80), null, 3)
        assertEquals(-80, merged.minRssi)
        assertEquals(-40, merged.maxRssi)
        assertEquals(-80, merged.lastRssi)
        assertEquals(3L, merged.observations)
    }

    @Test fun `a device keeps the least stable identity it has shown`() {
        val rotating = stored(obs(identity = IdentityConfidence.ADDRESS_ONLY))
        assertEquals(IdentityConfidence.ADDRESS_ONLY, Merge.device(rotating, obs(), null, 2).identity)
    }

    @Test fun `no location keeps the last known place`() {
        val placed: Device = stored(obs()).copy(lastPlaceId = 7)
        assertEquals(7L, Merge.device(placed, obs(), null, 2).lastPlaceId)
    }

    @Test fun `packets in one burst combine, keeping the best classification`() {
        val merged =
            Merge.packets(
                airpods.copy(rssi = -50, minRssi = -50, maxRssi = -50),
                findMy.copy(rssi = -70, minRssi = -70, maxRssi = -70),
            )
        assertEquals(Category.AUDIO, merged.category)
        assertEquals(-70, merged.rssi)
        assertEquals(-70, merged.minRssi)
        assertEquals(-50, merged.maxRssi)
        assertEquals(2, merged.readings)
    }
}
