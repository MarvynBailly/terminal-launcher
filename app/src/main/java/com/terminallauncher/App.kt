package com.terminallauncher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.StatusBarNotification
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
        shell = Shell(phone, PrefsStore(prefs), PrefsVault(prefs), AndroidSsh(this))
        shell.onChange = ::redraw
        phone.animate = ::animating
        phone.onError = { message -> onMain { shell.info(message, error = true) } }
        shell.start()

        val screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) onScreenOff() else shell.wake()
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        ContextCompat.registerReceiver(this, screen, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Animations play unless `fx off` was typed or the phone itself is set to show none. */
    fun animating(): Boolean = shell.motion && phone.systemAnimations()

    /**
     * The activity came to the front. If the screen is on, the terminal should be awake too, even if
     * the screen-on broadcast has not arrived yet.
     */
    fun wakeIfScreenOn() {
        if (getSystemService(PowerManager::class.java).isInteractive) shell.wake()
    }

    /** A notification was posted. It is printed in the terminal if the terminal is what is on screen. */
    fun posted(sbn: StatusBarNotification) = onMain {
        val news = phone.arrival(sbn)
        if (news != null && onRedraw != null) shell.notify(news)
        tellActivity()
    }

    fun removed(sbn: StatusBarNotification) = onMain {
        phone.dismissed(sbn)
        tellActivity()
    }

    fun redraw() = onMain(::tellActivity)

    private fun onMain(work: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) work() else main.post(work)
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
