package com.caremate.assistant.reminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.caremate.assistant.CareMateApp
import com.caremate.assistant.R
import com.caremate.assistant.memory.Reminder
import com.caremate.assistant.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Spoken + notification reminders (medicines, appointments) that survive reboots. */
object Reminders {

    fun add(ctx: Context, text: String, at: Long, repeat: String): Long {
        val id = CareMateApp.instance.store.addReminder(text, at, repeat)
        schedule(ctx, id, at)
        return id
    }

    fun cancel(ctx: Context, id: Long): Boolean {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id))
        return CareMateApp.instance.store.deactivateReminder(id)
    }

    fun rescheduleAll(ctx: Context) {
        val store = CareMateApp.instance.store
        for (r in store.activeReminders()) {
            val next = if (r.triggerAt < System.currentTimeMillis()) nextOccurrence(r) else r.triggerAt
            if (next == null) { store.deactivateReminder(r.id); continue }
            if (next != r.triggerAt) store.setReminderTime(r.id, next)
            schedule(ctx, r.id, next)
        }
    }

    fun nextOccurrence(r: Reminder): Long? {
        val step = when (r.repeat) { "daily" -> 86_400_000L; "weekly" -> 7 * 86_400_000L; else -> return null }
        var t = r.triggerAt
        val now = System.currentTimeMillis()
        while (t <= now) t += step
        return t
    }

    private fun pending(ctx: Context, id: Long) = PendingIntent.getBroadcast(
        ctx, id.toInt(), Intent(ctx, ReminderReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun schedule(ctx: Context, id: Long, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, id)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        val store = CareMateApp.instance.store
        val r = store.reminder(id) ?: return
        if (!r.active) return
        val next = Reminders.nextOccurrence(r)
        if (next != null) { store.setReminderTime(id, next); Reminders.rescheduleAll(ctx) } else store.deactivateReminder(id)

        val open = PendingIntent.getActivity(
            ctx, id.toInt(), Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_REMINDER, r.text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CareMateApp.CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Reminder")
            .setContentText(r.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(10_000 + id.toInt(), n)
        SpeakService.speak(ctx, "Reminder. ${r.text}", repeat = 2)
        CareMateApp.instance.store.log("reminder", r.text)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.rescheduleAll(ctx)
        com.caremate.assistant.wake.WakeWords.sync(ctx)
    }
}

/** Short foreground service that reads a message aloud even if the app is closed. */
class SpeakService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text").orEmpty()
        val repeat = intent?.getIntExtra("repeat", 1) ?: 1
        val notif: Notification = NotificationCompat.Builder(this, CareMateApp.CH_WORKING)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("CareMate is speaking").setContentText(text).build()
        ServiceCompat.startForeground(
            this, 7, notif,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        )
        scope.launch {
            repeat(repeat) { i ->
                CareMateApp.instance.speaker.speak(text, alarmStream = true)
                if (i < repeat - 1) delay(1500)
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) { stopSelf() }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        fun speak(ctx: Context, text: String, repeat: Int = 1) {
            val i = Intent(ctx, SpeakService::class.java).putExtra("text", text).putExtra("repeat", repeat)
            runCatching { ctx.startForegroundService(i) }
        }
    }
}
