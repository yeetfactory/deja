package dev.rishikesh.deja.core

enum class Trend { STRONGER, WEAKER, STEADY, UNKNOWN }

data class SignalReading(
    val time: Long,
    val rssi: Int,
)

data class SignalState(
    /** The smoothed strength, or null until something has been heard. */
    val rssi: Int?,
    val trend: Trend,
    val lastHeard: Long,
    val history: List<SignalReading>,
)

/**
 * Turns a noisy stream of readings into something a person can walk with. A single reading can
 * swing by 10 dB with nothing moving, so both the number and the trend come from medians, and the
 * trend only moves once two stretches of time really differ.
 */
class SignalTracker {
    private val readings = ArrayDeque<SignalReading>()

    fun add(
        time: Long,
        rssi: Int,
    ) {
        readings.addLast(SignalReading(time, rssi))
        while (readings.isNotEmpty() && time - readings.first().time > HISTORY_MS) readings.removeFirst()
    }

    private fun median(values: List<Int>): Int = values.sorted()[values.size / 2]

    fun state(now: Long): SignalState {
        val last = readings.lastOrNull() ?: return SignalState(null, Trend.UNKNOWN, 0, emptyList())
        val recent = readings.filter { now - it.time <= RECENT_MS }.map { it.rssi }
        val earlier = readings.filter { now - it.time in (RECENT_MS + 1)..EARLIER_MS }.map { it.rssi }

        val trend =
            when {
                recent.size < MIN_RECENT || earlier.size < MIN_EARLIER -> Trend.UNKNOWN
                median(recent) - median(earlier) >= THRESHOLD_DB -> Trend.STRONGER
                median(earlier) - median(recent) >= THRESHOLD_DB -> Trend.WEAKER
                else -> Trend.STEADY
            }
        return SignalState(
            rssi = if (recent.isEmpty()) last.rssi else median(recent),
            trend = trend,
            lastHeard = last.time,
            history = readings.toList(),
        )
    }

    companion object {
        const val HISTORY_MS = 60_000L
        const val RECENT_MS = 5_000L
        const val EARLIER_MS = 15_000L
        const val MIN_RECENT = 2
        const val MIN_EARLIER = 3
        const val THRESHOLD_DB = 4

        /** After this long without a reading, say so instead of showing an old number as current. */
        const val SILENT_AFTER_MS = 15_000L
    }
}
