package com.terminallauncher.shell

/**
 * Everything the shell asks of the phone. The shell itself is plain Kotlin, so it can be tested on a
 * desktop JVM against a fake of this interface.
 */
interface Phone {
    fun apps(): List<AppEntry>

    /**
     * false if the app is gone. true means it is on its way: the phone may hold the start back for
     * [LAUNCH_MS] so the launch transition has time to play.
     */
    fun launch(app: AppEntry): Boolean
    fun appInfo(app: AppEntry): Boolean

    /** null until the user grants notification access. */
    fun notifications(): List<Notif>?
    fun clearNotifications(): Int

    /** null until the user grants the contacts permission. Never prompts. */
    fun contacts(): List<Contact>?

    /** true if the call was placed directly, false if the number was handed to the dialer. */
    fun call(number: String): Boolean
    fun sms(number: String, body: String): Boolean

    /** null if the phone has no flashlight. */
    fun torch(): Boolean?
    fun setTorch(on: Boolean): Boolean

    fun volume(): Int
    fun setVolume(percent: Int)

    fun brightness(): Int

    /** false until the user allows this app to modify system settings. */
    fun setBrightness(percent: Int): Boolean

    fun battery(): Battery
    fun wifiOn(): Boolean
    fun openPanel(panel: Panel): Boolean
    fun system(): SystemInfo

    /**
     * The track the phone's media player is on, playing or paused. null when there is none, and
     * always null until the user grants notification access, which is how Android gates this.
     */
    fun nowPlaying(): Track?

    /** false if there is no player to take the key. */
    fun media(key: MediaKey): Boolean

    /** Puts [text] on the clipboard. */
    fun copy(text: String): Boolean

    fun setup(): List<SetupItem>

    /** Opens the settings page or permission dialog for a setup item. false if there is nothing to do. */
    fun fix(key: String): Boolean

    /** True while Android's own lock screen is still up, whether or not the terminal is drawn over it. */
    fun deviceLocked(): Boolean

    /**
     * Takes the user through Android's own unlock: fingerprint, PIN, or nothing at all if the lock
     * screen is only a swipe. Reports true once the phone is unlocked, false if the user backed out.
     */
    fun unlockDevice(done: (Boolean) -> Unit)
}

interface Store {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** Holds the launcher password. Implementations keep only a salted hash. */
interface Vault {
    val isSet: Boolean
    fun matches(password: String): Boolean
    fun set(password: String?)
}
