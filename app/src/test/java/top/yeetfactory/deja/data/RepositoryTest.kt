package top.yeetfactory.deja.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.yeetfactory.deja.core.EvidenceCodec
import top.yeetfactory.deja.core.GeoFix
import top.yeetfactory.deja.core.MatchEngine.DAY
import top.yeetfactory.deja.core.MatchEngine.HOUR
import top.yeetfactory.deja.core.MatchEngine.MINUTE

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepositoryTest {
    private lateinit var db: DejaDatabase
    private lateinit var repository: Repository
    private val dao get() = repository.dao
    private val start = 1000 * DAY

    @Before fun setUp() {
        db = inMemoryDatabase()
        repository = Repository(db)
    }

    @After fun tearDown() = db.close()

    private suspend fun place(
        name: String,
        lat: Double,
        lng: Double,
    ): Place {
        val id = dao.insertPlace(Place(name = name, lat = lat, lng = lng))
        return Place(id = id, name = name, lat = lat, lng = lng)
    }

    private suspend fun follow(
        key: String,
        prefs: AlertPrefs = AlertPrefs(),
        category: Category = Category.TRACKER,
        identity: IdentityConfidence = IdentityConfidence.STABLE,
    ): List<IngestResult> {
        val home = place("Home", 52.52, 13.405)
        val cafe = place("Café", 52.53, 13.42)
        val gym = place("Gym", 52.51, 13.39)
        val obs = observation(key, category, TypeConfidence.MEDIUM, identity)
        return listOf(0L to home, 10L to home, 40L to cafe, 50L to cafe, 90L to gym, 100L to gym).map { (minute, where) ->
            repository.ingest(listOf(obs), where, 15f, start + minute * MINUTE, prefs)
        }
    }

    @Test fun `following produces an alert with its evidence`() =
        runBlocking {
            val alert = follow("ble:tag").flatMap { it.alerts }.single()
            assertEquals(EventType.FOLLOWING, alert.type)
            val row = dao.eventById(alert.eventId)!!
            assertTrue(row.eligible)
            assertEquals(Delivery.PENDING, row.delivery)
            assertEquals(listOf("Home", "Café", "Gym"), EvidenceCodec.decode(row.evidence).map { it.placeName })
            assertTrue(row.summary, row.summary.startsWith("Seen 5 times at 3 separate places"))
        }

    @Test fun `an alert turned off stays eligible, but is not posted`() =
        runBlocking {
            val results = follow("ble:tag", AlertPrefs(following = false))
            assertTrue(results.all { it.alerts.isEmpty() })
            val event = dao.watchEvents(0, Long.MAX_VALUE, 10).first().first { it.type == EventType.FOLLOWING }
            assertTrue(event.eligible)
            assertEquals(Delivery.DISABLED_IN_SETTINGS, event.delivery)
        }

    @Test fun `a disabled alert still respects the cooldown`() =
        runBlocking {
            follow("ble:tag", AlertPrefs(following = false))
            val gym = dao.places().first { it.name == "Gym" }
            val obs = observation("ble:tag", Category.TRACKER, TypeConfidence.MEDIUM)
            repository.ingest(listOf(obs), gym, 15f, start + 110 * MINUTE, AlertPrefs(following = false))
            val following = dao.watchEvents(0, Long.MAX_VALUE, 50).first().count { it.type == EventType.FOLLOWING }
            assertEquals(1, following)
        }

    @Test fun `a private address never triggers following`() =
        runBlocking {
            val results = follow("ble:rotating", identity = IdentityConfidence.ADDRESS_ONLY)
            assertTrue(results.all { it.alerts.none { alert -> alert.type == EventType.FOLLOWING } })
        }

    @Test fun `a device marked as mine stops following alerts`() =
        runBlocking {
            val home = place("Start", 52.0, 13.0)
            val obs = observation("ble:mine", Category.TRACKER, TypeConfidence.MEDIUM)
            repository.ingest(listOf(obs), home, 15f, start - HOUR)
            repository.markMine(dao.deviceByKey("ble:mine")!!.id)
            val results = follow("ble:mine")
            assertTrue(results.all { it.alerts.isEmpty() })
        }

    @Test fun `marking as mine settles open alerts`() =
        runBlocking {
            val alert = follow("ble:tag").flatMap { it.alerts }.single()
            repository.markMine(alert.device.id)
            val row = dao.eventById(alert.eventId)!!
            assertTrue(row.dismissed)
            assertTrue(row.owned)
        }

    @Test fun `delivery is recorded separately from eligibility`() =
        runBlocking {
            val alert = follow("ble:tag").flatMap { it.alerts }.single()
            dao.setDelivery(alert.eventId, Delivery.BLOCKED_PERMISSION)
            val row = dao.eventById(alert.eventId)!!
            assertTrue(row.eligible)
            assertEquals(Delivery.BLOCKED_PERMISSION, row.delivery)
        }

    @Test fun `private addresses are saved as encounters`() =
        runBlocking {
            val obs =
                observation("ble:4A", Category.AUDIO, TypeConfidence.MEDIUM, IdentityConfidence.ADDRESS_ONLY, temporary = true, rssi = -55)
            repository.ingest(listOf(obs), null, null, start)
            repository.ingest(listOf(obs.copy(rssi = -75, minRssi = -75, maxRssi = -75)), null, null, start + 6 * MINUTE)
            val device = dao.deviceByKey("ble:4A")!!
            assertTrue(device.temporary)
            assertEquals(start, device.firstSeen)
            assertEquals(start + 6 * MINUTE, device.lastSeen)
            assertEquals(-75, device.minRssi)
            assertEquals(-55, device.maxRssi)
            assertEquals(2, dao.watchHistoryPoints(device.id).first().size)
            assertEquals(1, dao.watchTotals().first().encounters)
            assertEquals(0, dao.watchTotals().first().devices)
        }

    @Test fun `addresses sharing a fingerprint and signal are never merged`() =
        runBlocking {
            val a =
                observation(
                    "ble:4A",
                    identity = IdentityConfidence.ADDRESS_ONLY,
                    temporary = true,
                    fingerprint = "vendor=Apple;frames=10",
                    rssi = -60,
                )
            val b = a.copy(key = "ble:4B")
            repository.ingest(listOf(a), null, null, start)
            repository.ingest(listOf(b), null, null, start + MINUTE)
            assertNotNull(dao.deviceByKey("ble:4A"))
            assertNotNull(dao.deviceByKey("ble:4B"))
            assertEquals(2, dao.watchTotals().first().encounters)
        }

    @Test fun `sightings are limited to one per interval per place`() =
        runBlocking {
            val obs = observation("ble:x")
            repository.ingest(listOf(obs), null, null, start)
            repository.ingest(listOf(obs), null, null, start + MINUTE)
            val device = dao.deviceByKey("ble:x")!!
            assertEquals(1, dao.watchHistoryPoints(device.id).first().size)
            assertEquals(2L, device.observations)
        }

    @Test fun `totals do not depend on any page size`() =
        runBlocking {
            val obs = observation("ble:x")
            repeat(250) { repository.ingest(listOf(obs), null, null, start + it * 6 * MINUTE) }
            val device = dao.deviceByKey("ble:x")!!
            assertEquals(250, dao.watchTotals().first().sightings)
            assertEquals(250, dao.watchHistoryPoints(device.id).first().size)
            assertEquals(20, dao.watchSightings(device.id, 20).first().size)
        }

    @Test fun `user flags survive a scan`() =
        runBlocking {
            val obs = observation("ble:x")
            repository.ingest(listOf(obs), null, null, start)
            val device = dao.deviceByKey("ble:x")!!
            repository.updateFlags(device.copy(label = "Keys", starred = true, owned = true))
            repository.ingest(listOf(obs), null, null, start + MINUTE)
            val after = dao.deviceByKey("ble:x")!!
            assertEquals("Keys", after.label)
            assertTrue(after.starred && after.owned)
        }

    @Test fun `resetting a manual category restores the automatic one`() =
        runBlocking {
            repository.ingest(listOf(observation("ble:x", Category.AUDIO, TypeConfidence.MEDIUM)), null, null, start)
            val id = dao.deviceByKey("ble:x")!!.id
            dao.setCategory(id, Category.PHONE)
            assertEquals(Category.PHONE, dao.deviceById(id)!!.category)
            dao.resetCategory(id)
            val after = dao.deviceById(id)!!
            assertEquals(Category.AUDIO, after.category)
            assertFalse(after.categoryManual)
        }

    @Test fun `a new place needs two agreeing fixes`() =
        runBlocking {
            assertNull(repository.resolvePlace(GeoFix(52.5, 13.4, 20f)))
            assertEquals("Place 1", repository.resolvePlace(GeoFix(52.5001, 13.4, 20f))!!.name)
            assertEquals(1, dao.places().size)
        }

    @Test fun `sightings get a place at real-world location accuracy`() =
        runBlocking {
            val fix = GeoFix(52.5, 13.4, 100f)
            assertNull(repository.resolvePlace(fix))
            val place = repository.resolvePlace(fix)
            assertNotNull(place)
            repository.ingest(listOf(observation("ble:x")), place, fix.accuracy, start)
            val device = dao.deviceByKey("ble:x")!!
            assertEquals(
                place!!.id,
                dao
                    .watchHistoryPoints(device.id)
                    .first()
                    .single()
                    .placeId,
            )
        }

    @Test fun `merging places moves everything that happened there`() =
        runBlocking {
            val alert = follow("ble:tag").flatMap { it.alerts }.single()
            val places = dao.places().associateBy { it.name }
            repository.mergePlaces(places.getValue("Café").id, places.getValue("Home").id)

            val remaining = dao.places().map { it.name }.toSet()
            assertEquals(setOf("Home", "Gym"), remaining)
            val device = dao.deviceById(alert.device.id)!!
            val points = dao.watchHistoryPoints(device.id).first()
            assertEquals(6, points.size)
            assertTrue(points.none { it.placeId == places.getValue("Café").id })
            val steps = EvidenceCodec.decode(dao.eventById(alert.eventId)!!.evidence)
            assertEquals(listOf("Home", "Home", "Gym"), steps.map { it.placeName })
        }

    @Test fun `merging a place into itself does nothing`() =
        runBlocking {
            val home = place("Home", 52.52, 13.405)
            repository.mergePlaces(home.id, home.id)
            assertEquals(1, dao.places().size)
        }

    private val policy = RetentionPolicy(historyDays = 30, unnamedDays = 30, encounterDays = 7, keepSavedHistory = true)

    @Test fun `retention forgets old unnamed devices`() =
        runBlocking {
            repository.ingest(listOf(observation("ble:old")), null, null, start)
            repository.prune(start + 31 * DAY, policy)
            assertNull(dao.deviceByKey("ble:old"))
        }

    @Test fun `encounters expire on their own shorter schedule`() =
        runBlocking {
            val encounter = observation("ble:4A", temporary = true, identity = IdentityConfidence.ADDRESS_ONLY)
            repository.ingest(listOf(encounter, observation("ble:stable")), null, null, start)
            repository.prune(start + 8 * DAY, policy)
            assertNull(dao.deviceByKey("ble:4A"))
            assertNotNull(dao.deviceByKey("ble:stable"))
        }

    @Test fun `a saved device keeps its row and history by default`() =
        runBlocking {
            repository.ingest(listOf(observation("ble:x")), null, null, start)
            val device = dao.deviceByKey("ble:x")!!
            repository.updateFlags(device.copy(label = "Keys"))
            repository.prune(start + 100 * DAY, policy)
            assertEquals(1, dao.watchHistoryPoints(device.id).first().size)
        }

    @Test fun `a label can outlive its location history`() =
        runBlocking {
            val home = place("Home", 52.52, 13.405)
            repository.ingest(listOf(observation("ble:x")), home, 10f, start)
            val device = dao.deviceByKey("ble:x")!!
            repository.updateFlags(device.copy(label = "Keys"))
            repository.prune(start + 100 * DAY, policy.copy(keepSavedHistory = false))

            val after = dao.deviceByKey("ble:x")!!
            assertEquals("Keys", after.label)
            assertTrue(dao.watchHistoryPoints(device.id).first().isEmpty())
            assertNull(after.lastPlaceId)
            assertTrue(dao.places().isEmpty())
        }

    @Test fun `old events are cleaned up`() =
        runBlocking {
            follow("ble:tag")
            repository.prune(start + 31 * DAY, policy.copy(unnamedDays = 365))
            assertEquals(0, dao.watchTotals().first().events)
        }

    @Test fun `cleanup keeps places a person named`() =
        runBlocking {
            val named = place("Office", 52.0, 13.0)
            dao.renamePlace(named.id, "Office")
            place("Place 2", 53.0, 14.0)
            repository.prune(start, policy)
            assertEquals(listOf("Office"), dao.places().map { it.name })
        }

    @Test fun `deleting history keeps the device and its name`() =
        runBlocking {
            val alert = follow("ble:tag").flatMap { it.alerts }.single()
            repository.updateFlags(alert.device.copy(label = "Tag"))
            repository.deleteDeviceHistory(alert.device.id)

            val after = dao.deviceById(alert.device.id)!!
            assertEquals("Tag", after.label)
            assertEquals(0L, after.observations)
            assertNull(after.lastPlaceId)
            val totals = dao.watchTotals().first()
            assertEquals(0, totals.sightings)
            assertEquals(0, totals.events)
            assertEquals(0, totals.places)
        }

    @Test fun `deleting a device removes everything about it`() =
        runBlocking {
            val alert = follow("ble:tag").flatMap { it.alerts }.single()
            repository.deleteDevice(alert.device.id)
            assertNull(dao.deviceById(alert.device.id))
            assertEquals(Totals(), dao.watchTotals().first())
        }

    @Test fun `clear all leaves nothing`() =
        runBlocking {
            follow("ble:tag")
            repository.clearAll()
            assertEquals(Totals(), dao.watchTotals().first())
        }

    @Test fun `the export contains the observations behind an alert`() =
        runBlocking {
            val alert = follow("ble:tag").flatMap { it.alerts }.single()
            val csv = repository.exportEvent(alert.eventId)!!
            assertTrue(csv, "FOLLOWING" in csv)
            assertTrue(csv, "Café" in csv)
            assertTrue(csv, ",-60," in csv)
        }

    @Test fun `search and filters run in the database`() =
        runBlocking {
            repository.ingest(
                listOf(
                    observation("ble:a", Category.TRACKER, TypeConfidence.MEDIUM),
                    observation("ble:b", Category.AUDIO, TypeConfidence.MEDIUM),
                    observation("ble:c", temporary = true, identity = IdentityConfidence.ADDRESS_ONLY),
                ),
                null,
                null,
                start,
            )
            val device = dao.deviceByKey("ble:b")!!
            repository.updateFlags(device.copy(label = "100% Keys_1", owned = true))

            suspend fun list(
                search: String,
                filter: DeviceFilter,
            ) = dao.watchDeviceList(DeviceQuery.list(search, filter, DeviceSort.RECENT, 50)).first().map { it.key }

            assertEquals(listOf("ble:a"), list("", DeviceFilter.TRACKERS))
            assertEquals(listOf("ble:b"), list("", DeviceFilter.MINE))
            assertEquals(listOf("ble:c"), list("", DeviceFilter.PRIVATE))
            assertEquals(2, list("", DeviceFilter.REMEMBERED).size)
            assertEquals(listOf("ble:b"), list("keys", DeviceFilter.REMEMBERED))
            assertEquals("% is matched literally", listOf("ble:b"), list("100%", DeviceFilter.REMEMBERED))
            assertEquals("_ is matched literally", emptyList<String>(), list("Key__1", DeviceFilter.REMEMBERED))
            assertEquals(1, dao.watchDeviceCount(DeviceQuery.count("keys", DeviceFilter.REMEMBERED)).first())
        }

    @Test fun `the summary finds a device at two separate places today`() =
        runBlocking {
            follow("ble:tag")
            val id = dao.deviceByKey("ble:tag")!!.id
            val gym = dao.places().first { it.name == "Gym" }
            val glance = repository.glance(listOf(id), gym.id, start - HOUR, start + 200 * MINUTE)
            assertEquals(setOf(id), glance.withYou)
            assertTrue("this place is under a day old", glance.learning)
        }

    @Test fun `the summary leaves out your own gear, private addresses and Wi-Fi`() =
        runBlocking {
            follow("ble:mine")
            val mine = dao.deviceByKey("ble:mine")!!
            repository.updateFlags(mine.copy(owned = true))
            val home = dao.places().first { it.name == "Home" }
            val cafe = dao.places().first { it.name == "Café" }
            val rotating = observation("ble:4A", identity = IdentityConfidence.ADDRESS_ONLY, temporary = true)
            val router = observation("wifi:aa", Category.NETWORK, TypeConfidence.HIGH).copy(radio = Radio.WIFI)
            repository.ingest(listOf(rotating, router), home, 15f, start)
            repository.ingest(listOf(rotating, router), cafe, 15f, start + HOUR)
            assertTrue(repository.glance(emptyList(), home.id, start - HOUR, start + 2 * HOUR).withYou.isEmpty())
        }

    @Test fun `the summary splits new from regular once a place has history`() =
        runBlocking {
            val home = place("Home", 52.52, 13.405)
            repository.ingest(listOf(observation("ble:old")), home, 15f, start)
            val later = start + 3 * DAY
            repository.ingest(listOf(observation("ble:old"), observation("ble:new")), home, 15f, later)
            val old = dao.deviceByKey("ble:old")!!.id
            val fresh = dao.deviceByKey("ble:new")!!.id
            val glance = repository.glance(listOf(old, fresh), home.id, later - HOUR, later + MINUTE)
            assertFalse(glance.learning)
            assertEquals(setOf(fresh), glance.newHere)
            assertEquals(setOf(old), glance.regulars)
        }

    @Test fun `the summary works without a place`() =
        runBlocking {
            val glance = repository.glance(listOf(1L), null, 0, start)
            assertFalse(glance.placeKnown)
        }

    @Test fun `every sort order is valid sql`() =
        runBlocking {
            repository.ingest(listOf(observation("ble:a")), null, null, start)
            DeviceSort.entries.forEach { sort ->
                assertEquals(1, dao.watchDeviceList(DeviceQuery.list("", DeviceFilter.REMEMBERED, sort, 10)).first().size)
            }
        }

    @Test fun `the event list pages, and its total does not`() =
        runBlocking {
            val id =
                dao.insertDevice(
                    Device(
                        key = "k",
                        radio = Radio.BLE,
                        broadcastName = null,
                        category = Category.UNKNOWN,
                        firstSeen = 0,
                        lastSeen = 0,
                        lastRssi = 0,
                    ),
                )
            repeat(250) {
                dao.insertEvent(
                    Event(
                        deviceId = id,
                        type = EventType.RETURNED,
                        time = start + it,
                        fromPlaceId = null,
                        toPlaceId = null,
                        eligible = false,
                    ),
                )
            }
            assertEquals(50, dao.watchEvents(0, Long.MAX_VALUE, 50).first().size)
            assertEquals(250, dao.watchEventCount(0, Long.MAX_VALUE).first())
            assertEquals(10, dao.watchEventCount(start + 240, Long.MAX_VALUE).first())
        }

    @Test fun `a dismissed warning stops showing, and an owned one never shows`() =
        runBlocking {
            val alert = follow("ble:tag").flatMap { it.alerts }.single()
            assertNotNull(dao.watchActiveWarning(0).first())
            dao.dismissEvent(alert.eventId)
            assertNull(dao.watchActiveWarning(0).first())
        }
}
