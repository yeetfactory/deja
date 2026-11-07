package dev.rishikesh.deja.scan

import dev.rishikesh.deja.core.ScanPlan
import dev.rishikesh.deja.core.SystemState
import dev.rishikesh.deja.data.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Blip(
    val deviceId: Long,
    val key: String,
    val title: String,
    val subtitle: String,
    val category: Category,
    val rssi: Int,
    val starred: Boolean,
    val owned: Boolean,
    val returning: Boolean,
    val temporary: Boolean,
    val heardAt: Long,
)

data class Live(
    val listening: Boolean = false,
    val simulated: Boolean = false,
    val placeId: Long? = null,
    val placeName: String? = null,
    /** Everything heard recently, stable addresses and private ones alike. */
    val blips: List<Blip> = emptyList(),
    val plan: ScanPlan? = null,
) {
    val inRange: List<Blip> get() = blips.filterNot { it.temporary }
    val privateAddresses: List<Blip> get() = blips.filter { it.temporary }
}

/** What is in range right now. Lives in memory only; the database holds the history. */
object LiveState {
    private const val MIN_TTL_MS = 120_000L

    private val state = MutableStateFlow(Live())
    val live: StateFlow<Live> = state.asStateFlow()

    private val systemState = MutableStateFlow(SystemState())
    val system: StateFlow<SystemState> = systemState.asStateFlow()

    fun setSystem(value: SystemState) {
        systemState.value = value
    }

    fun setListening(simulated: Boolean) = state.update { it.copy(listening = true, simulated = simulated) }

    fun setStopped() {
        state.value = Live()
    }

    fun setPlan(plan: ScanPlan) = state.update { it.copy(plan = plan) }

    /** A device stays listed for a couple of scan cycles, so one missed packet is not a departure. */
    private fun ttl(plan: ScanPlan?) =
        maxOf(MIN_TTL_MS, 5 * ((plan?.pauseMs ?: 0L) + (plan?.burstMs ?: 0L)) / 2)

    fun publish(blips: List<Blip>, placeId: Long?, placeName: String?, now: Long) = state.update { current ->
        val fresh = blips.mapTo(HashSet()) { it.deviceId }
        val kept = current.blips.filter { it.deviceId !in fresh && now - it.heardAt < ttl(current.plan) }
        current.copy(blips = kept + blips, placeId = placeId, placeName = placeName)
    }

    /** Drops what has aged out. Runs even when a scan fails, so nothing lingers as if still here. */
    fun expire(now: Long) = state.update { current ->
        current.copy(blips = current.blips.filter { now - it.heardAt < ttl(current.plan) })
    }
}
