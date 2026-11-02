package com.caremate.assistant.care

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.provider.Telephony
import com.caremate.assistant.CareMateApp
import com.caremate.assistant.device.LocationHelper
import com.caremate.assistant.device.PhoneActions
import com.caremate.assistant.memory.MemoryCategory
import com.caremate.assistant.reminders.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/** Emergency help: texts every emergency contact with the phone's location, then calls the first one. */
object Sos {
    suspend fun trigger(ctx: Context, reason: String?): String {
        val app = CareMateApp.instance
        val contacts = app.prefs.emergencyContacts
        if (contacts.isEmpty()) return "No emergency contacts are set. Add them in CareMate settings."
        val actions = PhoneActions(ctx)
        val who = app.prefs.userName.ifBlank { "Your family member" }
        val loc = LocationHelper.current(ctx)
        val where = loc?.let { l ->
            (LocationHelper.address(ctx, l)?.let { "$it. " } ?: "") + LocationHelper.mapsLink(l)
        } ?: "location unavailable"
        val msg = "SOS from CareMate: $who needs help${reason?.let { " ($it)" } ?: ""}. Location: $where. Battery ${actions.batteryPercent()}%."
        val sent = contacts.map { actions.sendSms(it.number, msg) }
        app.store.log("sos", msg)
        val call = actions.call(contacts.first().number)
        return "Alert sent to ${contacts.joinToString { it.name }}. $call ${sent.firstOrNull { !it.startsWith("SMS sent") } ?: ""}".trim()
    }
}

/** Plays a loud alarm (even in silent mode) so a lost phone can be found. */
object FindPhone {
    private var player: MediaPlayer? = null

    fun ring(ctx: Context, seconds: Int = 30) {
        stop()
        val am = ctx.getSystemService(AudioManager::class.java)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            setDataSource(ctx, uri); isLooping = true; prepare(); start()
        }
        CoroutineScope(Dispatchers.Main).launch { delay(seconds * 1000L); stop() }
    }

    fun stop() { player?.runCatching { stop(); release() }; player = null }
}

/**
 * Lets a trusted caregiver check in by SMS, e.g. "CAREMATE 4821 WHERE".
 * Only numbers listed in settings, with the right PIN, are obeyed. Commands:
 * WHERE, STATUS, RING, NOTE <text>, REMIND <HH:MM> <text>, HELP.
 */
class CareSmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val prefs = CareMateApp.instance.prefs
        if (!prefs.careSmsEnabled || prefs.carePin.length < 4) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val from = msgs.firstOrNull()?.originatingAddress ?: return
        val body = msgs.joinToString("") { it.messageBody ?: "" }.trim()
        val m = Regex("^CAREMATE\\s+(\\d{4,8})\\s+(\\w+)\\s*(.*)$", RegexOption.IGNORE_CASE).find(body) ?: return
        val (pin, cmd, arg) = m.destructured
        val trusted = prefs.caregiverNumbers.any { it.filter(Char::isDigit).takeLast(10) == from.filter(Char::isDigit).takeLast(10) }
        if (!trusted || pin != prefs.carePin) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val actions = PhoneActions(ctx)
            val reply = try {
                handle(ctx, actions, cmd.uppercase(), arg.trim())
            } catch (e: Exception) { "CareMate error: ${e.message}" }
            actions.sendSms(from, reply)
            CareMateApp.instance.store.log("caregiver", "$cmd $arg -> $reply")
            pending.finish()
        }
    }

    private suspend fun handle(ctx: Context, actions: PhoneActions, cmd: String, arg: String): String = when (cmd) {
        "WHERE" -> LocationHelper.current(ctx)?.let { l ->
            "Phone is at " + (LocationHelper.address(ctx, l)?.let { "$it. " } ?: "") + LocationHelper.mapsLink(l) +
                " (accuracy ${l.accuracy.toInt()} m)"
        } ?: "Location unavailable (location off or permission missing)."
        "STATUS" -> actions.status()
        "RING" -> { kotlinx.coroutines.withContext(Dispatchers.Main) { FindPhone.ring(ctx) }; "Ringing loudly for 30 seconds." }
        "NOTE" -> {
            CareMateApp.instance.store.remember(MemoryCategory.NOTE, "From caregiver " + java.text.DateFormat.getDateInstance().format(java.util.Date()), arg)
            "Saved to CareMate's memory: $arg"
        }
        "REMIND" -> {
            val r = Regex("^(\\d{1,2})[:.](\\d{2})\\s+(.+)$").find(arg)
            if (r == null) "Use: REMIND 18:30 take evening tablet" else {
                val (h, mi, text) = r.destructured
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, h.toInt()); set(Calendar.MINUTE, mi.toInt()); set(Calendar.SECOND, 0)
                    if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                }
                Reminders.add(ctx, text, cal.timeInMillis, "none")
                "Reminder set for $h:$mi: $text"
            }
        }
        else -> "Commands: WHERE, STATUS, RING, NOTE <text>, REMIND <HH:MM> <text>"
    }
}
