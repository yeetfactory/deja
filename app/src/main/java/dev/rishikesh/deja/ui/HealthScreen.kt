package dev.rishikesh.deja.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rishikesh.deja.core.Capability
import dev.rishikesh.deja.core.Health
import dev.rishikesh.deja.core.HealthReport
import dev.rishikesh.deja.core.Level
import dev.rishikesh.deja.core.Recovery
import dev.rishikesh.deja.core.ScanJournal
import dev.rishikesh.deja.core.ScanPlan
import dev.rishikesh.deja.core.SystemState

@Composable
fun HealthScreen(
    report: HealthReport,
    journal: ScanJournal,
    system: SystemState,
    plan: ScanPlan?,
    now: Long,
    onBack: () -> Unit,
    onRecover: (Recovery) -> Unit,
) {
    fun at(time: Long) = if (time > 0) Health.ago(time, now) else "Never"

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Gutter)) {
        item {
            BackButton(onBack)
            Header("Scanner health", "What is working, and what to do when it isn't.")
            journal.serviceError?.let {
                Banner("Scanning stopped", "$it.", Icons.Outlined.ErrorOutline, alert = true, action = "Restart scanning", onAction = { onRecover(Recovery.RESUME) })
            }
            report.all.forEach { CapabilityCard(it, onRecover) }
            Spacer(Modifier.height(10.dp))

            SectionLabel("Recent scans")
            Group {
                DetailRow("Last completed scan", at(journal.lastBurstAt) + if (report.stale) ", results are stale" else "")
                Hairline(inset = 16.dp)
                DetailRow("Last Bluetooth scan", at(journal.lastBluetoothOk))
                Hairline(inset = 16.dp)
                DetailRow("Last Wi-Fi scan", at(journal.lastWifiOk))
                Hairline(inset = 16.dp)
                DetailRow("Last location fix", at(journal.lastFixAt))
                Hairline(inset = 16.dp)
                DetailRow("Failed scan cycles", "%,d".format(journal.failedBursts) + if (journal.failureAt > 0) ", last ${at(journal.failureAt)}" else "")
                journal.bluetoothFailure?.let {
                    Hairline(inset = 16.dp)
                    DetailRow("Bluetooth problem", it)
                }
                journal.wifiFailure?.let {
                    Hairline(inset = 16.dp)
                    DetailRow("Wi-Fi problem", it)
                }
            }
            Spacer(Modifier.height(20.dp))

            SectionLabel("Battery and coverage")
            Group {
                DetailRow(
                    "Current pattern",
                    plan?.let { "${it.burstMs / 1000} s scan every ${(it.burstMs + it.pauseMs) / 1000} s" } ?: "Not scanning",
                )
                Hairline(inset = 16.dp)
                DetailRow("Coverage", plan?.note ?: "Nothing is being heard")
                Hairline(inset = 16.dp)
                DetailRow("Battery Saver", if (system.powerSave) "On, so scans are reduced" else "Off")
            }
            Note(
                "When the phone sits idle with the screen off, Android may stretch the gap between scans to several minutes.",
                Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun CapabilityCard(capability: Capability, onRecover: (Recovery) -> Unit) {
    val icon = when (capability.level) {
        Level.OK -> Icons.Outlined.CheckCircle
        Level.WARNING -> Icons.Outlined.WarningAmber
        Level.BLOCKED -> Icons.Outlined.ErrorOutline
        Level.OFF -> Icons.Outlined.PauseCircle
    }
    Banner(
        capability.headline,
        capability.detail,
        icon,
        alert = capability.level == Level.BLOCKED,
        action = capability.recovery?.label,
        onAction = capability.recovery?.let { { onRecover(it) } },
    )
}
