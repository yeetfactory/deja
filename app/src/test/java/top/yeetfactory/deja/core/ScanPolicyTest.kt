package top.yeetfactory.deja.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPolicyTest {
    private fun plan(
        cadence: Cadence = Cadence.BALANCED,
        screenOn: Boolean = true,
        charging: Boolean = false,
        powerSave: Boolean = false,
        battery: Int = 80,
    ) = ScanPolicy.plan(cadence, Conditions(screenOn, charging, powerSave, battery))

    private fun duty(plan: ScanPlan) = plan.burstMs.toDouble() / (plan.burstMs + plan.pauseMs)

    @Test fun `a lit screen scans unfiltered at full speed`() {
        val plan = plan()
        assertFalse(plan.filtered)
        assertEquals(BleMode.LOW_LATENCY, plan.mode)
    }

    @Test fun `screen off must use filters, or Android delivers nothing`() {
        assertTrue(plan(screenOn = false).filtered)
    }

    @Test fun `screen off spends less than the old fixed pattern of 12 s in every 57 s`() {
        val off = plan(screenOn = false)
        assertTrue(duty(off) < 12.0 / 57.0 / 2)
        assertEquals(BleMode.BALANCED, off.mode)
    }

    @Test fun `screen off skips every other wi-fi scan on battery`() {
        assertEquals(2, plan(screenOn = false).wifiEvery)
        assertEquals(1, plan(screenOn = false, charging = true).wifiEvery)
    }

    @Test fun `battery saver and low battery back right off`() {
        listOf(plan(powerSave = true), plan(battery = 10)).forEach {
            assertEquals(BleMode.LOW_POWER, it.mode)
            assertEquals(180_000L, it.pauseMs)
            assertEquals(3, it.wifiEvery)
        }
    }

    @Test fun `low battery while charging is not constrained`() {
        assertEquals(BleMode.LOW_LATENCY, plan(battery = 10, charging = true).mode)
    }

    @Test fun `an unknown battery level is not treated as low`() {
        assertEquals(BleMode.LOW_LATENCY, plan(battery = -1).mode)
    }

    @Test fun `frequent cannot override battery saver`() {
        assertEquals(180_000L, plan(Cadence.FREQUENT, powerSave = true).pauseMs)
    }

    @Test fun `cadence orders the pauses`() {
        assertTrue(plan(Cadence.FREQUENT).pauseMs < plan().pauseMs)
        assertTrue(plan().pauseMs < plan(Cadence.SAVER).pauseMs)
    }

    @Test fun `no pattern breaks the wi-fi limit of four scans in two minutes`() {
        Cadence.entries.forEach { cadence ->
            val plan = plan(cadence)
            val perTwoMinutes = 120_000.0 / ((plan.burstMs + plan.pauseMs) * plan.wifiEvery)
            assertTrue("$cadence", perTwoMinutes <= 4)
        }
    }

    @Test fun `failures back off, up to a limit`() {
        assertEquals(45_000L, ScanPolicy.backoff(45_000, 0))
        assertEquals(90_000L, ScanPolicy.backoff(45_000, 1))
        assertEquals(ScanPolicy.MAX_BACKOFF_MS, ScanPolicy.backoff(45_000, 20))
    }

    @Test fun `backoff never shortens an already long pause`() {
        assertEquals(600_000L, ScanPolicy.backoff(600_000, 3))
    }
}
