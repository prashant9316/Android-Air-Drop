package com.caremate.assistant.brain

import android.content.Context
import com.caremate.assistant.device.PhoneActions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Basic commands that work with no internet or API key, so the essentials
 * (calling family, the time, battery, torch, opening apps) never stop working.
 */
class LocalCommands(private val ctx: Context, private val bridge: UserBridge) {
    private val tools = ToolBox(ctx, bridge)
    private val phone = PhoneActions(ctx)

    /** Returns a spoken reply, or null if the phrase isn't understood offline. */
    suspend fun handle(text: String): String? {
        val t = text.lowercase(Locale.getDefault()).trim()
        Regex("^(?:please )?(?:call|phone|ring|dial)\\s+(.+)$").find(t)?.let {
            return tools.run("call", mapOf("recipient" to it.groupValues[1].removePrefix("my "))).text
        }
        Regex("^(?:open|start|launch)\\s+(.+)$").find(t)?.let { return phone.openApp(it.groupValues[1]) }
        if (Regex("\\b(help|emergency|sos|i fell|i have fallen)\\b").containsMatchIn(t)) return tools.run("emergency_sos", mapOf("reason" to text)).text
        if (Regex("\\b(time)\\b").containsMatchIn(t) && t.contains("what")) return "It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + "."
        if (Regex("\\b(date|day)\\b").containsMatchIn(t) && t.contains("what")) return "Today is " + SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()) + "."
        if (t.contains("battery")) return "Battery is at ${phone.batteryPercent()} percent."
        if (Regex("(torch|flash ?light)").containsMatchIn(t)) return phone.flashlight(!t.contains("off"))
        if (t.contains("missed call")) return phone.recentCalls(5)
        if (t.contains("where am i")) return tools.run("get_location", emptyMap()).text
        return null
    }
}
