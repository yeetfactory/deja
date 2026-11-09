package dev.rishikesh.deja.data

import android.app.UiModeManager
import android.content.Context
import androidx.core.content.edit
import dev.rishikesh.deja.core.Cadence
import dev.rishikesh.deja.core.ScanJournal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ThemeMode(
    val label: String,
    val nightMode: Int,
) {
    SYSTEM("System", UiModeManager.MODE_NIGHT_AUTO),
    LIGHT("Light", UiModeManager.MODE_NIGHT_NO),
    DARK("Dark", UiModeManager.MODE_NIGHT_YES),
}

data class AppSettings(
    /** The user's own choice. Nothing but the user changes this, so a pause survives anything. */
    val scanningEnabled: Boolean = true,
    val bluetoothEnabled: Boolean = true,
    val wifiEnabled: Boolean = true,
    val cadence: Cadence = Cadence.BALANCED,
    val alerts: AlertPrefs = AlertPrefs(),
    val retention: RetentionPolicy = RetentionPolicy(),
    val onboarded: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
)

/** Small state that must outlive the process. All of it is read synchronously at service start. */
class SettingsStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("deja", Context.MODE_PRIVATE)

    private val settingsState = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = settingsState.asStateFlow()

    private val journalState = MutableStateFlow(readJournal())
    val journal: StateFlow<ScanJournal> = journalState.asStateFlow()

    private fun readSettings() =
        AppSettings(
            scanningEnabled = prefs.getBoolean("scanning", true),
            bluetoothEnabled = prefs.getBoolean("bluetooth", true),
            wifiEnabled = prefs.getBoolean("wifi", true),
            cadence =
                prefs
                    .getString("cadence", null)
                    ?.let { name -> Cadence.entries.firstOrNull { it.name == name } } ?: Cadence.BALANCED,
            alerts =
                AlertPrefs(
                    following = prefs.getBoolean("alertFollowing", true),
                    watched = prefs.getBoolean("alertWatched", true),
                    newTracker = prefs.getBoolean("alertNewTracker", false),
                ),
            retention =
                RetentionPolicy(
                    historyDays = prefs.getInt("historyDays", 30),
                    unnamedDays = prefs.getInt("unnamedDays", 30),
                    encounterDays = prefs.getInt("encounterDays", 7),
                    keepSavedHistory = prefs.getBoolean("keepSavedHistory", true),
                ),
            onboarded = prefs.getBoolean("onboarded", false),
            theme =
                prefs
                    .getString("theme", null)
                    ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM,
        )

    fun update(change: (AppSettings) -> AppSettings) {
        settingsState.update(change)
        val value = settingsState.value
        prefs.edit {
            putBoolean("scanning", value.scanningEnabled)
            putBoolean("bluetooth", value.bluetoothEnabled)
            putBoolean("wifi", value.wifiEnabled)
            putString("cadence", value.cadence.name)
            putBoolean("alertFollowing", value.alerts.following)
            putBoolean("alertWatched", value.alerts.watched)
            putBoolean("alertNewTracker", value.alerts.newTracker)
            putInt("historyDays", value.retention.historyDays)
            putInt("unnamedDays", value.retention.unnamedDays)
            putInt("encounterDays", value.retention.encounterDays)
            putBoolean("keepSavedHistory", value.retention.keepSavedHistory)
            putBoolean("onboarded", value.onboarded)
            putString("theme", value.theme.name)
        }
    }

    private fun readJournal() =
        ScanJournal(
            lastBluetoothOk = prefs.getLong("lastBluetoothOk", 0),
            lastWifiOk = prefs.getLong("lastWifiOk", 0),
            lastFixAt = prefs.getLong("lastFixAt", 0),
            lastFixAccuracy = prefs.getFloat("lastFixAccuracy", 0f),
            lastBurstAt = prefs.getLong("lastBurstAt", 0),
            bluetoothFailure = prefs.getString("bluetoothFailure", null),
            wifiFailure = prefs.getString("wifiFailure", null),
            failedBursts = prefs.getInt("failedBursts", 0),
            failureAt = prefs.getLong("failureAt", 0),
            serviceError = prefs.getString("serviceError", null),
        )

    fun journal(change: (ScanJournal) -> ScanJournal) {
        journalState.update(change)
        val value = journalState.value
        prefs.edit {
            putLong("lastBluetoothOk", value.lastBluetoothOk)
            putLong("lastWifiOk", value.lastWifiOk)
            putLong("lastFixAt", value.lastFixAt)
            putFloat("lastFixAccuracy", value.lastFixAccuracy)
            putLong("lastBurstAt", value.lastBurstAt)
            putString("bluetoothFailure", value.bluetoothFailure)
            putString("wifiFailure", value.wifiFailure)
            putInt("failedBursts", value.failedBursts)
            putLong("failureAt", value.failureAt)
            putString("serviceError", value.serviceError)
        }
    }

    /** Android only reveals "denied for good" as a rationale answer, so the asking must be remembered. */
    fun wasAsked(permission: String) = prefs.getBoolean("asked:$permission", false)

    fun markAsked(permission: String) = prefs.edit { putBoolean("asked:$permission", true) }

    fun isDeniedForever(permission: String) = prefs.getBoolean("forever:$permission", false)

    fun setDeniedForever(
        permission: String,
        forever: Boolean,
    ) = prefs.edit { putBoolean("forever:$permission", forever) }
}
