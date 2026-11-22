package com.terminallauncher

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.usage.StorageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.os.UserHandle
import android.os.storage.StorageManager
import android.provider.ContactsContract
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import com.terminallauncher.shell.AppEntry
import com.terminallauncher.shell.Battery
import com.terminallauncher.shell.Contact
import com.terminallauncher.shell.LAUNCH_MS
import com.terminallauncher.shell.MediaKey
import com.terminallauncher.shell.Notif
import com.terminallauncher.shell.Panel
import com.terminallauncher.shell.Phone
import com.terminallauncher.shell.SetupItem
import com.terminallauncher.shell.SystemInfo
import com.terminallauncher.shell.Track
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** The real phone behind the shell's [Phone] interface. */
class AndroidPhone(private val ctx: Context) : Phone {
    /** The activity on screen, if any. Permission dialogs need one. */
    var activity: Activity? = null

    /** Called when something the status line shows has changed. */
    var onChange: () -> Unit = {}

    /** Whether animations are on. While they are, a launch waits for its transition to play. */
    var animate: () -> Boolean = { false }

    /** Called with a message when something already reported as started fails after all. */
    var onError: (String) -> Unit = {}

    private class Launchable(val entry: AppEntry, val component: ComponentName, val user: UserHandle)

    private val launcher = ctx.getSystemService(LauncherApps::class.java)
    private val pm = ctx.packageManager
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val camera = ctx.getSystemService(CameraManager::class.java)
    private val keyguard = ctx.getSystemService(KeyguardManager::class.java)
    private val sessions = ctx.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(ctx, NotifService::class.java)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var appCache: List<Launchable>? = null
    private var contactCache: List<Contact>? = null
    private var contactsReadAt = 0L
    private var torchOn = false
    private val torchId: String? by lazy {
        try {
            camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        } catch (e: Exception) {
            null
        }
    }

    init {
        val forget = { appCache = null }
        launcher.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = forget()
            override fun onPackageAdded(packageName: String, user: UserHandle) = forget()
            override fun onPackageChanged(packageName: String, user: UserHandle) = forget()
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = forget()
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = forget()
        })
        try {
            camera.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchId && torchOn != enabled) {
                        torchOn = enabled
                        onChange()
                    }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            // no camera service: torch() reports that there is no flashlight
        }
        thread(name = "app-list") { loadApps() }     // warm the list so the first `ls` is instant
    }

    /* ---------------- apps ---------------- */

    private fun clean(label: String): String = label.lowercase(Locale.getDefault()).replace(Regex("\\s+"), " ").trim()

    @Synchronized
    private fun loadApps(): List<Launchable> {
        appCache?.let { return it }
        val all = try {
            launcher.profiles.flatMap { user -> launcher.getActivityList(null, user).map { it to user } }
        } catch (e: Exception) {
            emptyList()
        }
        val found = all.filter { it.first.componentName.packageName != ctx.packageName }
        val labelled = found.map { (info, user) ->
            Triple(clean(info.label?.toString().orEmpty()).ifEmpty { info.componentName.packageName }, info, user)
        }
        // two apps can share a label: tell them apart by the last part of the package name
        val counts = labelled.groupingBy { it.first }.eachCount()
        val used = HashSet<String>()
        val list = labelled.map { (label, info, user) ->
            var name = label
            if ((counts[label] ?: 0) > 1) name = "$label (${info.componentName.packageName.substringAfterLast('.')})"
            var n = 2
            val base = name
            while (!used.add(name)) name = "$base ${n++}"
            val id = info.componentName.flattenToShortString() + "#" + user.hashCode()
            Launchable(AppEntry(name, category(info.applicationInfo), id), info.componentName, user)
        }.sortedBy { it.entry.name }
        appCache = list
        return list
    }

    private fun category(info: ApplicationInfo): String = when (info.category) {
        ApplicationInfo.CATEGORY_GAME -> "game"
        ApplicationInfo.CATEGORY_AUDIO -> "audio"
        ApplicationInfo.CATEGORY_VIDEO -> "video"
        ApplicationInfo.CATEGORY_IMAGE -> "image"
        ApplicationInfo.CATEGORY_SOCIAL -> "social"
        ApplicationInfo.CATEGORY_NEWS -> "news"
        ApplicationInfo.CATEGORY_MAPS -> "maps"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "work"
        else -> "-"
    }

    override fun apps(): List<AppEntry> = loadApps().map { it.entry }

    private fun find(app: AppEntry): Launchable? = loadApps().firstOrNull { it.entry.id == app.id }

    override fun launch(app: AppEntry): Boolean {
        val target = find(app) ?: return false
        if (!animate()) {
            return try {
                launcher.startMainActivity(target.component, target.user, null, null)
                true
            } catch (e: Exception) {
                false
            }
        }
        // The terminal grows the launch line until it fills the screen. The app then fades in over it.
        val fadeIn = ActivityOptions.makeCustomAnimation(ctx, android.R.anim.fade_in, android.R.anim.fade_out).toBundle()
        main.postDelayed({
            try {
                launcher.startMainActivity(target.component, target.user, null, fadeIn)
            } catch (e: Exception) {
                onError("open: could not start ${app.name}")
            }
        }, LAUNCH_MS)
        return true
    }

    override fun appInfo(app: AppEntry): Boolean {
        val target = find(app) ?: return false
        return try {
            launcher.startAppDetailsActivity(target.component, target.user, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    /* ---------------- notifications ---------------- */

    private fun notifAccess(): Boolean = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)

    private fun appName(packageName: String): String =
        appCache?.firstOrNull { it.component.packageName == packageName }?.entry?.name
            ?: try {
                clean(pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString())
            } catch (e: Exception) {
                packageName
            }

    override fun notifications(): List<Notif>? {
        if (!notifAccess()) return null
        val service = NotifService.instance ?: return emptyList()     // access granted, not connected yet
        val active = try {
            service.activeNotifications ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        val now = System.currentTimeMillis()
        return active
            .filter { it.isClearable && (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
            .sortedByDescending { it.postTime }
            .map { n ->
                val extras = n.notification.extras
                Notif(
                    app = appName(n.packageName),
                    title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                    ageMinutes = ((now - n.postTime) / 60_000).coerceAtLeast(0),
                )
            }
    }

    /** The text last shown for each notification, so one that is posted again unchanged stays quiet. */
    private val heard = HashMap<String, String>()

    /**
     * What to print for a notification that was just posted, or null if it is not news: a progress
     * bar, a media player, something that cannot be dismissed, or a repeat of what was already shown.
     */
    fun arrival(sbn: StatusBarNotification): Notif? {
        val n = sbn.notification ?: return null
        if (sbn.packageName == ctx.packageName || !sbn.isClearable) return null
        val quiet = Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE
        if (n.flags and quiet != 0) return null
        if (n.category in setOf(Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE)) return null
        val extras = n.extras
        if (extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 || extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        if (heard.size > 300) heard.clear()
        val shown = title + "\n" + text
        if (heard.put(sbn.key, shown) == shown) return null
        return Notif(appName(sbn.packageName), title, text, 0)
    }

    fun dismissed(sbn: StatusBarNotification) {
        heard.remove(sbn.key)
    }

    override fun clearNotifications(): Int {
        val count = notifications()?.size ?: 0
        try {
            NotifService.instance?.cancelAllNotifications()
        } catch (e: Exception) {
            return 0
        }
        return count
    }

    /* ---------------- contacts, calls, texts ---------------- */

    private fun granted(permission: String) = ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun contacts(): List<Contact>? {
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val now = System.currentTimeMillis()
        contactCache?.let { if (now - contactsReadAt < 60_000) return it }
        val seen = HashSet<String>()
        val list = ArrayList<Contact>()
        try {
            val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, columns, null, null, columns[0])?.use { c ->
                while (c.moveToNext()) {
                    val name = clean(c.getString(0) ?: continue)
                    val number = (c.getString(1) ?: continue).trim()
                    // the same number is often stored twice in different formats
                    if (name.isNotEmpty() && seen.add(name + "|" + number.filter { it.isDigit() })) list += Contact(name, number)
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        contactCache = list
        contactsReadAt = now
        return list
    }

    private fun start(intent: Intent): Boolean = try {
        (activity ?: ctx).startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    override fun call(number: String): Boolean {
        val uri = Uri.fromParts("tel", number, null)
        if (granted(Manifest.permission.CALL_PHONE) && start(Intent(Intent.ACTION_CALL, uri))) return true
        start(Intent(Intent.ACTION_DIAL, uri))
        return false
    }

    override fun sms(number: String, body: String): Boolean =
        start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", body))

    /* ---------------- torch, volume, brightness ---------------- */

    override fun torch(): Boolean? = if (torchId == null) null else torchOn

    override fun setTorch(on: Boolean): Boolean {
        val id = torchId ?: return false
        return try {
            camera.setTorchMode(id, on)
            torchOn = on
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun volume(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
    }

    override fun setVolume(percent: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent * max / 100f).roundToInt(), 0)
        } catch (e: SecurityException) {
            // do-not-disturb can forbid it; `vol` then prints the level that is still in effect
        }
    }

    override fun brightness(): Int {
        val raw = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        return (raw * 100f / 255).roundToInt().coerceIn(0, 100)
    }

    override fun setBrightness(percent: Int): Boolean {
        if (!Settings.System.canWrite(ctx)) return false
        return try {
            val resolver = ctx.contentResolver
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (percent * 255f / 100).roundToInt().coerceIn(1, 255))
            true
        } catch (e: Exception) {
            false
        }
    }

    /* ---------------- status ---------------- */

    override fun battery(): Battery {
        val manager = ctx.getSystemService(BatteryManager::class.java)
        return Battery(manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100), manager.isCharging)
    }

    override fun wifiOn(): Boolean = try {
        ctx.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled
    } catch (e: Exception) {
        false
    }

    override fun system(): SystemInfo {
        val size = try {
            val windows = ctx.getSystemService(WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windows.maximumWindowMetrics.bounds.let { minOf(it.width(), it.height()) to maxOf(it.width(), it.height()) }
            } else {
                val point = android.graphics.Point()
                @Suppress("DEPRECATION")
                windows.defaultDisplay.getRealSize(point)
                minOf(point.x, point.y) to maxOf(point.x, point.y)
            }
        } catch (e: Exception) {
            ctx.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) to maxOf(it.widthPixels, it.heightPixels) }
        }
        // The storage service reports the size on the box (128 GB); the file system only what is left of it.
        val (total, free) = try {
            val stats = ctx.getSystemService(StorageStatsManager::class.java)
            stats.getTotalBytes(StorageManager.UUID_DEFAULT) to stats.getFreeBytes(StorageManager.UUID_DEFAULT)
        } catch (e: Exception) {
            StatFs(Environment.getDataDirectory().path).let { it.totalBytes to it.availableBytes }
        }
        val gb = 1_000_000_000.0
        return SystemInfo(
            os = "android ${Build.VERSION.RELEASE}",
            device = clean(Build.MODEL.orEmpty()).ifEmpty { "phone" },
            display = "${size.first}x${size.second}",
            uptimeMinutes = SystemClock.elapsedRealtime() / 60_000,
            diskUsedGb = ((total - free) / gb).roundToInt().toLong(),
            diskTotalGb = (total / gb).roundToInt().toLong(),
        )
    }

    /** false when the phone is set to show no animations at all. */
    fun systemAnimations(): Boolean =
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    /* ---------------- the media player ---------------- */

    /** The player that is playing, or failing that the first one with a track loaded. */
    private fun player(): MediaController? {
        if (!notifAccess()) return null
        val all = try {
            sessions.getActiveSessions(listener)
        } catch (e: Exception) {
            return null
        }
        return all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null }
            ?: all.firstOrNull { it.metadata != null }
    }

    override fun nowPlaying(): Track? {
        val player = player() ?: return null
        val meta = player.metadata ?: return null
        val title = (meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val artist = (meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
            .orEmpty().trim()
        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
        val state = player.playbackState
        val playing = state?.state == PlaybackState.STATE_PLAYING
        var position = state?.position ?: 0
        // the player reports where it was at some moment; it has moved on since
        if (playing && state != null) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        position = position.coerceAtLeast(0)
        if (duration > 0) position = position.coerceAtMost(duration)
        return Track(title, artist, playing, position, duration)
    }

    override fun media(key: MediaKey): Boolean {
        val controls = player()?.transportControls ?: return false
        when (key) {
            MediaKey.PREVIOUS -> controls.skipToPrevious()
            MediaKey.PLAY -> controls.play()
            MediaKey.PAUSE -> controls.pause()
            MediaKey.NEXT -> controls.skipToNext()
        }
        return true
    }

    override fun copy(text: String): Boolean = try {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", text))
        true
    } catch (e: Exception) {
        false
    }

    override fun openPanel(panel: Panel): Boolean = when (panel) {
        Panel.WIFI ->
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && start(Intent(Settings.Panel.ACTION_WIFI))) ||
                start(Intent(Settings.ACTION_WIFI_SETTINGS))
        Panel.BLUETOOTH -> start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    /* ---------------- setup ---------------- */

    private fun isHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == ctx.packageName
    }

    override fun setup(): List<SetupItem> = listOf(
        SetupItem("home", "default home app", isHome()),
        SetupItem("notifs", "read notifications", notifAccess()),
        SetupItem("bright", "change brightness", Settings.System.canWrite(ctx)),
        SetupItem("contacts", "call and sms by name", granted(Manifest.permission.READ_CONTACTS)),
        SetupItem("calls", "call without the dialer", granted(Manifest.permission.CALL_PHONE)),
    )

    override fun fix(key: String): Boolean {
        val self = Uri.fromParts("package", ctx.packageName, null)
        return when (key) {
            "home" -> start(Intent(Settings.ACTION_HOME_SETTINGS))
            "notifs" -> start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            "bright" -> start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, self))
            "contacts" -> ask(Manifest.permission.READ_CONTACTS, self)
            "calls" -> ask(Manifest.permission.CALL_PHONE, self)
            else -> false
        }
    }

    /** Shows the permission dialog. If Android will not show it again, opens this app's settings page instead. */
    private fun ask(permission: String, self: Uri): Boolean {
        if (granted(permission)) return false
        val host = activity ?: return start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, self))
        host.requestPermissions(arrayOf(permission), 0)
        return true
    }

    /* ---------------- Android's own lock screen ---------------- */

    override fun deviceLocked(): Boolean = keyguard.isKeyguardLocked

    override fun unlockDevice(done: (Boolean) -> Unit) {
        val host = activity
        if (host == null) {
            done(!deviceLocked())
            return
        }
        keyguard.requestDismissKeyguard(host, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = done(true)
            override fun onDismissCancelled() = done(false)

            // Android reports an error when there was nothing to dismiss, which is also a way of being unlocked.
            override fun onDismissError() = done(!deviceLocked())
        })
    }
}
