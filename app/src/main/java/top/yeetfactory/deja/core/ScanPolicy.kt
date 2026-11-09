package top.yeetfactory.deja.core

enum class Cadence(
    val label: String,
    val detail: String,
) {
    SAVER("Battery saver", "Fewer, gentler scans. Brief encounters are more likely to be missed."),
    BALANCED("Balanced", "Scans about once a minute with the screen on, less often with it off."),
    FREQUENT("Frequent", "Scans more often and uses more battery."),
}

enum class BleMode { LOW_LATENCY, BALANCED, LOW_POWER }

data class Conditions(
    val screenOn: Boolean,
    val charging: Boolean,
    val powerSave: Boolean,
    val batteryPercent: Int,
)

data class ScanPlan(
    val burstMs: Long,
    val pauseMs: Long,
    val mode: BleMode,
    /** Android withholds unfiltered scans while the screen is off, so only known brands get through. */
    val filtered: Boolean,
    /** Run a Wi-Fi scan on every nth burst. */
    val wifiEvery: Int,
    val note: String,
)

/** Trades coverage for battery. Pure, so the whole table can be checked in tests. */
object ScanPolicy {
    private const val SECOND = 1_000L
    const val LOW_BATTERY_PERCENT = 15

    /** Longest wait after repeated failures. */
    const val MAX_BACKOFF_MS = 5 * 60 * SECOND

    /** How long to wait while nothing can scan. A system change wakes the loop sooner. */
    const val IDLE_PAUSE_MS = 2 * 60 * SECOND

    fun plan(
        cadence: Cadence,
        conditions: Conditions,
    ): ScanPlan {
        val constrained =
            conditions.powerSave ||
                (!conditions.charging && conditions.batteryPercent in 0..LOW_BATTERY_PERCENT)
        val filtered = !conditions.screenOn

        if (constrained) {
            return ScanPlan(8 * SECOND, 180 * SECOND, BleMode.LOW_POWER, filtered, wifiEvery = 3, note = "Reduced to save battery")
        }

        val scale =
            when (cadence) {
                Cadence.SAVER -> 2.0
                Cadence.BALANCED -> 1.0
                Cadence.FREQUENT -> 0.5
            }
        val base =
            when {
                conditions.screenOn -> 45
                conditions.charging -> 60
                else -> 90
            }
        val mode =
            when {
                cadence == Cadence.SAVER -> BleMode.LOW_POWER
                conditions.screenOn -> BleMode.LOW_LATENCY
                else -> BleMode.BALANCED
            }
        return ScanPlan(
            burstMs = (if (conditions.screenOn) 10 else 8) * SECOND,
            pauseMs = (base * scale).toLong() * SECOND,
            mode = mode,
            filtered = filtered,
            wifiEvery = if (conditions.screenOn || conditions.charging) 1 else 2,
            note = if (filtered) "Screen off: only known brands and trackers are heard" else "Screen on: hearing everything",
        )
    }

    /** Doubles with each consecutive failure, so a broken radio is not hammered. */
    fun backoff(
        pauseMs: Long,
        failures: Int,
    ): Long {
        if (failures <= 0) return pauseMs
        return minOf(pauseMs shl minOf(failures, 6), MAX_BACKOFF_MS).coerceAtLeast(pauseMs)
    }

    /** Results older than this are shown as stale. Generous, so a slow cycle never looks broken. */
    fun staleAfter(plan: ScanPlan?): Long = 3 * ((plan?.pauseMs ?: 180 * SECOND) + (plan?.burstMs ?: 10 * SECOND))
}
