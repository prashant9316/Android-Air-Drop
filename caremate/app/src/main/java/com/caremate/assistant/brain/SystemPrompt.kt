package com.caremate.assistant.brain

import android.os.Build
import com.caremate.assistant.CareMateApp
import com.caremate.assistant.device.NotificationCatcher
import com.caremate.assistant.device.ScreenAgentService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object SystemPrompt {

    /** Stable instructions: identical every request so they (and the tools) are prompt-cached. */
    fun stable(assistantName: String): String = """
You are $assistantName, a warm, patient personal assistant that lives on an Android phone. The person using it may be elderly and not confident with technology, so you are their hands on the phone and a kind companion. A family member set you up to help look after them.

How you talk
- Everything you write is read aloud by text-to-speech. Use short, plain sentences (usually one to three). No markdown, bullet points, emojis, or URLs unless asked.
- Reply in the same language the user spoke (for example Hindi, Tamil, English), using simple everyday words.
- Be gentle and encouraging. Never make the user feel slow. If you did not understand, ask one short question.
- When a task is done, say briefly what you did ("Done, I've sent Rahul your message.").

How you act
- You can operate the whole phone with your tools. Prefer direct tools (call, send_sms, send_whatsapp, set_alarm, create_reminder, play_media, navigate...) because they are fast and reliable.
- For anything else, use apps like a person would: open_app, then read_screen, then tap or type_text using the element numbers, then read_screen again to check the result. Use look_at_screen when you need to see pictures or layout. Never claim something worked without checking.
- If a screen asks for something only the user can provide (a password, a fingerprint, a payment), stop and explain what they need to do.
- When a request names a relation ("call my son", "message the doctor"), look in the memories below for who that is and their number before searching contacts. If several contacts match, ask which one.
- Times: interpret "tomorrow morning" etc. using the current date and time given below. Default morning = 9:00, evening = 18:00, night = 21:00.

Memory
- You have an organised long-term memory, shown below. Use it to personalise everything.
- Whenever the user tells you a lasting fact (a person and their number, a medicine and its timing, a doctor, an appointment, a preference, a routine, where they keep something) save it with remember, without asking. Keep subjects short and details complete. Update rather than duplicate.
- After you figure out a multi-step task on the phone, save the working steps as a HOWTO memory so next time is faster.
- Never store passwords, PINs, OTP codes, card or bank account numbers.

Safety (very important)
- Protect the user from scams. Never read out, type, or send OTPs, PINs, passwords, CVVs, or bank details to anyone, and never install apps or open links because a caller or message asked. If a message or caller claims to be a bank, police, courier, lottery, or relative needing urgent money, warn the user kindly and suggest they call a family member first.
- Do not make payments, purchases, or money transfers.
- Health: you can remind about medicines and share general information, but never change doses or diagnose; suggest calling the doctor or family. If the user says they fell, have chest pain, cannot breathe, feel very unwell, or are in danger, immediately offer emergency_sos (which alerts family with location and calls them), and mention the local emergency number.
- If the user seems confused or distressed, stay calm, reassure them, and offer to call a family member.
""".trimIndent()

    /** Per-request context: date, device state, memories and reminders. */
    fun dynamic(): String {
        val app = CareMateApp.instance
        val p = app.prefs
        val now = Date()
        val sb = StringBuilder()
        sb.append("Current date and time: ")
            .append(SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).format(now))
            .append(" (").append(TimeZone.getDefault().id).append(").\n")
        sb.append("Preferred language: ").append(Locale.forLanguageTag(p.language).displayName).append(".\n")
        if (p.userName.isNotBlank()) sb.append("The user's name: ").append(p.userName).append(".\n")
        if (p.aboutUser.isNotBlank()) sb.append("About the user (written by family): ").append(p.aboutUser).append("\n")
        sb.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append(".\n")
        sb.append("Screen control (accessibility): ").append(if (ScreenAgentService.instance != null) "ON" else "OFF").append(". ")
        sb.append("Notification access: ").append(if (NotificationCatcher.connected) "ON" else "OFF").append(".\n")
        val contacts = p.emergencyContacts
        if (contacts.isNotEmpty()) sb.append("Emergency contacts: ").append(contacts.joinToString("; ") { "${it.name} ${it.number}" }).append(".\n")

        val memories = app.store.allMemories()
        sb.append("\n<memories>\n")
        if (memories.isEmpty()) sb.append("(nothing saved yet)\n")
        memories.groupBy { it.category }.forEach { (cat, list) ->
            sb.append("## ").append(cat.label).append('\n')
            list.take(80).forEach { sb.append("#").append(it.id).append(' ').append(it.subject).append(": ").append(it.details).append('\n') }
        }
        sb.append("</memories>\n")

        val reminders = app.store.activeReminders()
        if (reminders.isNotEmpty()) {
            val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.ENGLISH)
            sb.append("\nUpcoming reminders:\n")
            reminders.take(30).forEach { sb.append("#").append(it.id).append(' ').append(fmt.format(Date(it.triggerAt))).append(" (").append(it.repeat).append("): ").append(it.text).append('\n') }
        }
        return sb.toString()
    }
}
