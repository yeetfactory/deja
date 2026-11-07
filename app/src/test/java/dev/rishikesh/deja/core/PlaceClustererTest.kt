package dev.rishikesh.deja.core

import dev.rishikesh.deja.data.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceClustererTest {
    private val home = Place(id = 1, name = "Home", lat = 52.5200, lng = 13.4050, samples = 10)

    @Test fun `a fix near a place resolves to it`() {
        val result = PlaceClusterer.resolve(listOf(home), GeoFix(52.5205, 13.4050, 20f), null)
        assertEquals(home.id, (result as Resolution.Known).place.id)
    }

    @Test fun `an uncertain fix reaches further, so drift does not found a new place`() {
        val fix = GeoFix(52.5215, 13.4050, 90f) // about 167 m away, beyond the base radius
        assertTrue(PlaceClusterer.resolve(listOf(home), fix, null) is Resolution.Known)
        assertFalse(PlaceClusterer.resolve(listOf(home), fix.copy(accuracy = 20f), null) is Resolution.Known)
    }

    @Test fun `a good fix refines the centre, slowly`() {
        val result = PlaceClusterer.resolve(listOf(home), GeoFix(52.5205, 13.4050, 20f), null) as Resolution.Known
        val updated = result.updated!!
        assertTrue(updated.lat > home.lat && updated.lat < 52.5201)
        assertEquals(11, updated.samples)
    }

    @Test fun `a vague fix matches but never moves the centre`() {
        val result = PlaceClusterer.resolve(listOf(home), GeoFix(52.5205, 13.4050, 120f), null) as Resolution.Known
        assertNull(result.updated)
    }

    @Test fun `one fix somewhere new only makes a candidate`() {
        val fix = GeoFix(52.5300, 13.4200, 20f)
        assertEquals(Resolution.Candidate(fix), PlaceClusterer.resolve(listOf(home), fix, null))
    }

    @Test fun `a second agreeing fix creates the place`() {
        val first = GeoFix(52.5300, 13.4200, 20f)
        val second = GeoFix(52.5301, 13.4200, 20f)
        assertEquals(Resolution.Create(second), PlaceClusterer.resolve(listOf(home), second, first))
    }

    @Test fun `the flat 100 m that balanced location reports on real phones can found a place`() {
        val first = GeoFix(52.5300, 13.4200, 100f)
        assertEquals(Resolution.Candidate(first), PlaceClusterer.resolve(emptyList(), first, null))
        assertEquals(Resolution.Create(first), PlaceClusterer.resolve(emptyList(), first, first))
    }

    @Test fun `a 100 m fix does not move an established centre`() {
        val result = PlaceClusterer.resolve(listOf(home), GeoFix(52.5205, 13.4050, 100f), null) as Resolution.Known
        assertNull(result.updated)
    }

    @Test fun `passing through leaves nothing behind`() {
        val first = GeoFix(52.5300, 13.4200, 20f)
        val later = GeoFix(52.5400, 13.4400, 20f)
        assertEquals(Resolution.Candidate(later), PlaceClusterer.resolve(listOf(home), later, first))
    }

    @Test fun `a vague fix far from anywhere is not placed`() {
        assertEquals(Resolution.Unresolved, PlaceClusterer.resolve(listOf(home), GeoFix(52.5300, 13.4200, 150f), null))
    }

    @Test fun `a useless fix is not placed even next to a known place`() {
        assertEquals(Resolution.Unresolved, PlaceClusterer.resolve(listOf(home), GeoFix(52.5200, 13.4050, 500f), null))
    }

    @Test fun `places within location error are not apart`() {
        val near = home.copy(id = 2, lat = 52.5225) // about 280 m
        assertFalse(PlaceClusterer.apart(home, near, 50f))
    }

    @Test fun `places well beyond location error are apart`() {
        val far = home.copy(id = 2, lat = 52.5300)
        assertTrue(PlaceClusterer.apart(home, far, 50f))
    }

    @Test fun `no known place means no movement`() {
        assertFalse(PlaceClusterer.apart(null, home, 10f))
        assertFalse(PlaceClusterer.apart(home, home, 10f))
    }

    @Test fun `close places are offered as duplicates, nearest first`() {
        val close = home.copy(id = 2, lat = 52.5210)
        val closer = home.copy(id = 3, lat = 52.5203)
        val far = home.copy(id = 4, lat = 52.6000)
        val pairs = PlaceClusterer.duplicates(listOf(home, close, closer, far))
        assertEquals(1L to 3L, pairs.first().let { it.first.id to it.second.id })
        assertTrue(pairs.none { it.first.id == 4L || it.second.id == 4L })
    }
}
