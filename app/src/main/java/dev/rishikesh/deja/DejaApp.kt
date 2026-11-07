package dev.rishikesh.deja

import android.app.Application
import dev.rishikesh.deja.data.DejaDatabase
import dev.rishikesh.deja.data.Repository
import dev.rishikesh.deja.data.SettingsStore

class DejaApp : Application() {
    val repository: Repository by lazy { Repository(DejaDatabase.create(this)) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
}
