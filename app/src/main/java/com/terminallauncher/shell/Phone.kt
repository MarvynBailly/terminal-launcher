package com.terminallauncher.shell

/**
 * Everything the shell asks of the phone. The shell itself is plain Kotlin, so it can be tested on a
 * desktop JVM against a fake of this interface.
 */
interface Phone {
    fun apps(): List<AppEntry>
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

    fun setup(): List<SetupItem>

    /** Opens the settings page or permission dialog for a setup item. false if there is nothing to do. */
    fun fix(key: String): Boolean
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
