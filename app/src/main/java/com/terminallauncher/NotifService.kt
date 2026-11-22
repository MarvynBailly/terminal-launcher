package com.terminallauncher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Android only hands notifications to a listener service the user has switched on by hand
 * (`setup notifs`). While it is connected, [AndroidPhone] reads the active notifications through it,
 * and each new one is passed to [App] to print in the terminal.
 */
class NotifService : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        changed()
    }

    override fun onListenerDisconnected() {
        instance = null
        changed()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val app = application as? App ?: return
        if (sbn != null) app.posted(sbn) else app.redraw()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val app = application as? App ?: return
        if (sbn != null) app.removed(sbn) else app.redraw()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun changed() {
        (application as? App)?.redraw()
    }

    companion object {
        @Volatile
        var instance: NotifService? = null
            private set
    }
}
