package top.yeetfactory.deja.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Upgrades a database built from the exact schema dumped off a phone running version 1. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"
    private var opened: DejaDatabase? = null

    @Before fun setUp() {
        context.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        V1_SCHEMA.forEach(db::execSQL)
        V1_ROWS.forEach(db::execSQL)
        db.version = 1
        db.close()
    }

    @After fun tearDown() {
        opened?.close()
        context.deleteDatabase(name)
    }

    /** Room checks the migrated tables against the entities on open, and fails on any mismatch. */
    private fun migrated(): DejaDao {
        val db =
            Room
                .databaseBuilder(context, DejaDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .allowMainThreadQueries()
                .build()
        opened = db
        return db.dao()
    }

    @Test fun `the migrated schema matches the entities`() =
        runBlocking {
            assertEquals(5, migrated().watchTotals().first().let { it.devices + it.encounters })
        }

    @Test fun `every row survives`() =
        runBlocking {
            val totals = migrated().watchTotals().first()
            assertEquals(3, totals.sightings)
            assertEquals(2, totals.events)
            assertEquals(2, totals.places)
        }

    @Test fun `labels and flags are preserved`() =
        runBlocking {
            val device = migrated().deviceByKey("ble:AA:AA:AA:AA:AA:01")!!
            assertEquals("My headphones", device.label)
            assertTrue(device.starred)
            assertEquals("WH-1000XM5", device.broadcastName)
            assertEquals(Category.AUDIO, device.category)
        }

    @Test fun `a find my tracker row is honestly downgraded`() =
        runBlocking {
            val device = migrated().deviceByKey("ble:CA:00:00:00:00:01")!!
            assertEquals(Category.FIND_MY, device.category)
            assertEquals("Apple Find My broadcast · Model unknown", device.title)
            assertEquals(TypeConfidence.LOW, device.typeConfidence)
            assertEquals(IdentityConfidence.MAY_CHANGE, device.identity)
            assertEquals("Apple device", device.vendor)
            assertNull(device.broadcastName)
            assertTrue(device.evidence.isNotEmpty())
        }

    @Test fun `its new tracker event is corrected too`() =
        runBlocking {
            val events = migrated().watchEvents(0, Long.MAX_VALUE, 10).first()
            assertEquals(EventType.NEW_FIND_MY, events.first { it.id == 1L }.type)
        }

    @Test fun `a named tracker keeps its category`() =
        runBlocking {
            val device = migrated().deviceByKey("ble:CC:CC:CC:CC:CC:01")!!
            assertEquals(Category.TRACKER, device.category)
            assertEquals("Tile", device.broadcastName)
        }

    @Test fun `a tracker with a user label still downgrades but keeps the label`() =
        runBlocking {
            val device = migrated().deviceByKey("ble:CA:00:00:00:00:02")!!
            assertEquals("Spare keys", device.label)
            assertEquals(Category.FIND_MY, device.category)
            assertEquals("Spare keys", device.title)
        }

    @Test fun `hidden network placeholders are not kept as real names`() =
        runBlocking {
            val device = migrated().deviceByKey("wifi:00:1a:00:00:00:01")!!
            assertNull(device.broadcastName)
            assertEquals("Hidden Wi-Fi network", device.title)
        }

    @Test fun `old notifications are recorded as unknown, not as delivered`() =
        runBlocking {
            val events = migrated().watchEvents(0, Long.MAX_VALUE, 10).first()
            val old = events.first { it.id == 2L }
            assertTrue(old.eligible)
            assertEquals(Delivery.UNKNOWN, old.delivery)
            val quiet = events.first { it.id == 1L }
            assertFalse(quiet.eligible)
            assertEquals(Delivery.NOT_ELIGIBLE, quiet.delivery)
        }

    @Test fun `observation counts are rebuilt from sightings`() =
        runBlocking {
            val device = migrated().deviceByKey("ble:AA:AA:AA:AA:AA:01")!!
            assertEquals(2L, device.observations)
            assertEquals(-60, device.minRssi)
        }

    @Test fun `a place a person named is protected, a generated one is not`() =
        runBlocking {
            val places = migrated().places().associateBy { it.name }
            assertTrue(places.getValue("Office").userNamed)
            assertFalse(places.getValue("Place 1").userNamed)
        }

    @Test fun `old devices improve as soon as better evidence arrives`() =
        runBlocking {
            val dao = migrated()
            val db = opened!!
            val obs = observation("ble:CA:00:00:00:00:01", Category.AUDIO, TypeConfidence.MEDIUM).copy(descriptor = "AirPods Pro")
            Repository(db).ingest(listOf(obs), null, null, 2_000_000_000_000)
            val device = dao.deviceByKey("ble:CA:00:00:00:00:01")!!
            assertEquals(Category.AUDIO, device.category)
            assertEquals("AirPods Pro", device.title)
        }

    private companion object {
        val V1_SCHEMA =
            listOf(
                "CREATE TABLE `device` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `key` TEXT NOT NULL, `radio` TEXT NOT NULL, `broadcastName` TEXT, `category` TEXT NOT NULL, `label` TEXT, `starred` INTEGER NOT NULL, `ignored` INTEGER NOT NULL, `firstSeen` INTEGER NOT NULL, `lastSeen` INTEGER NOT NULL, `lastRssi` INTEGER NOT NULL, `lastPlaceId` INTEGER, `lastAlertAt` INTEGER NOT NULL)",
                "CREATE UNIQUE INDEX `index_device_key` ON `device` (`key`)",
                "CREATE TABLE `place` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL)",
                "CREATE TABLE `sighting` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `deviceId` INTEGER NOT NULL, `placeId` INTEGER, `time` INTEGER NOT NULL, `rssi` INTEGER NOT NULL, FOREIGN KEY(`deviceId`) REFERENCES `device`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX `index_sighting_deviceId` ON `sighting` (`deviceId`)",
                "CREATE INDEX `index_sighting_time` ON `sighting` (`time`)",
                "CREATE TABLE `event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `deviceId` INTEGER NOT NULL, `type` TEXT NOT NULL, `time` INTEGER NOT NULL, `fromPlaceId` INTEGER, `toPlaceId` INTEGER, `notified` INTEGER NOT NULL, FOREIGN KEY(`deviceId`) REFERENCES `device`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX `index_event_deviceId` ON `event` (`deviceId`)",
                "CREATE INDEX `index_event_time` ON `event` (`time`)",
                "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '6123cbb8448240060f6a9790ba1ae86e')",
            )

        private fun device(
            id: Int,
            key: String,
            radio: String,
            name: String?,
            category: String,
            label: String? = null,
            starred: Int = 0,
        ): String {
            val values = "$id, '$key', '$radio', ${sqlText(name)}, '$category', ${sqlText(label)}, $starred, 0, 1000, 2000, -60, 1, 0"
            return "INSERT INTO device (id, `key`, radio, broadcastName, category, label, starred, ignored, " +
                "firstSeen, lastSeen, lastRssi, lastPlaceId, lastAlertAt) VALUES ($values)"
        }

        private fun sqlText(text: String?) = text?.let { "'$it'" } ?: "NULL"

        val V1_ROWS =
            listOf(
                "INSERT INTO place (id, name, lat, lng) VALUES (1, 'Place 1', 52.5, 13.4), (2, 'Office', 52.6, 13.5)",
                device(1, "ble:AA:AA:AA:AA:AA:01", "BLE", "WH-1000XM5", "AUDIO", "My headphones", 1),
                device(2, "ble:CA:00:00:00:00:01", "BLE", "Apple device", "TRACKER"),
                device(3, "ble:CC:CC:CC:CC:CC:01", "BLE", "Tile", "TRACKER"),
                device(4, "ble:CA:00:00:00:00:02", "BLE", "Apple device", "TRACKER", "Spare keys"),
                device(5, "wifi:00:1a:00:00:00:01", "WIFI", "Hidden network", "NETWORK"),
                "INSERT INTO sighting (deviceId, placeId, time, rssi) VALUES (1, 1, 1000, -60), (1, 2, 2000, -50), (2, 1, 1500, -42)",
                "INSERT INTO event (id, deviceId, type, time, fromPlaceId, toPlaceId, notified) VALUES (1, 2, 'NEW_TRACKER', 1500, NULL, 1, 0), (2, 1, 'RETURNED', 1800, 1, 2, 1)",
            )
    }
}
