package com.caremate.assistant.brain

import android.content.Context
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.caremate.assistant.CareMateApp
import com.caremate.assistant.care.Sos
import com.caremate.assistant.device.LocationHelper
import com.caremate.assistant.device.NotificationCatcher
import com.caremate.assistant.device.PhoneActions
import com.caremate.assistant.device.ScreenAgentService
import com.caremate.assistant.memory.MemoryCategory
import com.caremate.assistant.reminders.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** What a tool returns to Claude: text, optionally with a screenshot. */
data class ToolOutput(val text: String, val imageJpegBase64: String? = null, val isError: Boolean = false)

/** How tools talk back to the person holding the phone. */
interface UserBridge {
    /** Ask a yes/no question out loud and on screen. */
    suspend fun confirm(question: String, defaultOnTimeout: Boolean = false): Boolean
    /** Short status line, e.g. "Opening WhatsApp…". */
    fun progress(text: String)
}

class ToolBox(private val ctx: Context, private val bridge: UserBridge) {
    private val app get() = CareMateApp.instance
    private val phone = PhoneActions(ctx)

    // ------------------------------------------------------------------ execution

    private val sensitive = setOf("call", "send_sms", "send_whatsapp")

    suspend fun run(name: String, input: Map<String, Any?>): ToolOutput = try {
        execute(name, input)
    } catch (e: Exception) {
        ToolOutput("Error in $name: ${e.message}", isError = true)
    }

    private fun Map<String, Any?>.str(k: String) = this[k]?.toString()?.takeIf { it.isNotBlank() }
    private fun Map<String, Any?>.int(k: String, def: Int) = (this[k] as? Number)?.toInt() ?: this[k]?.toString()?.toIntOrNull() ?: def
    private fun Map<String, Any?>.bool(k: String) = this[k] == true || this[k]?.toString() == "true"

    private fun screen(): ScreenAgentService? = ScreenAgentService.instance

    private val noScreen = ToolOutput(
        "Screen control is off. Ask the user to turn on CareMate in Settings > Accessibility (the setup screen has a button).",
        isError = true
    )

    private suspend fun execute(name: String, a: Map<String, Any?>): ToolOutput {
        val out: String = when (name) {
            "remember" -> {
                val cat = MemoryCategory.parse(a.str("category"))
                val id = app.store.remember(cat, a.str("subject") ?: "Note", a.str("details") ?: "")
                "Saved as memory #$id (${cat.label})."
            }
            "forget_memory" -> if (app.store.forget(a.int("id", -1).toLong())) "Forgotten." else "No memory with that id."
            "search_memory" -> app.store.search(a.str("query") ?: "").take(20)
                .joinToString("\n") { "#${it.id} [${it.category.name}] ${it.subject}: ${it.details}" }.ifBlank { "Nothing found in memory." }

            "find_contact" -> phone.findContacts(a.str("name") ?: "").take(10)
                .joinToString("\n") { "${it.name} (${it.label}): ${it.number}" }.ifBlank { "No matching contacts (or contacts permission missing)." }

            "call", "send_sms", "send_whatsapp" -> {
                val (contact, err) = phone.resolve(a.str("recipient") ?: "")
                if (contact == null) return ToolOutput(err ?: "Unknown recipient", isError = true)
                val msg = a.str("message").orEmpty()
                val who = if (contact.name != contact.number) contact.name else contact.number
                if (name in sensitive && app.prefs.confirmActions) {
                    val q = when (name) {
                        "call" -> "Shall I call $who?"
                        "send_sms" -> "Send this text to $who: \"$msg\"?"
                        else -> "Send this WhatsApp to $who: \"$msg\"?"
                    }
                    if (!bridge.confirm(q)) return ToolOutput("The user said no. Nothing was sent.")
                }
                when (name) {
                    "call" -> { bridge.progress("Calling $who…"); phone.call(contact.number) }
                    "send_sms" -> phone.sendSms(contact.number, msg)
                    else -> { bridge.progress("Opening WhatsApp…"); phone.sendWhatsApp(contact.number, msg, autoSend = true) }
                }
            }
            "recent_calls" -> phone.recentCalls(a.int("limit", 10))
            "read_sms" -> phone.recentSms(a.int("limit", 5))
            "read_notifications" -> {
                if (!NotificationCatcher.connected) "Notification access is off. Ask the user to allow it in CareMate setup."
                else {
                    val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                    NotificationCatcher.snapshot(a.int("limit", 10))
                        .joinToString("\n") { "${it.app} ${fmt.format(Date(it.time))} - ${it.title}: ${it.text.take(300)}" }
                        .ifBlank { "No recent notifications." }
                }
            }

            "open_app" -> { bridge.progress("Opening ${a.str("name")}…"); phone.openApp(a.str("name") ?: "").also { delay(1500) } }
            "list_apps" -> phone.launchableApps().map { it.first }.sorted().joinToString(", ")
            "read_screen" -> screen()?.describeScreen() ?: return noScreen
            "look_at_screen" -> {
                val svc = screen() ?: return noScreen
                val img = svc.screenshotBase64() ?: return ToolOutput("Screenshot not available (needs Android 11+). Use read_screen.", isError = true)
                val dm = ctx.resources.displayMetrics
                return ToolOutput("Screenshot attached. Real screen size is ${dm.widthPixels}x${dm.heightPixels} pixels (image may be scaled down; scale coordinates for tap_at).", img)
            }
            "tap" -> screen()?.tap(a.str("target") ?: "", a.bool("long_press")) ?: return noScreen
            "tap_at" -> {
                val svc = screen() ?: return noScreen
                if (svc.tapAt(a.int("x", 0).toFloat(), a.int("y", 0).toFloat())) { delay(700); "Tapped." } else "Tap failed."
            }
            "type_text" -> screen()?.typeText(a.str("text") ?: "", a.str("target"), a.bool("submit")) ?: return noScreen
            "scroll" -> screen()?.scroll(a.str("direction") ?: "down") ?: return noScreen
            "press_button" -> screen()?.press(a.str("button") ?: "back") ?: return noScreen
            "wait" -> { delay(a.int("seconds", 2).coerceIn(1, 10) * 1000L); "Waited." }
            "open_url" -> phone.openUrl(a.str("url") ?: "")

            "set_alarm" -> phone.setAlarm(a.int("hour", 7), a.int("minute", 0), a.str("label"))
            "set_timer" -> phone.setTimer(a.int("seconds", 60), a.str("label"))
            "create_reminder" -> {
                val at = parseLocal(a.str("when")) ?: return ToolOutput("Bad time format; use YYYY-MM-DDTHH:MM", isError = true)
                val repeat = a.str("repeat") ?: "none"
                val id = Reminders.add(ctx, a.str("text") ?: "Reminder", at, repeat)
                "Reminder #$id set for ${fmtTime(at)}${if (repeat != "none") ", repeating $repeat" else ""}."
            }
            "list_reminders" -> app.store.activeReminders()
                .joinToString("\n") { "#${it.id} ${fmtTime(it.triggerAt)} (${it.repeat}): ${it.text}" }.ifBlank { "No reminders." }
            "cancel_reminder" -> if (Reminders.cancel(ctx, a.int("id", -1).toLong())) "Reminder cancelled." else "No such reminder."
            "calendar_events" -> phone.calendarEvents(a.int("days", 7))
            "add_calendar_event" -> {
                val at = parseLocal(a.str("start")) ?: return ToolOutput("Bad time format; use YYYY-MM-DDTHH:MM", isError = true)
                phone.addCalendarEvent(a.str("title") ?: "Event", at, a.int("minutes", 60), a.str("location"))
            }

            "device_status" -> phone.status()
            "get_location" -> {
                val loc = LocationHelper.current(ctx) ?: return ToolOutput("Location unavailable: location may be off or permission missing.", isError = true)
                (LocationHelper.address(ctx, loc) ?: "Address unknown") + ". Map: " + LocationHelper.mapsLink(loc)
            }
            "set_volume" -> phone.setVolume(a.str("stream") ?: "media", a.int("percent", 50))
            "set_ringer_mode" -> phone.setRingerMode(a.str("mode") ?: "normal")
            "flashlight" -> phone.flashlight(a.bool("on"))
            "set_brightness" -> phone.setBrightness(a.int("percent", 60))
            "open_settings" -> phone.openSettings(a.str("page") ?: "main")
            "navigate" -> phone.navigate(a.str("destination") ?: "", a.str("mode"))
            "play_media" -> phone.playMedia(a.str("query") ?: "", a.str("app"))
            "open_camera" -> phone.openCamera(a.bool("selfie"))

            "emergency_sos" -> {
                // Defaults to YES if nobody answers: someone who has fallen may not be able to reply.
                if (!bridge.confirm("Do you want me to alert your family and call for help?", defaultOnTimeout = true))
                    return ToolOutput("The user cancelled the SOS.")
                bridge.progress("Sending SOS…")
                Sos.trigger(ctx, a.str("reason"))
            }
            else -> return ToolOutput("Unknown tool $name", isError = true)
        }
        return ToolOutput(out)
    }

    private fun parseLocal(s: String?): Long? = runCatching {
        LocalDateTime.parse(s!!.trim().take(16)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    private fun fmtTime(ms: Long) = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(ms))

    companion object {
        // ------------------------------------------------------------------ schema helpers

        private fun s(desc: String) = mapOf("type" to "string", "description" to desc)
        private fun i(desc: String) = mapOf("type" to "integer", "description" to desc)
        private fun b(desc: String) = mapOf("type" to "boolean", "description" to desc)
        private fun e(desc: String, vararg values: String) = mapOf("type" to "string", "enum" to values.toList(), "description" to desc)

        private fun tool(name: String, desc: String, props: Map<String, Map<String, Any>> = emptyMap(), required: List<String> = emptyList()): Tool =
            Tool.builder().name(name).description(desc).inputSchema(
                Tool.InputSchema.builder()
                    .properties(Tool.InputSchema.Properties.builder().apply {
                        props.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
                    }.build())
                    .required(required)
                    .build()
            ).build()

        val definitions: List<Tool> = listOf(
            // memory
            tool("remember", "Save or update a long-term memory about the user's life (people and their phone numbers, medicines, doctors, routines, preferences, places, events, how-tos). Same category+subject overwrites the old entry. Never store passwords, PINs, OTPs or bank card numbers.",
                mapOf("category" to e("Memory category", *MemoryCategory.entries.map { it.name }.toTypedArray()),
                    "subject" to s("Short key, e.g. 'Rahul (son)', 'BP tablet', 'Dr Mehta'"),
                    "details" to s("The facts to remember, complete and self-contained")),
                listOf("category", "subject", "details")),
            tool("forget_memory", "Delete a memory by its id.", mapOf("id" to i("Memory id")), listOf("id")),
            tool("search_memory", "Search long-term memory by keywords.", mapOf("query" to s("Keywords")), listOf("query")),

            // people & communication
            tool("find_contact", "Look up phone numbers in the phone's contacts by name.", mapOf("name" to s("Contact name or part of it")), listOf("name")),
            tool("call", "Phone call a contact name or number. Use the exact name saved in contacts or a number from memory.",
                mapOf("recipient" to s("Contact name or phone number")), listOf("recipient")),
            tool("send_sms", "Send a text message (SMS).", mapOf("recipient" to s("Contact name or number"), "message" to s("Message text")), listOf("recipient", "message")),
            tool("send_whatsapp", "Send a WhatsApp message. Opens the chat with the text typed and presses Send.",
                mapOf("recipient" to s("Contact name or number"), "message" to s("Message text")), listOf("recipient", "message")),
            tool("recent_calls", "List recent calls including missed calls.", mapOf("limit" to i("How many, default 10"))),
            tool("read_sms", "Read the latest SMS messages in the inbox.", mapOf("limit" to i("How many, default 5"))),
            tool("read_notifications", "Read recent notifications from all apps (WhatsApp messages, missed calls, app alerts).", mapOf("limit" to i("How many, default 10"))),

            // apps & screen control
            tool("open_app", "Open an installed app by name (e.g. 'WhatsApp', 'YouTube', 'Camera', 'Gallery').", mapOf("name" to s("App name")), listOf("name")),
            tool("list_apps", "List installed apps."),
            tool("read_screen", "Read everything currently on screen as a numbered list of elements. Use the numbers with tap/type_text. Always call this after opening an app or tapping, before the next action."),
            tool("look_at_screen", "Take a screenshot to see the screen visually (pictures, layouts, things the text list misses)."),
            tool("tap", "Tap an element on screen by its [number] from read_screen, or by its visible text.",
                mapOf("target" to s("Element number like '12' or visible text like 'Send'"), "long_press" to b("Long-press instead of tap")), listOf("target")),
            tool("tap_at", "Tap at exact screen pixel coordinates (use after look_at_screen when there is no element to target).",
                mapOf("x" to i("X pixel"), "y" to i("Y pixel")), listOf("x", "y")),
            tool("type_text", "Type text into a text box (the focused one, or the given element).",
                mapOf("text" to s("Text to type"), "target" to s("Optional element number or text of the box"), "submit" to b("Press enter/search after typing")), listOf("text")),
            tool("scroll", "Scroll the screen.", mapOf("direction" to e("Direction", "down", "up", "left", "right")), listOf("direction")),
            tool("press_button", "Press a system button.", mapOf("button" to e("Button", "back", "home", "recents", "notifications", "quick_settings", "lock_screen", "screenshot", "power_dialog")), listOf("button")),
            tool("wait", "Wait for an app or page to load.", mapOf("seconds" to i("1 to 10")), listOf("seconds")),
            tool("open_url", "Open a web page or deep link.", mapOf("url" to s("URL")), listOf("url")),

            // time
            tool("set_alarm", "Set an alarm in the clock app.", mapOf("hour" to i("0-23"), "minute" to i("0-59"), "label" to s("Optional label")), listOf("hour", "minute")),
            tool("set_timer", "Start a countdown timer.", mapOf("seconds" to i("Duration in seconds"), "label" to s("Optional label")), listOf("seconds")),
            tool("create_reminder", "Create a spoken reminder (medicine, appointment, call someone). It rings and reads the text aloud at the time.",
                mapOf("text" to s("What to remind, phrased to be read aloud, e.g. 'Take your blood pressure tablet with water'"),
                    "when" to s("Local date-time, format YYYY-MM-DDTHH:MM"),
                    "repeat" to e("Repeat", "none", "daily", "weekly")), listOf("text", "when")),
            tool("list_reminders", "List upcoming reminders."),
            tool("cancel_reminder", "Cancel a reminder by id.", mapOf("id" to i("Reminder id")), listOf("id")),
            tool("calendar_events", "List upcoming calendar events.", mapOf("days" to i("Days ahead, default 7"))),
            tool("add_calendar_event", "Add an event to the phone calendar.",
                mapOf("title" to s("Title"), "start" to s("Local date-time YYYY-MM-DDTHH:MM"), "minutes" to i("Duration minutes, default 60"), "location" to s("Optional place")),
                listOf("title", "start")),

            // device
            tool("device_status", "Battery, charging, internet, ringer mode and volume, current app."),
            tool("get_location", "Get the phone's current address and a map link."),
            tool("set_volume", "Set a volume level.", mapOf("stream" to e("Which volume", "media", "ring", "alarm", "call", "notification"), "percent" to i("0-100")), listOf("stream", "percent")),
            tool("set_ringer_mode", "Switch between normal, vibrate and silent.", mapOf("mode" to e("Mode", "normal", "vibrate", "silent")), listOf("mode")),
            tool("flashlight", "Turn the torch on or off.", mapOf("on" to b("true for on")), listOf("on")),
            tool("set_brightness", "Set screen brightness.", mapOf("percent" to i("1-100")), listOf("percent")),
            tool("open_settings", "Open a phone settings page.",
                mapOf("page" to e("Page", "main", "wifi", "bluetooth", "mobile_data", "display", "font_size", "sound", "battery", "accessibility", "apps", "location", "notifications", "date_time", "language", "storage", "airplane_mode", "default_apps")),
                listOf("page")),
            tool("navigate", "Start Google Maps directions.", mapOf("destination" to s("Place or address"), "mode" to e("Travel mode", "drive", "walk", "transit", "bike")), listOf("destination")),
            tool("play_media", "Play music, a song, bhajan, podcast or video.", mapOf("query" to s("What to play"), "app" to s("Optional: youtube, spotify, music")), listOf("query")),
            tool("open_camera", "Open the camera.", mapOf("selfie" to b("Front camera"))),

            // safety
            tool("emergency_sos", "EMERGENCY: text all emergency contacts the user's location and call the first one. Use when the user asks for help, has fallen, feels very unwell or is in danger.",
                mapOf("reason" to s("Short reason")), emptyList()),
        )


        /** Tools that move to another app, so the UI should step aside. */
        val SCREEN_TOOLS = setOf("open_app", "tap", "tap_at", "type_text", "scroll", "press_button", "open_url", "navigate", "play_media", "open_camera", "open_settings")
    }
}

/** Runs [block] on the IO dispatcher; tools that touch content providers shouldn't block the UI. */
suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
