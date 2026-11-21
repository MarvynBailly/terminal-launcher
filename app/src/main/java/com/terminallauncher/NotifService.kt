package com.terminallauncher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Android only hands notifications to a listener service the user has switched on by hand
 * (`setup notifs`). While it is connected, [AndroidPhone] reads the active notifications through it.
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

    override fun onNotificationPosted(sbn: StatusBarNotification?) = changed()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = changed()

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
