package dev.rishikesh.deja.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTest {
    private val now = 1_000_000_000L

    private val healthy =
        SystemState(
            bluetoothPermission = Permission.GRANTED,
            location = LocationAccess.PRECISE,
            notificationPermission = Permission.GRANTED,
            bluetoothOn = true,
            wifiOn = true,
            locationOn = true,
            notificationsEnabled = true,
        )

    private val working =
        ScanJournal(
            lastBluetoothOk = now - 1000,
            lastWifiOk = now - 1000,
            lastFixAt = now - 1000,
            lastFixAccuracy = 12f,
            lastBurstAt = now - 1000,
        )

    private fun report(
        system: SystemState = healthy,
        journal: ScanJournal = working,
        bluetooth: Boolean = true,
        wifi: Boolean = true,
        alerts: Boolean = true,
        listening: Boolean = true,
        paused: Boolean = false,
    ) = Health.report(HealthInputs(system, bluetooth, wifi, alerts, listening, paused, journal, 180_000, now))

    @Test fun `everything working is all clear`() {
        val report = report()
        assertTrue(report.all.all { it.level == Level.OK })
        assertTrue(report.canScan)
        assertEquals(null, report.worst)
    }

    @Test fun `each radio reports separately`() {
        val report = report(healthy.copy(bluetoothOn = false))
        assertEquals(Level.BLOCKED, report.bluetooth.level)
        assertEquals(Recovery.ENABLE_BLUETOOTH, report.bluetooth.recovery)
        assertEquals(Level.OK, report.wifi.level)
        assertTrue("Wi-Fi alone can still scan", report.canScan)
    }

    @Test fun `wi-fi off is fine when background wi-fi scanning is on`() {
        assertEquals(Level.OK, report(healthy.copy(wifiOn = false, wifiScanAlways = true)).wifi.level)
    }

    @Test fun `wi-fi off with no background scanning blocks it`() {
        val report = report(healthy.copy(wifiOn = false))
        assertEquals(Level.BLOCKED, report.wifi.level)
        assertEquals(Recovery.WIFI_SETTINGS, report.wifi.recovery)
    }

    @Test fun `location off blocks both radios, and says why`() {
        val report = report(healthy.copy(locationOn = false))
        assertEquals(Level.BLOCKED, report.bluetooth.level)
        assertEquals(Level.BLOCKED, report.wifi.level)
        assertFalse(report.canScan)
        assertEquals(Recovery.LOCATION_SETTINGS, report.worst?.recovery)
    }

    @Test fun `approximate location is not enough to scan`() {
        val report = report(healthy.copy(location = LocationAccess.APPROXIMATE))
        assertFalse(report.canScan)
        assertEquals("Approximate only", report.location.summary)
    }

    @Test fun `a first refusal can be asked again`() {
        val report = report(healthy.copy(location = LocationAccess.DENIED))
        assertEquals(Recovery.GRANT_SCANNING, report.location.recovery)
    }

    @Test fun `a permanent refusal points to system settings`() {
        val report = report(healthy.copy(location = LocationAccess.PERMANENTLY_DENIED, bluetoothPermission = Permission.PERMANENTLY_DENIED))
        assertEquals(Recovery.APP_SETTINGS, report.location.recovery)
        assertEquals(Recovery.APP_SETTINGS, report.bluetooth.recovery)
    }

    @Test fun `bluetooth denied leaves wi-fi scanning`() {
        val report = report(healthy.copy(bluetoothPermission = Permission.DENIED))
        assertEquals(Level.BLOCKED, report.bluetooth.level)
        assertTrue(report.canScan)
    }

    @Test fun `no notification permission still scans, with alerts clearly disabled`() {
        val report = report(healthy.copy(notificationPermission = Permission.DENIED))
        assertTrue(report.canScan)
        assertEquals("Alerts disabled", report.notifications.summary)
        assertEquals(Level.WARNING, report.notifications.level)
        assertEquals(Recovery.GRANT_NOTIFICATIONS, report.notifications.recovery)
    }

    @Test fun `a blocked alert channel is reported apart from the permission`() {
        val report = report(healthy.copy(alertChannelEnabled = false))
        assertEquals(Recovery.ALERT_CHANNEL_SETTINGS, report.notifications.recovery)
    }

    @Test fun `alerts turned off in the app are a choice, not a fault`() {
        assertEquals(Level.OFF, report(alerts = false).notifications.level)
    }

    @Test fun `a radio turned off in the app is a choice, not a fault`() {
        val report = report(bluetooth = false)
        assertEquals(Level.OFF, report.bluetooth.level)
        assertTrue(report.canScan)
    }

    @Test fun `both radios off in the app means nothing can scan`() {
        assertFalse(report(bluetooth = false, wifi = false).canScan)
    }

    @Test fun `a failed scan is shown even though the radio is on`() {
        val report = report(journal = working.copy(bluetoothFailure = "The Bluetooth radio is out of scan slots"))
        assertEquals(Level.WARNING, report.bluetooth.level)
        assertTrue("out of scan slots" in report.bluetooth.detail)
    }

    @Test fun `old results are stale`() {
        val old = now - 10 * MatchEngine.MINUTE
        val report = report(journal = working.copy(lastBluetoothOk = old, lastBurstAt = old))
        assertTrue(report.stale)
        assertEquals("Results are stale", report.bluetooth.summary)
    }

    @Test fun `a fresh install has no stale results`() {
        val report = report(journal = ScanJournal())
        assertFalse(report.stale)
        assertEquals("Starting", report.bluetooth.summary)
    }

    @Test fun `pausing is a choice, with a way back`() {
        val report = report(listening = false, paused = true)
        assertEquals(Level.OFF, report.bluetooth.level)
        assertEquals(Recovery.RESUME, report.bluetooth.recovery)
    }

    @Test fun `not running without having been paused is a warning`() {
        assertEquals(Level.WARNING, report(listening = false).bluetooth.level)
    }

    @Test fun `no fix yet is a warning, not a failure`() {
        assertEquals("Waiting for a fix", report(journal = working.copy(lastFixAt = 0)).location.summary)
    }

    @Test fun `the service needs precise location even if the radios are off`() {
        assertTrue(Health.canRunService(healthy.copy(bluetoothOn = false, wifiOn = false)))
        assertFalse(Health.canRunService(healthy.copy(location = LocationAccess.APPROXIMATE)))
    }
}
