package dev.rishikesh.deja.scan

import android.os.Build
import dev.rishikesh.deja.BuildConfig
import dev.rishikesh.deja.core.AddressKind
import dev.rishikesh.deja.core.Advert
import dev.rishikesh.deja.core.BleClassifier
import dev.rishikesh.deja.core.GeoFix
import dev.rishikesh.deja.core.MatchEngine
import dev.rishikesh.deja.core.Merge
import dev.rishikesh.deja.data.Observation
import dev.rishikesh.deja.data.Radio
import kotlin.math.min
import kotlin.random.Random

/**
 * A fake town for the emulator, which has no real radios. Debug builds only, and the UI
 * shows a "Simulated data" notice while it runs. Two days of walking between three spots are
 * replayed quickly, so the return and following rules can be exercised end to end.
 */
class SimulatedSource : Source {
    override val simulated = true

    private data class Actor(
        val id: Int,
        val name: String?,
        val spots: Set<Int>,
        val chance: Float = 0.8f,
        val services: Set<Int> = emptySet(),
    )

    private val spots =
        listOf(
            GeoFix(52.5200, 13.4050, 15f, "Home"),
            GeoFix(52.5300, 13.4200, 15f, "Café"),
            GeoFix(52.5100, 13.3900, 15f, "Gym"),
        )

    private val cast =
        buildList {
            add(Actor(1, null, setOf(0, 1, 2), 1f, setOf(0xFEED)))
            add(Actor(2, "Pixel Buds", setOf(0, 1)))
            add(Actor(3, "Fitness band", setOf(1, 2)))
            add(Actor(4, "My car", setOf(0, 2)))
            add(Actor(5, "Living room TV", setOf(0), 1f))
            repeat(9) { add(Actor(10 + it, null, setOf(0))) }
            repeat(8) { add(Actor(30 + it, null, setOf(2))) }
        }

    private val start = System.currentTimeMillis() - 2 * MatchEngine.DAY
    private var tick = 0

    override fun start() = Unit

    override fun stop() = Unit

    override fun now(): Long = min(start + tick * STEP_MS, System.currentTimeMillis())

    override fun fix(): GeoFix = spots[spot()]

    private fun spot() = (tick / TICKS_PER_SPOT) % spots.size

    override suspend fun burst(request: BurstRequest): Burst {
        tick++
        val here = spot()
        val time = now()
        val stable =
            cast
                .filter { here in it.spots && Random.nextFloat() < it.chance }
                .map { actor ->
                    val address = "C0:00:00:00:00:%02X".format(actor.id)
                    observation(
                        Advert(address, AddressKind.RANDOM, actor.name, services = actor.services),
                        -42 - actor.id % 50 + Random.nextInt(-3, 4),
                        time,
                    )
                }
        val rotating =
            List(Random.nextInt(4, 10)) { index ->
                val address = "4%X:00:00:%02X:00:00".format(index % 16, Random.nextInt(256))
                val apple = mapOf(0x004C to byteArrayOf(0x10, 0x05, 0x01, 0x18, 0x00, 0x00, 0x00))
                observation(Advert(address, AddressKind.RANDOM, manufacturer = apple), -58 - index * 2, time)
            }
        val networks =
            (0 until 3).map { index ->
                val bssid = "00:11:22:33:44:%02X".format(here * 3 + index)
                val ssid = "Network ${here * 3 + index}"
                Merge.observation("wifi:$bssid", Radio.WIFI, ssid, -60 - index, time, BleClassifier.classifyWifi(bssid, ssid))
            }
        return Burst(stable + rotating + networks, RadioResult.Ok(stable.size + rotating.size), RadioResult.Ok(networks.size))
    }

    private fun observation(
        advert: Advert,
        rssi: Int,
        time: Long,
    ): Observation = Merge.observation("ble:${advert.address}", Radio.BLE, advert.name, rssi, time, BleClassifier.classify(advert))

    companion object {
        private const val STEP_MS = 20 * MatchEngine.MINUTE
        private const val TICKS_PER_SPOT = 3

        val isAvailable: Boolean
            get() = BuildConfig.DEBUG && Build.HARDWARE.let { it == "ranchu" || it == "goldfish" }
    }
}
