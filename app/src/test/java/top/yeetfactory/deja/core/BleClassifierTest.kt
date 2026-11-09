package top.yeetfactory.deja.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yeetfactory.deja.data.Category
import top.yeetfactory.deja.data.IdentityConfidence
import top.yeetfactory.deja.data.TypeConfidence

class BleClassifierTest {
    private val static = "CA:11:22:33:44:55"
    private val resolvable = "4A:11:22:33:44:55"

    private fun apple(vararg bytes: Int) = mapOf(0x004C to ByteArray(bytes.size) { bytes[it].toByte() })

    /** A long Find My frame: type, length, status, then 24 bytes of key material. */
    private fun separated(status: Int) = apple(0x12, 0x19, status, *IntArray(24))

    private fun classify(
        manufacturer: Map<Int, ByteArray> = emptyMap(),
        name: String? = null,
        services: Set<Int> = emptySet(),
        serviceData: Map<Int, ByteArray> = emptyMap(),
        address: String = static,
        kind: AddressKind = AddressKind.RANDOM,
    ) = BleClassifier.classify(Advert(address, kind, name, manufacturer, services, serviceData))

    @Test fun `resolvable private addresses are rotating`() {
        assertTrue(BleClassifier.isRotating(resolvable, AddressKind.RANDOM, emptyMap()))
    }

    @Test fun `non resolvable addresses are rotating`() {
        assertTrue(BleClassifier.isRotating("0A:11:22:33:44:55", AddressKind.RANDOM, emptyMap()))
    }

    @Test fun `static random addresses are stable`() {
        assertFalse(BleClassifier.isRotating(static, AddressKind.RANDOM, emptyMap()))
    }

    @Test fun `public addresses are stable whatever their top bits`() {
        assertFalse(BleClassifier.isRotating(resolvable, AddressKind.PUBLIC, emptyMap()))
    }

    @Test fun `unknown address type with private bits is treated as rotating`() {
        assertTrue(BleClassifier.isRotating(resolvable, AddressKind.UNKNOWN, emptyMap()))
    }

    @Test fun `find my near its owner is rotating even with a static looking address`() {
        assertTrue(BleClassifier.isRotating(static, AddressKind.RANDOM, apple(0x12, 0x02, 0x00, 0x00)))
    }

    @Test fun `a plain find my broadcast is not called a tracker`() {
        val result = classify(separated(0x00))
        assertEquals(Category.FIND_MY, result.category)
        assertEquals("Apple Find My broadcast · Model unknown", result.descriptor)
        assertEquals(TypeConfidence.LOW, result.typeConfidence)
    }

    @Test fun `a separated find my address may change and is kept as an encounter`() {
        val result = classify(separated(0x00))
        assertEquals(IdentityConfidence.MAY_CHANGE, result.identity)
        assertTrue(result.temporary)
    }

    @Test fun `a near owner find my frame is this address only`() {
        val result = classify(apple(0x12, 0x02, 0x00, 0x00))
        assertEquals(Category.FIND_MY, result.category)
        assertEquals(IdentityConfidence.ADDRESS_ONLY, result.identity)
    }

    @Test fun `find my status bits add a word but never make a tracker`() {
        listOf(
            0x10 to "Apple Find My broadcast · Possible AirTag",
            0x20 to "Apple Find My broadcast · Possible accessory",
        ).forEach { (status, text) ->
            val result = classify(separated(status))
            assertEquals(Category.FIND_MY, result.category)
            assertEquals(text, result.descriptor)
            assertEquals(TypeConfidence.LOW, result.typeConfidence)
        }
    }

    @Test fun `an airpods status hint stays low confidence`() {
        val result = classify(separated(0x30))
        assertEquals(Category.AUDIO, result.category)
        assertEquals(TypeConfidence.LOW, result.typeConfidence)
    }

    @Test fun `find my status battery bits do not change the type`() {
        assertEquals(Category.FIND_MY, classify(separated(0xC0)).category)
    }

    @Test fun `a proximity pairing frame names the model it claims`() {
        val result = classify(apple(0x07, 0x05, 0x01, 0x0E, 0x20, 0x00, 0x00))
        assertEquals(Category.AUDIO, result.category)
        assertEquals("AirPods Pro", result.descriptor)
    }

    @Test fun `the byte before the model is not relied on`() {
        assertEquals("AirPods Pro", classify(apple(0x07, 0x05, 0x07, 0x0E, 0x20, 0x00, 0x00)).descriptor)
    }

    @Test fun `a claimed model alone is never high confidence`() {
        val result = classify(apple(0x07, 0x05, 0x01, 0x0E, 0x20, 0x00, 0x00))
        assertEquals(TypeConfidence.MEDIUM, result.typeConfidence)
    }

    @Test fun `a claimed model backed by the name is high confidence`() {
        val result = classify(apple(0x07, 0x05, 0x01, 0x0E, 0x20, 0x00, 0x00), name = "Sam's AirPods Pro")
        assertEquals(TypeConfidence.HIGH, result.typeConfidence)
    }

    @Test fun `an unrecognised model says so instead of guessing`() {
        val result = classify(apple(0x07, 0x05, 0x01, 0x7F, 0x7F, 0x00, 0x00))
        assertEquals(Category.AUDIO, result.category)
        assertEquals("AirPods or Beats · Model unknown", result.descriptor)
    }

    @Test fun `pairing found behind a find my frame still wins, as seen on real devices`() {
        val result = classify(apple(0x12, 0x02, 0x00, 0x00, 0x07, 0x05, 0x01, 0x14, 0x20, 0x00, 0x00))
        assertEquals("AirPods Pro (2nd generation)", result.descriptor)
        assertEquals(IdentityConfidence.ADDRESS_ONLY, result.identity)
    }

    @Test fun `a truncated pairing frame does not crash`() {
        assertEquals(TypeConfidence.LOW, classify(apple(0x07, 0x09, 0x01)).typeConfidence)
    }

    @Test fun `a mac is recognised from its name and an ipad from its name`() {
        assertEquals(Category.COMPUTER, classify(separated(0x00), name = "Sam's MacBook Pro").category)
        assertEquals(Category.TABLET, classify(separated(0x00), name = "iPad").category)
    }

    @Test fun `handoff and nearby frames admit the model is unknown`() {
        val result = classify(apple(0x0C, 0x00, 0x10, 0x00), address = resolvable)
        assertEquals(Category.UNKNOWN, result.category)
        assertEquals("Apple device · Model unknown", result.descriptor)
        assertEquals(TypeConfidence.LOW, result.typeConfidence)
    }

    @Test fun `private addresses are classified rather than discarded`() {
        val result = classify(apple(0x07, 0x05, 0x01, 0x0E, 0x20, 0x00, 0x00), address = resolvable)
        assertEquals("AirPods Pro", result.descriptor)
        assertEquals(IdentityConfidence.ADDRESS_ONLY, result.identity)
        assertTrue(result.temporary)
    }

    @Test fun `type confidence and identity confidence are independent`() {
        val sureOfTypeNotIdentity = classify(services = setOf(0xFEED), address = resolvable)
        assertEquals(TypeConfidence.HIGH, sureOfTypeNotIdentity.typeConfidence)
        assertEquals(IdentityConfidence.ADDRESS_ONLY, sureOfTypeNotIdentity.identity)

        val sureOfIdentityNotType = classify(kind = AddressKind.PUBLIC)
        assertEquals(TypeConfidence.NONE, sureOfIdentityNotType.typeConfidence)
        assertEquals(IdentityConfidence.STABLE, sureOfIdentityNotType.identity)
    }

    @Test fun `tracker services mark a tracker`() {
        listOf(0xFEED, 0xFEEC, 0xFE33, 0xFA25, 0xFD5A).forEach {
            assertEquals(Category.TRACKER, classify(services = setOf(it)).category)
        }
    }

    @Test fun `tracker service data counts as well as a service list`() {
        assertEquals(Category.TRACKER, classify(serviceData = mapOf(0xFEED to byteArrayOf(2, 0))).category)
    }

    @Test fun `google find hub frames are finding broadcasts and plain eddystone is a beacon`() {
        val hub = classify(serviceData = mapOf(0xFEAA to byteArrayOf(0x40, 0x00)), services = setOf(0xFEAA))
        assertEquals(Category.FIND_MY, hub.category)
        assertEquals("Google Find Hub broadcast · Model unknown", hub.descriptor)
        assertEquals(Category.BEACON, classify(services = setOf(0xFEAA)).category)
    }

    @Test fun `names classify ordinary gear`() {
        assertEquals(Category.AUDIO, classify(name = "Pixel Buds Pro").category)
        assertEquals(Category.WEARABLE, classify(name = "Garmin Forerunner").category)
        assertEquals(Category.UNKNOWN, classify().category)
    }

    @Test fun `names match whole words only`() {
        assertEquals(Category.UNKNOWN, classify(name = "Oxford Projectile").category)
        assertEquals(Category.UNKNOWN, classify(name = "ZX QRT042").category)
    }

    @Test fun `known company ids give a readable fallback name`() {
        assertEquals("Apple device", BleClassifier.vendor(apple(0x10)))
        assertEquals("Samsung device", BleClassifier.vendor(mapOf(0x0075 to ByteArray(0))))
        assertEquals(null, BleClassifier.vendor(mapOf(0xFFFF to ByteArray(0))))
    }

    @Test fun `apple frames are read in order and survive truncated data`() {
        assertEquals(listOf(0x10, 0x0C), BleClassifier.appleFrames(apple(0x10, 0x01, 0xAA, 0x0C, 0x00)))
        assertEquals(listOf(0x12), BleClassifier.appleFrames(apple(0x12)))
        assertEquals(emptyList<Int>(), BleClassifier.appleFrames(emptyMap()))
    }

    @Test fun `every classification carries evidence`() {
        assertTrue(classify().evidence.isNotEmpty())
        assertTrue(classify(separated(0x00)).evidence.any { "Find My" in it })
    }

    @Test fun `the fingerprint holds no address`() {
        assertFalse("11:22:33" in classify(separated(0x00)).fingerprint)
    }

    @Test fun `different addresses can share a fingerprint`() {
        val data = apple(0x10, 0x00)
        val a = classify(data, address = "4A:00:00:00:00:01")
        val b = classify(data, address = "4B:00:00:00:00:02")
        assertEquals(a.fingerprint, b.fingerprint)
        assertNotEquals("4A:00:00:00:00:01", "4B:00:00:00:00:02")
    }

    @Test fun `a locally administered bssid may change`() {
        assertEquals(IdentityConfidence.MAY_CHANGE, BleClassifier.classifyWifi("02:00:00:00:00:01", "Guest").identity)
        assertEquals(IdentityConfidence.STABLE, BleClassifier.classifyWifi("00:1a:00:00:00:01", "Home").identity)
    }

    @Test fun `a hidden network says so`() {
        assertEquals("Hidden Wi-Fi network", BleClassifier.classifyWifi("00:1a:00:00:00:01", null).descriptor)
    }
}
