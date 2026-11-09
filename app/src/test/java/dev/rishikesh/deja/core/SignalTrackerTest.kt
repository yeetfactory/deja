package dev.rishikesh.deja.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalTrackerTest {
    private fun tracker(vararg readings: Pair<Long, Int>) =
        SignalTracker().apply {
            readings.forEach { (second, rssi) -> add(second * 1000, rssi) }
        }

    /** One reading a second for each value in turn, ending at second [values].size - 1. */
    private fun series(vararg values: Int) =
        SignalTracker().apply {
            values.forEachIndexed { second, rssi -> add(second * 1000L, rssi) }
        }

    @Test fun `nothing heard yet is unknown, not zero`() {
        val state = SignalTracker().state(1000)
        assertNull(state.rssi)
        assertEquals(Trend.UNKNOWN, state.trend)
    }

    @Test fun `one reading shows a number but no trend`() {
        val state = tracker(1L to -60).state(1000)
        assertEquals(-60, state.rssi)
        assertEquals(Trend.UNKNOWN, state.trend)
    }

    @Test fun `walking toward a device reads as stronger`() {
        val state = series(-80, -80, -79, -80, -78, -79, -80, -80, -70, -68, -66, -65, -64, -63).state(13_000)
        assertEquals(Trend.STRONGER, state.trend)
    }

    @Test fun `walking away reads as weaker`() {
        val state = series(-50, -51, -50, -52, -50, -51, -50, -52, -62, -64, -66, -67, -68, -69).state(13_000)
        assertEquals(Trend.WEAKER, state.trend)
    }

    @Test fun `ordinary noise does not move the trend`() {
        val state = series(-60, -63, -58, -61, -60, -62, -59, -60, -61, -58, -62, -60, -63, -59).state(13_000)
        assertEquals(Trend.STEADY, state.trend)
    }

    @Test fun `one wild reading moves neither the number nor the trend`() {
        val state = series(-60, -60, -60, -60, -60, -60, -60, -60, -60, -60, -60, -35, -60, -60).state(13_000)
        assertEquals(-60, state.rssi)
        assertEquals(Trend.STEADY, state.trend)
    }

    @Test fun `going quiet drops the trend rather than repeating an old one`() {
        val moving = series(-80, -80, -80, -80, -80, -80, -80, -80, -70, -68, -66, -65, -64, -63)
        assertEquals(Trend.STRONGER, moving.state(13_000).trend)
        assertEquals(Trend.UNKNOWN, moving.state(30_000).trend)
    }

    @Test fun `the last reading is kept once nothing recent is left`() {
        val state = tracker(1L to -60, 2L to -70).state(40_000)
        assertEquals(-70, state.rssi)
        assertEquals(2000L, state.lastHeard)
    }

    @Test fun `history is capped at a minute`() {
        val tracker = SignalTracker()
        repeat(200) { tracker.add(it * 1000L, -60) }
        val history = tracker.state(199_000).history
        assertTrue(history.first().time >= 139_000)
        assertEquals(61, history.size)
    }
}
