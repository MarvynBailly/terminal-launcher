package com.terminallauncher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.terminallauncher.shell.Shell

/**
 * Holds the terminal session for the life of the process, so it survives the activity being
 * recreated and can lock itself when the screen turns off while another app is in front.
 */
class App : Application() {
    lateinit var phone: AndroidPhone
        private set
    lateinit var shell: Shell
        private set

    /** Set by the activity while it is on screen. */
    var onRedraw: (() -> Unit)? = null

    /**
     * Set by the activity for as long as it exists, on screen or not. It has to hear about the terminal
     * locking while the screen is off, to be in place over Android's lock screen before the phone wakes.
     */
    var onLockChange: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("terminal", MODE_PRIVATE)
        phone = AndroidPhone(this)
        phone.onChange = ::redraw
        shell = Shell(phone, PrefsStore(prefs), PrefsVault(prefs))
        shell.onChange = ::redraw
        shell.start()

        val screenOff = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onScreenOff()
        }
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun redraw() {
        if (Looper.myLooper() == Looper.getMainLooper()) tellActivity() else main.post(::tellActivity)
    }

    private fun tellActivity() {
        onLockChange?.invoke()
        onRedraw?.invoke()
    }

    private fun onScreenOff() {
        if (!shell.lock()) return      // no password set: nothing to lock
        // Come to the front so the lock screen is what the phone wakes to, even if another app was open.
        // Android lets the default home app start itself from the background; if this is not the
        // home app the call is quietly dropped and the lock shows the next time the launcher opens.
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // nothing to do: the session is locked either way
        }
    }
}
