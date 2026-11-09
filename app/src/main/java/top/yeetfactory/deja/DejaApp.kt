package top.yeetfactory.deja

import android.app.Application
import top.yeetfactory.deja.data.DejaDatabase
import top.yeetfactory.deja.data.Repository
import top.yeetfactory.deja.data.SettingsStore

class DejaApp : Application() {
    val repository: Repository by lazy { Repository(DejaDatabase.create(this)) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
}
