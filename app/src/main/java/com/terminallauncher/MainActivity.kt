package com.terminallauncher

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.terminallauncher.ui.TerminalScreen
import com.terminallauncher.ui.paletteFor

/** The home screen. It only draws the terminal; the session itself lives in [App]. */
class MainActivity : ComponentActivity() {
    private var rev by mutableIntStateOf(0)
    private var wake by mutableIntStateOf(0)
    private var onScreen by mutableStateOf(false)

    private val app get() = application as App

    /** What Android was last told about showing over its lock screen; null before the first time. */
    private var overLockScreen: Boolean? = null
    private val lockSync: () -> Unit = { syncLockScreen() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.onLockChange = lockSync
        syncLockScreen()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Home is the bottom of the stack: Back has nowhere to go.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        setContent {
            val palette = paletteFor(app.shell.theme)
            SideEffect {
                window.navigationBarColor = palette.bg.toArgb()
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = palette.light
            }
            TerminalScreen(app.shell, app.phone, rev, wake, onScreen)
        }
    }

    override fun onStart() {
        super.onStart()
        app.phone.activity = this
        app.onRedraw = { rev++ }
        onScreen = true
        syncLockScreen()
        rev++                     // the session may have locked or printed while we were away
    }

    override fun onDestroy() {
        if (app.onLockChange === lockSync) app.onLockChange = null
        super.onDestroy()
    }

    /**
     * While the terminal is locked it may show on top of Android's lock screen, so its password page is
     * the first thing on screen when the phone wakes. Android's lock is still there underneath: a right
     * password is followed by Android's own unlock (see Shell.awaitPhone). Once the terminal is open it
     * goes back behind Android's lock like any other app, so it can never be used on a locked phone.
     */
    private fun syncLockScreen() {
        val over = app.shell.locked
        if (over == overLockScreen) return
        overLockScreen = over
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(over)
        } else if (over) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }

    override fun onResume() {
        super.onResume()
        hideStatusBar()
        wake++
    }

    override fun onStop() {
        onScreen = false
        app.onRedraw = null
        if (app.phone.activity === this) app.phone.activity = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: bring the keyboard back.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) wake++
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideStatusBar()
            wake++
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val name = permissions.firstOrNull()?.substringAfterLast('.')?.lowercase()?.replace('_', ' ') ?: return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        app.shell.info(if (granted) "$name: allowed" else "$name: not allowed")
    }

    /** The terminal draws its own status line. Swiping down from the top still reveals the system one. */
    private fun hideStatusBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }
}
