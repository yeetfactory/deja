package dev.rishikesh.deja.core

import dev.rishikesh.deja.core.MatchEngine.DAY
import dev.rishikesh.deja.core.MatchEngine.HOUR
import dev.rishikesh.deja.data.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlanceTest {
    private val now = 100 * DAY
    private val home = Place(id = 1, name = "Home", lat = 52.5200, lng = 13.4050)
    private val nextDoor = Place(id = 2, name = "Drift", lat = 52.5210, lng = 13.4050) // about 110 m
    private val cafe = Place(id = 3, name = "Café", lat = 52.5300, lng = 13.4200)
    private val places = listOf(home, nextDoor, cafe).associateBy { it.id }

    @Test fun `a device at two separate places is with you`() {
        val rows = listOf(DevicePlace(7, 1), DevicePlace(7, 3))
        assertEquals(setOf(7L), GlanceEngine.withYou(rows, places))
    }

    @Test fun `one place is not with you`() {
        assertTrue(GlanceEngine.withYou(listOf(DevicePlace(7, 1)), places).isEmpty())
    }

    @Test fun `location drift between two close places is not with you`() {
        val rows = listOf(DevicePlace(7, 1), DevicePlace(7, 2))
        assertTrue(GlanceEngine.withYou(rows, places).isEmpty())
    }

    @Test fun `a place that no longer exists is ignored`() {
        assertTrue(GlanceEngine.withYou(listOf(DevicePlace(7, 1), DevicePlace(7, 99)), places).isEmpty())
    }

    @Test fun `a young place is still learning, and calls nothing new`() {
        val glance = GlanceEngine.sort(emptySet(), listOf(1, 2), emptyList(), now - HOUR, now)
        assertTrue(glance.learning)
        assertTrue(glance.newHere.isEmpty())
        assertTrue(glance.regulars.isEmpty())
    }

    @Test fun `a place with no history is learning`() {
        assertTrue(GlanceEngine.sort(emptySet(), listOf(1), emptyList(), null, now).learning)
    }

    @Test fun `devices split into new and regular`() {
        val first = listOf(FirstHeard(1, now - HOUR), FirstHeard(2, now - 5 * DAY))
        val glance = GlanceEngine.sort(emptySet(), listOf(1, 2), first, now - 9 * DAY, now)
        assertFalse(glance.learning)
        assertEquals(setOf(1L), glance.newHere)
        assertEquals(setOf(2L), glance.regulars)
    }

    @Test fun `a device with no record here counts as new`() {
        assertEquals(setOf(3L), GlanceEngine.sort(emptySet(), listOf(3), emptyList(), now - 9 * DAY, now).newHere)
    }

    @Test fun `every device in range lands in exactly one group`() {
        val first = listOf(FirstHeard(1, now - HOUR), FirstHeard(2, now - 5 * DAY))
        val glance = GlanceEngine.sort(emptySet(), listOf(1, 2, 3), first, now - 9 * DAY, now)
        assertEquals(setOf(1L, 2L, 3L), glance.newHere + glance.regulars)
        assertTrue((glance.newHere intersect glance.regulars).isEmpty())
    }

    @Test fun `with you survives learning`() {
        assertEquals(setOf(9L), GlanceEngine.sort(setOf(9), emptyList(), emptyList(), null, now).withYou)
    }

    @Test fun `the headline is calm by default and specific otherwise`() {
        assertEquals("Nothing unusual", GlanceEngine.headline(Glance()))
        assertEquals("1 device has been at more than one of your places today", GlanceEngine.headline(Glance(withYou = setOf(1))))
        assertEquals("2 devices have been at more than one of your places today", GlanceEngine.headline(Glance(withYou = setOf(1, 2))))
    }

    @Test fun `the detail says what is and isn't known`() {
        assertEquals("3 nearby · place not known yet", GlanceEngine.detail(Glance(), 3))
        assertEquals("3 nearby · learning this place", GlanceEngine.detail(Glance(learning = true, placeKnown = true), 3))
        assertEquals("3 nearby · 1 new here", GlanceEngine.detail(Glance(newHere = setOf(1), placeKnown = true), 3))
    }
}
