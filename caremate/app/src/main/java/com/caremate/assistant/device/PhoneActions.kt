package com.caremate.assistant.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class PhoneContact(val name: String, val number: String, val label: String)

/** Everything the assistant can do directly through Android APIs (no screen tapping needed). */
class PhoneActions(private val ctx: Context) {

    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    private fun need(p: String, what: String) = "I don't have permission to $what yet. Ask the user to open CareMate setup and allow it."

    private fun launch(i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }

    // ---------------------------------------------------------------- contacts

    fun findContacts(query: String): List<PhoneContact> {
        if (!has(Manifest.permission.READ_CONTACTS)) return emptyList()
        val q = query.trim()
        val out = LinkedHashMap<String, PhoneContact>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(ctx.resources, c.getInt(2), c.getString(3)).toString()
                out.putIfAbsent(digits(num).takeLast(10), PhoneContact(name, num, label))
            }
        }
        // Exact name matches first.
        return out.values.sortedBy { if (it.name.equals(q, true)) 0 else if (it.name.startsWith(q, true)) 1 else 2 }
    }

    private fun digits(s: String) = s.filter { it.isDigit() || it == '+' }

    /** Resolves a name or number. Returns (number, displayName) or an error/ambiguity message. */
    fun resolve(recipient: String): Pair<PhoneContact?, String?> {
        val d = digits(recipient)
        if (d.count { it.isDigit() } >= 5) return PhoneContact(recipient, d, "") to null
        val matches = findContacts(recipient)
        return when {
            !has(Manifest.permission.READ_CONTACTS) -> null to need(Manifest.permission.READ_CONTACTS, "read contacts")
            matches.isEmpty() -> null to "No contact named \"$recipient\" found. Check memory for a nickname (e.g. 'son'), or ask for the name as saved."
            matches.map { it.name }.distinct().size > 1 && matches.none { it.name.equals(recipient, true) } ->
                null to "Several contacts match: " + matches.take(6).joinToString("; ") { "${it.name} (${it.label}) ${it.number}" } + ". Ask which one."
            else -> matches.first() to null
        }
    }

    // ---------------------------------------------------------------- calls & messages

    @SuppressLint("MissingPermission")
    fun call(number: String): String {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        return if (has(Manifest.permission.CALL_PHONE) && launch(Intent(Intent.ACTION_CALL, uri))) "Calling $number now."
        else if (launch(Intent(Intent.ACTION_DIAL, uri))) "Opened the dialer with $number; the user needs to press the green call button."
        else "Could not start a call."
    }

    fun sendSms(number: String, message: String): String {
        if (!has(Manifest.permission.SEND_SMS)) {
            launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", message))
            return "Opened the messaging app with the text ready; the user must press send."
        }
        return try {
            @Suppress("DEPRECATION")
            val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            val parts = sms.divideMessage(message)
            if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
            else sms.sendTextMessage(number, null, message, null, null)
            "SMS sent to $number."
        } catch (e: Exception) { "SMS failed: ${e.message}" }
    }

    /** Opens a WhatsApp chat with the message typed in, then presses Send via accessibility. */
    suspend fun sendWhatsApp(number: String, message: String, autoSend: Boolean): String {
        val clean = digits(number).removePrefix("+").let { if (it.length == 10) defaultCountryCode() + it else it }
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$clean&text=" + Uri.encode(message))
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { isInstalled(it) }
            ?: return "WhatsApp is not installed."
        if (!launch(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))) return "Could not open WhatsApp."
        if (!autoSend) return "WhatsApp chat opened with the message typed in."
        val svc = ScreenAgentService.instance ?: return "WhatsApp opened with the message typed; the user needs to tap Send (enable CareMate in Accessibility to send automatically)."
        return if (svc.clickWhatsAppSend()) "WhatsApp message sent." else "WhatsApp opened but I could not press Send. Use read_screen to check."
    }

    private fun defaultCountryCode(): String = when (Locale.getDefault().country) {
        "IN" -> "91"; "US", "CA" -> "1"; "GB" -> "44"; "AU" -> "61"; "AE" -> "971"; "PK" -> "92"; "BD" -> "880"
        "NP" -> "977"; "LK" -> "94"; "SG" -> "65"; "MY" -> "60"; "NZ" -> "64"; "ZA" -> "27"; "DE" -> "49"; "FR" -> "33"
        else -> ""
    }

    fun recentCalls(limit: Int): String {
        if (!has(Manifest.permission.READ_CALL_LOG)) return need(Manifest.permission.READ_CALL_LOG, "read call history")
        val fmt = SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault())
        val sb = StringBuilder()
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, "${CallLog.Calls.DATE} DESC"
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n++ < limit) {
                val type = when (c.getInt(2)) {
                    CallLog.Calls.INCOMING_TYPE -> "incoming"; CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.MISSED_TYPE -> "MISSED"; CallLog.Calls.REJECTED_TYPE -> "rejected"; else -> "other"
                }
                sb.append("${c.getString(0) ?: "Unknown"} (${c.getString(1)}) $type ${fmt.format(Date(c.getLong(3)))}, ${c.getLong(4)}s\n")
            }
        }
        return sb.toString().ifBlank { "No calls in history." }
    }

    fun recentSms(limit: Int): String {
        if (!has(Manifest.permission.READ_SMS)) return need(Manifest.permission.READ_SMS, "read SMS")
        val fmt = SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault())
        val sb = StringBuilder()
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n++ < limit) sb.append("From ${c.getString(0)} at ${fmt.format(Date(c.getLong(2)))}: ${c.getString(1)}\n")
        }
        return sb.toString().ifBlank { "No SMS messages." }
    }

    // ---------------------------------------------------------------- apps

    fun launchableApps(): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinct()
    }

    fun openApp(name: String): String {
        val apps = launchableApps()
        val n = name.trim().lowercase()
        val hit = apps.firstOrNull { it.first.lowercase() == n || it.second == name.trim() }
            ?: apps.firstOrNull { it.first.lowercase().startsWith(n) }
            ?: apps.firstOrNull { it.first.lowercase().contains(n) }
            ?: apps.firstOrNull { n.contains(it.first.lowercase()) && it.first.length > 3 }
            ?: return "No app called \"$name\". Installed apps include: " + apps.map { it.first }.sorted().take(60).joinToString(", ")
        val intent = ctx.packageManager.getLaunchIntentForPackage(hit.second) ?: return "Could not open ${hit.first}."
        return if (launch(intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))) "Opened ${hit.first}." else "Could not open ${hit.first}."
    }

    fun isInstalled(pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun openUrl(url: String): String = if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "Opened $url." else "Could not open the link."

    // ---------------------------------------------------------------- clock

    fun setAlarm(hour: Int, minute: Int, label: String?): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return if (launch(i)) "Alarm set for %02d:%02d.".format(hour, minute) else "No clock app available."
    }

    fun setTimer(seconds: Int, label: String?): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return if (launch(i)) "Timer started for ${seconds / 60} min ${seconds % 60} s." else "No clock app available."
    }

    // ---------------------------------------------------------------- device controls

    fun setVolume(stream: String, percent: Int): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = when (stream.lowercase()) {
            "ring", "ringer", "ringtone" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "call", "voice_call" -> AudioManager.STREAM_VOICE_CALL
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        return try {
            val max = am.getStreamMaxVolume(s)
            am.setStreamVolume(s, (max * percent.coerceIn(0, 100) / 100f).toInt(), AudioManager.FLAG_SHOW_UI)
            "Set $stream volume to $percent%."
        } catch (e: SecurityException) { "Can't change that volume while Do Not Disturb is on." }
    }

    fun setRingerMode(mode: String): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        return try {
            am.ringerMode = when (mode.lowercase()) {
                "silent" -> AudioManager.RINGER_MODE_SILENT; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            "Phone set to $mode."
        } catch (e: SecurityException) { "Not allowed to change Do Not Disturb. Open Settings > Notifications > Do Not Disturb access for CareMate." }
    }

    fun flashlight(on: Boolean): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "This phone has no flashlight."
        return try { cm.setTorchMode(id, on); "Flashlight ${if (on) "on" else "off"}." } catch (e: Exception) { "Flashlight failed: ${e.message}" }
    }

    fun setBrightness(percent: Int): String {
        if (!Settings.System.canWrite(ctx)) {
            launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            return "I need the 'Modify system settings' permission. I opened that screen; the user should switch it on."
        }
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (percent.coerceIn(1, 100) * 255 / 100))
        return "Brightness set to $percent%."
    }

    fun openSettings(page: String): String {
        val action = when (page.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "mobile_data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "notifications" -> if (Build.VERSION.SDK_INT >= 26) Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_SETTINGS
            "date_time" -> Settings.ACTION_DATE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "airplane_mode" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "default_apps" -> Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
            "font_size" -> Settings.ACTION_DISPLAY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val i = Intent(action)
        if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        return if (launch(i)) "Opened $page settings." else "Could not open that settings page."
    }

    // ---------------------------------------------------------------- maps & media

    fun navigate(destination: String, mode: String?): String {
        val m = when (mode?.lowercase()) { "walk", "walking" -> "w"; "bike", "bicycle" -> "b"; "transit", "bus", "train" -> "r"; else -> "d" }
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$m"))
        return if (launch(nav) || launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}"))))
            "Started directions to $destination." else "No maps app found."
    }

    fun playMedia(query: String, app: String?): String {
        val a = app?.lowercase().orEmpty()
        if (a.contains("youtube") || a.isEmpty() && isInstalled("com.google.android.youtube") && a != "music") {
            val yt = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra(SearchManager.QUERY, query)
            if (launch(yt)) return "Searching YouTube for $query. Use read_screen and tap the first result to play it."
        }
        if (a.contains("spotify") && launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query)))))
            return "Opened Spotify search for $query."
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        return if (launch(i)) "Playing $query." else "No music app could play that."
    }

    fun openCamera(selfie: Boolean): String {
        val i = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (selfie) i.putExtra("android.intent.extras.CAMERA_FACING", 1).putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        return if (launch(i)) "Camera opened." else "No camera app."
    }

    // ---------------------------------------------------------------- calendar

    fun calendarEvents(days: Int): String {
        if (!has(Manifest.permission.READ_CALENDAR)) return need(Manifest.permission.READ_CALENDAR, "read the calendar")
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString()).appendPath((now + days * 86_400_000L).toString()).build()
        val fmt = SimpleDateFormat("EEE d MMM h:mm a", Locale.getDefault())
        val sb = StringBuilder()
        ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY),
            null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val whenStr = if (c.getInt(3) == 1) SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(c.getLong(1))) + " (all day)" else fmt.format(Date(c.getLong(1)))
                sb.append("$whenStr: ${c.getString(0)}${c.getString(2)?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""}\n")
            }
        }
        return sb.toString().ifBlank { "Nothing on the calendar in the next $days days." }
    }

    fun addCalendarEvent(title: String, startMillis: Long, minutes: Int, location: String?): String {
        if (!has(Manifest.permission.WRITE_CALENDAR)) return need(Manifest.permission.WRITE_CALENDAR, "add calendar events")
        val calId = ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE}=1",
            null, "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return "No writable calendar on this phone."
        val cv = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId); put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis); put(CalendarContract.Events.DTEND, startMillis + minutes * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, cv) ?: return "Could not add the event."
        return "Added \"$title\" to the calendar."
    }

    // ---------------------------------------------------------------- status

    fun status(): String {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "no internet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "connected"
        }
        val am = ctx.getSystemService(AudioManager::class.java)
        val ringer = when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" }
        val ringVol = am.getStreamVolume(AudioManager.STREAM_RING) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_RING).coerceAtLeast(1)
        return "Battery $pct%${if (charging) " (charging)" else ""}. Network: $net. Ringer: $ringer, ring volume $ringVol%. Foreground app: ${ScreenAgentService.currentPackage.ifBlank { "unknown" }}."
    }

    fun batteryPercent(): Int = ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}
