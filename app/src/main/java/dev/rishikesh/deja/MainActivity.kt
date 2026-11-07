package dev.rishikesh.deja

import android.Manifest
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rishikesh.deja.core.Health
import dev.rishikesh.deja.core.HealthInputs
import dev.rishikesh.deja.core.Recovery
import dev.rishikesh.deja.core.ScanPolicy
import dev.rishikesh.deja.core.MatchEngine
import dev.rishikesh.deja.data.Repository
import dev.rishikesh.deja.data.SettingsStore
import dev.rishikesh.deja.data.Totals
import dev.rishikesh.deja.scan.LiveState
import dev.rishikesh.deja.scan.Notifier
import dev.rishikesh.deja.scan.Probe
import dev.rishikesh.deja.scan.ScanController
import dev.rishikesh.deja.ui.ActivityScreen
import dev.rishikesh.deja.ui.AlertRoute
import dev.rishikesh.deja.ui.DejaTheme
import dev.rishikesh.deja.ui.DeviceRoute
import dev.rishikesh.deja.ui.DevicesScreen
import dev.rishikesh.deja.ui.HealthScreen
import dev.rishikesh.deja.ui.IconBadge
import dev.rishikesh.deja.ui.LocalPalette
import dev.rishikesh.deja.ui.NearbyScreen
import dev.rishikesh.deja.ui.PlacesScreen
import dev.rishikesh.deja.ui.SettingsScreen
import dev.rishikesh.deja.ui.TextGap
import dev.rishikesh.deja.ui.Type
import dev.rishikesh.deja.ui.rememberNow

private val SCAN_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private const val NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS
private const val MAX_TAB_SCALE = 1.3f

class MainActivity : ComponentActivity() {
    /** A screen a notification asked for, waiting for the navigation stack to pick it up. */
    private val requestedRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android remembers this itself. Setting it again keeps it true after a data restore.
        getSystemService(UiModeManager::class.java)
            .setApplicationNightMode((application as DejaApp).settings.settings.value.theme.nightMode)
        val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        // A recreated activity gets its old intent again, which must not act a second time.
        if (savedInstanceState == null) {
            handle(intent)
            holdSplash()
        }
        val app = application as DejaApp
        Notifier(this).createChannels()
        // Someone upgrading has already been through this, so the welcome would only get in the way.
        val alreadySetUp = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (alreadySetUp && !app.settings.settings.value.onboarded) app.settings.update { it.copy(onboarded = true) }
        setContent {
            DejaTheme {
                DejaRoot(app.repository, app.settings, requestedRoute.value) { requestedRoute.value = null }
            }
        }
    }

    /** The app starts faster than the splash animation runs, and its last beat is the whole logo. */
    private fun holdSplash() {
        val until = SystemClock.uptimeMillis() + SPLASH_MS
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (SystemClock.uptimeMillis() < until) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        if (intent.action == ACTION_RESUME) ScanController.resume(this)
        val event = intent.getLongExtra(EXTRA_EVENT, 0L)
        if (event != 0L) requestedRoute.value = "event:$event"
    }

    /** The activity handles theme changes itself so colours can fade, so the bars must follow by hand. */
    fun refreshBars() {
        val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    }

    /** Android tells a refused permission from a permanently refused one only through this answer. */
    fun rememberDenials(store: SettingsStore) {
        for (permission in SCAN_PERMISSIONS + NOTIFICATIONS) {
            val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            store.setDeniedForever(
                permission,
                !granted && store.wasAsked(permission) && !shouldShowRequestPermissionRationale(permission),
            )
        }
    }

    companion object {
        const val EXTRA_EVENT = "event"
        const val ACTION_RESUME = "dev.rishikesh.deja.RESUME"
        private const val SPLASH_MS = 520L
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    NEARBY("Nearby", Icons.Outlined.Radar),
    ACTIVITY("Activity", Icons.Outlined.History),
    DEVICES("Devices", Icons.Outlined.Devices),
    PLACES("Places", Icons.Outlined.Place),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@Composable
private fun DejaRoot(repository: Repository, store: SettingsStore, requested: String?, onRequestHandled: () -> Unit) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val probe = remember { Probe(context, store) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()
    LaunchedEffect(dark) { activity.refreshBars() }

    fun refresh() {
        activity.rememberDenials(store)
        LiveState.setSystem(probe.read())
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        store.markAsked(NOTIFICATIONS)
        refresh()
    }
    val askScanning = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        SCAN_PERMISSIONS.forEach(store::markAsked)
        refresh()
        ScanController.ensureRunning(context)
        // Asked once, straight after, or a following alert could never reach someone who didn't look.
        if (!store.wasAsked(NOTIFICATIONS)) askNotifications.launch(NOTIFICATIONS)
    }

    // Permissions and radios can change in system Settings while the app is away.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refresh()
        ScanController.ensureRunning(context)
    }

    Box(Modifier.fillMaxSize().background(LocalPalette.current.background).safeDrawingPadding()) {
        if (settings.onboarded) {
            Home(
                repository = repository,
                store = store,
                requested = requested,
                onRequestHandled = onRequestHandled,
                onAskScanning = { askScanning.launch(SCAN_PERMISSIONS) },
                onAskNotifications = { askNotifications.launch(NOTIFICATIONS) },
            )
        } else {
            Welcome(
                onContinue = {
                    store.update { it.copy(onboarded = true) }
                    askScanning.launch(SCAN_PERMISSIONS)
                },
                onSkip = { store.update { it.copy(onboarded = true) } },
            )
        }
    }
}

@Composable
private fun Home(
    repository: Repository,
    store: SettingsStore,
    requested: String?,
    onRequestHandled: () -> Unit,
    onAskScanning: () -> Unit,
    onAskNotifications: () -> Unit,
) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val dao = repository.dao
    val live by LiveState.live.collectAsStateWithLifecycle()
    val system by LiveState.system.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    val journal by store.journal.collectAsStateWithLifecycle()
    val totals by remember { dao.watchTotals() }.collectAsStateWithLifecycle(Totals())
    val warningSince = remember { System.currentTimeMillis() - MatchEngine.DAY }
    val warning by remember { dao.watchActiveWarning(warningSince) }.collectAsStateWithLifecycle(null)
    val now by rememberNow()

    var tabName by rememberSaveable { mutableStateOf(Tab.NEARBY.name) }
    val tab = Tab.valueOf(tabName)
    var stack by rememberSaveable { mutableStateOf(listOf<String>()) }
    val holder = rememberSaveableStateHolder()

    fun push(route: String) {
        stack = stack + route
    }
    fun pop() {
        stack = stack.dropLast(1)
    }
    fun open(deviceId: Long) = push("device:$deviceId")
    fun openEvent(eventId: Long) = push("event:$eventId")

    LaunchedEffect(requested) {
        if (requested != null) {
            push(requested)
            onRequestHandled()
        }
    }

    val report = Health.report(
        HealthInputs(
            system = system,
            bluetoothEnabled = settings.bluetoothEnabled,
            wifiEnabled = settings.wifiEnabled,
            anyAlertEnabled = settings.alerts.let { it.following || it.watched || it.newTracker },
            listening = live.listening,
            paused = !settings.scanningEnabled,
            journal = journal,
            staleAfterMs = ScanPolicy.staleAfter(live.plan),
            now = now,
        ),
    )

    fun recover(recovery: Recovery) {
        fun settingsIntent(action: String) = Intent(action)
        when (recovery) {
            Recovery.GRANT_SCANNING -> onAskScanning()
            Recovery.GRANT_NOTIFICATIONS -> onAskNotifications()
            Recovery.APP_SETTINGS -> context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
            Recovery.ENABLE_BLUETOOTH -> context.startActivity(settingsIntent(Settings.ACTION_BLUETOOTH_SETTINGS))
            Recovery.WIFI_SETTINGS -> context.startActivity(settingsIntent(Settings.ACTION_WIFI_SETTINGS))
            Recovery.LOCATION_SETTINGS -> context.startActivity(settingsIntent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            Recovery.NOTIFICATION_SETTINGS -> context.startActivity(
                settingsIntent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
            Recovery.ALERT_CHANNEL_SETTINGS -> context.startActivity(
                settingsIntent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.CHANNEL_ALERTS),
            )
            Recovery.DEJA_SETTINGS -> {
                stack = emptyList()
                tabName = Tab.SETTINGS.name
            }
            Recovery.RESUME -> ScanController.resume(context)
        }
    }

    val top = stack.lastOrNull()
    if (top != null) {
        BackHandler { pop() }
        holder.SaveableStateProvider(top) {
            val (kind, id) = top.split(":").let { it[0] to it.getOrNull(1)?.toLongOrNull() }
            when (kind) {
                "device" -> DeviceRoute(repository, id ?: 0L, ::pop, ::openEvent)
                "event" -> AlertRoute(repository, id ?: 0L, ::pop, ::open)
                else -> HealthScreen(report, journal, system, live.plan, now, ::pop, ::recover)
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            holder.SaveableStateProvider(tab.name) {
                when (tab) {
                    Tab.NEARBY -> NearbyScreen(
                        repository = repository,
                        live = live,
                        warning = warning,
                        report = report,
                        journal = journal,
                        paused = !settings.scanningEnabled,
                        now = now,
                        onToggle = {
                            if (settings.scanningEnabled) ScanController.pause(context, showResume = false) else ScanController.resume(context)
                        },
                        onRecover = ::recover,
                        onHealth = { push("health") },
                        onOpen = ::open,
                        onOpenEvent = ::openEvent,
                    )
                    Tab.ACTIVITY -> ActivityScreen(repository, ::openEvent)
                    Tab.DEVICES -> DevicesScreen(repository, settings, ::open)
                    Tab.PLACES -> PlacesScreen(repository)
                    Tab.SETTINGS -> SettingsScreen(repository, store, settings, totals) { push("health") }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
        Row(Modifier.fillMaxWidth().background(palette.background).selectableGroup()) {
            Tab.entries.forEach { entry ->
                val color = if (entry == tab) palette.ink else palette.faint
                Column(
                    Modifier
                        .weight(1f)
                        .selectable(selected = entry == tab, role = Role.Tab) { tabName = entry.name }
                        .heightIn(min = 56.dp)
                        .padding(top = 10.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(entry.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(entry.label, style = tabLabel(), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Five labels share one row, so they stop growing at 130%. TalkBack still reads them in full. */
@Composable
private fun tabLabel(): TextStyle {
    val scale = LocalDensity.current.fontScale
    return Type.micro.copy(fontSize = Type.micro.fontSize * (minOf(scale, MAX_TAB_SCALE) / scale))
}

@Composable
private fun Welcome(onContinue: () -> Unit, onSkip: () -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxSize().verticalScrollIfNeeded().padding(24.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text("Déjà", style = Type.label, color = palette.muted)
        Spacer(Modifier.height(10.dp))
        Text("Know when something comes back.", style = Type.display, color = palette.ink, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(12.dp))
        Text(
            "Déjà remembers the devices around you and tells you when one turns up again, or seems to be following you.",
            style = Type.text,
            color = palette.muted,
        )
        Spacer(Modifier.height(28.dp))
        Reason(Icons.Outlined.Bluetooth, "Nearby devices", "To hear Bluetooth around you")
        Reason(Icons.Outlined.Place, "Precise location", "Android requires it for scan results, and it tells your places apart")
        Reason(Icons.Outlined.NotificationsNone, "Notifications", "Optional. You can allow these later, and scanning works without them")
        Spacer(Modifier.height(20.dp))
        Text("Everything stays on this phone.", style = Type.small, color = palette.muted)
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.ink)
                .clickable(onClick = onContinue)
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Continue", style = Type.body, color = palette.background)
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onSkip).padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Not now", style = Type.body, color = palette.muted)
        }
    }
}

@Composable
private fun Modifier.verticalScrollIfNeeded(): Modifier =
    this.verticalScroll(androidx.compose.foundation.rememberScrollState())

@Composable
private fun Reason(icon: ImageVector, title: String, body: String) {
    val palette = LocalPalette.current
    Row(Modifier.padding(vertical = 7.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, palette.ink, palette.surface)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = Type.body, color = palette.ink)
            Spacer(Modifier.height(TextGap))
            Text(body, style = Type.small, color = palette.muted)
        }
    }
}
