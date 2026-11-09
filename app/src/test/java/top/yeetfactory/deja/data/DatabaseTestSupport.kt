package top.yeetfactory.deja.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider

fun inMemoryDatabase(): DejaDatabase =
    Room
        .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), DejaDatabase::class.java)
        .allowMainThreadQueries()
        .build()

fun observation(
    key: String,
    category: Category = Category.UNKNOWN,
    confidence: TypeConfidence = TypeConfidence.NONE,
    identity: IdentityConfidence = IdentityConfidence.STABLE,
    temporary: Boolean = false,
    fingerprint: String = "",
    rssi: Int = -60,
) = Observation(
    key = key,
    radio = Radio.BLE,
    name = null,
    category = category,
    rssi = rssi,
    typeConfidence = confidence,
    identity = identity,
    temporary = temporary,
    fingerprint = fingerprint,
)
