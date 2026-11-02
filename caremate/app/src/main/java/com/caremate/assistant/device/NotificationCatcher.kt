package com.caremate.assistant.device

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

data class CaughtNotification(val app: String, val pkg: String, val title: String, val text: String, val time: Long)

/** Keeps the latest notifications so the assistant can answer "any new messages?". */
class NotificationCatcher : NotificationListenerService() {

    override fun onListenerConnected() { connected = true; activeNotifications?.forEach { add(it) } }
    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification) = add(sbn)

    private fun add(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val ex = sbn.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        synchronized(recent) {
            recent.removeAll { it.pkg == sbn.packageName && it.title == title && it.text == text }
            recent.add(0, CaughtNotification(app, sbn.packageName, title, text, sbn.postTime))
            while (recent.size > 60) recent.removeAt(recent.size - 1)
        }
    }

    companion object {
        @Volatile var connected = false
        val recent = mutableListOf<CaughtNotification>()
        fun snapshot(limit: Int): List<CaughtNotification> = synchronized(recent) { recent.take(limit) }
    }
}
