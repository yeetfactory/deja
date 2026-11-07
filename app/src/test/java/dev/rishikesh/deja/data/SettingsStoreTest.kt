package dev.rishikesh.deja.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.rishikesh.deja.core.Cadence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A fresh store reads from disk, which is what a new process or a recreated activity sees. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `a pause survives a restart`() {
        SettingsStore(context).update { it.copy(scanningEnabled = false) }
        assertFalse(SettingsStore(context).settings.value.scanningEnabled)
    }

    @Test fun `scanning defaults to on and the theme to system`() {
        val settings = SettingsStore(context).settings.value
        assertTrue(settings.scanningEnabled)
        assertEquals(ThemeMode.SYSTEM, settings.theme)
    }

    @Test fun `every choice survives a restart`() {
        SettingsStore(context).update {
            it.copy(
                bluetoothEnabled = false,
                cadence = Cadence.SAVER,
                theme = ThemeMode.DARK,
                alerts = AlertPrefs(following = false, watched = false, newTracker = true),
                retention = RetentionPolicy(historyDays = 7, unnamedDays = 90, encounterDays = 1, keepSavedHistory = false),
            )
        }
        val after = SettingsStore(context).settings.value
        assertFalse(after.bluetoothEnabled)
        assertEquals(Cadence.SAVER, after.cadence)
        assertEquals(ThemeMode.DARK, after.theme)
        assertEquals(AlertPrefs(following = false, watched = false, newTracker = true), after.alerts)
        assertEquals(RetentionPolicy(7, 90, 1, false), after.retention)
    }

    @Test fun `the failure record survives a restart`() {
        SettingsStore(context).journal { it.copy(serviceError = "Stopped", failedBursts = 3) }
        val journal = SettingsStore(context).journal.value
        assertEquals("Stopped", journal.serviceError)
        assertEquals(3, journal.failedBursts)
    }

    @Test fun `a refused permission is remembered`() {
        val permission = "android.permission.BLUETOOTH_SCAN"
        SettingsStore(context).markAsked(permission)
        SettingsStore(context).setDeniedForever(permission, true)
        assertTrue(SettingsStore(context).wasAsked(permission))
        assertTrue(SettingsStore(context).isDeniedForever(permission))
    }
}
