package com.caremate.assistant.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.caremate.assistant.device.NotificationCatcher
import com.caremate.assistant.device.ScreenAgentService

/** One row on the setup checklist. */
data class SetupItem(
    val title: String,
    val why: String,
    val granted: Boolean,
    val runtime: List<String> = emptyList(),
    val settingsIntent: Intent? = null,
)

object Permissions {

    val runtimeGroups: List<Triple<String, String, List<String>>> = buildList {
        add(Triple("Microphone", "To hear your voice", listOf(Manifest.permission.RECORD_AUDIO)))
        add(Triple("Phone calls & call history", "To call people and tell you about missed calls",
            listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE)))
        add(Triple("Contacts", "To find people by name", listOf(Manifest.permission.READ_CONTACTS)))
        add(Triple("SMS", "To send and read text messages, and SOS alerts",
            listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)))
        add(Triple("Location", "For 'where am I', directions and SOS location",
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)))
        add(Triple("Calendar", "To read and add appointments", listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)))
        if (Build.VERSION.SDK_INT >= 33) add(Triple("Notifications", "To show reminders", listOf(Manifest.permission.POST_NOTIFICATIONS)))
    }

    fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun accessibilityOn(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val me = ComponentName(ctx, ScreenAgentService::class.java).flattenToString()
        return ScreenAgentService.instance != null || enabled.split(':').any { it.equals(me, true) }
    }

    fun notificationAccessOn(ctx: Context) =
        NotificationCatcher.connected || NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun isDefaultAssistant(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 29) ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
        else (Settings.Secure.getString(ctx.contentResolver, "assistant") ?: "").contains(ctx.packageName)

    private fun appDetails(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    fun items(ctx: Context): List<SetupItem> = buildList {
        runtimeGroups.forEach { (t, why, perms) -> add(SetupItem(t, why, perms.all { has(ctx, it) }, runtime = perms)) }
        if (Build.VERSION.SDK_INT >= 29) add(SetupItem(
            "Location all the time", "So family can ask 'where are you' by SMS even when the app is closed. Choose 'Allow all the time'.",
            has(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION), runtime = listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)))
        add(SetupItem(
            "Screen control (Accessibility)",
            "Lets CareMate read the screen and tap/type in any app (WhatsApp, YouTube…). Turn on 'CareMate'. " +
                if (Build.VERSION.SDK_INT >= 33) "If it's greyed out: open App info > ⋮ menu > 'Allow restricted settings' first." else "",
            accessibilityOn(ctx), settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
        add(SetupItem("Read notifications", "To read out new WhatsApp messages and alerts",
            notificationAccessOn(ctx), settingsIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
        add(SetupItem("Default assistant", "Long-press the home button (or swipe from a bottom corner) to talk, even on the lock screen. Choose CareMate as 'Digital assistant app'.",
            isDefaultAssistant(ctx), settingsIntent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)))
        add(SetupItem("Display over other apps", "Lets CareMate come back to the front to ask you questions while using other apps",
            Settings.canDrawOverlays(ctx), settingsIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))))
        val pm = ctx.getSystemService(PowerManager::class.java)
        add(SetupItem("Run in background", "So reminders and family check-ins always work",
            pm.isIgnoringBatteryOptimizations(ctx.packageName),
            settingsIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))))
        if (Build.VERSION.SDK_INT >= 31) add(SetupItem("Exact reminder times", "So medicine reminders ring on time",
            ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
            settingsIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))))
        if (Build.VERSION.SDK_INT >= 34) add(SetupItem("Full-screen reminders", "Shows reminders over the lock screen",
            ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
            settingsIntent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))))
        add(SetupItem("Change system settings", "To adjust screen brightness",
            Settings.System.canWrite(ctx), settingsIntent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))))
        add(SetupItem("Do Not Disturb access", "To switch silent / vibrate mode",
            ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted,
            settingsIntent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)))
        add(SetupItem("App info", "Restricted settings, permissions and battery for CareMate", true, settingsIntent = appDetails(ctx)))
    }
}
