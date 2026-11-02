package com.caremate.assistant

import android.content.Context
import android.content.Intent
import com.caremate.assistant.brain.Agent
import com.caremate.assistant.brain.AgentUnavailable
import com.caremate.assistant.brain.LocalCommands
import com.caremate.assistant.brain.ToolBox
import com.caremate.assistant.brain.UserBridge
import com.caremate.assistant.ui.MainActivity
import com.caremate.assistant.voice.Listener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class Phase { IDLE, LISTENING, THINKING, ACTING, SPEAKING }

data class ChatLine(val fromUser: Boolean, val text: String)

data class UiState(
    val phase: Phase = Phase.IDLE,
    val partial: String = "",
    val level: Float = 0f,
    val status: String = "",
    val lines: List<ChatLine> = emptyList(),
    val confirmQuestion: String? = null,
)

/**
 * App-wide assistant loop: listen -> think (Claude, or offline commands) -> act -> speak.
 * Lives as long as the process so a task keeps going while other apps are on screen.
 */
object AssistantEngine : UserBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private lateinit var ctx: Context
    private lateinit var agent: Agent
    private lateinit var local: LocalCommands
    private var job: Job? = null
    private var confirmAnswer: CompletableDeferred<Boolean>? = null

    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        agent = Agent(ctx, this)
        local = LocalCommands(ctx, this)
        agent.onTool = { name ->
            _state.update { it.copy(phase = Phase.ACTING, status = friendly(name)) }
            if (name in ToolBox.SCREEN_TOOLS) leftApp = true
        }
    }

    private val app get() = CareMateApp.instance
    private var leftApp = false

    fun listen() {
        if (_state.value.confirmQuestion != null) { listenForConfirm(); return }
        cancel()
        job = scope.launch {
            app.speaker.stop()
            val heard = hear() ?: run { setIdle(); return@launch }
            handle(heard)
        }
    }

    fun submitText(text: String) {
        if (text.isBlank()) return
        cancel()
        job = scope.launch { handle(text) }
    }

    /** SOS button: runs the emergency flow directly, without waiting on the internet. */
    fun sos() {
        cancel()
        job = scope.launch {
            addLine(true, "SOS")
            val reply = ToolBox(ctx, this@AssistantEngine).run("emergency_sos", mapOf("reason" to "SOS button pressed")).text
            addLine(false, reply)
            app.store.log("assistant", reply)
            _state.update { it.copy(phase = Phase.SPEAKING, status = "") }
            app.speaker.speak(reply)
            setIdle()
        }
    }

    private fun friendly(tool: String) = when (tool) {
        "read_screen", "look_at_screen" -> "Looking at the screen…"
        "tap", "tap_at", "type_text", "scroll", "press_button" -> "Using the phone…"
        "remember" -> "Remembering that…"
        "search_memory" -> "Checking my memory…"
        "read_notifications", "read_sms", "recent_calls" -> "Checking your messages…"
        "create_reminder", "set_alarm", "set_timer" -> "Setting it up…"
        "get_location", "navigate" -> "Finding the location…"
        "web_search" -> "Searching…"
        else -> "Working on it…"
    }

    fun cancel() {
        job?.cancel(); job = null
        app.speaker.stop()
        confirmAnswer?.complete(false)
        setIdle()
    }

    fun newConversation() { cancel(); agent.reset(); _state.update { it.copy(lines = emptyList()) } }

    private suspend fun hear(): String? {
        _state.update { it.copy(phase = Phase.LISTENING, partial = "", status = "Listening…") }
        val heard = Listener.listen(ctx, app.prefs.language,
            onPartial = { p -> _state.update { it.copy(partial = p) } },
            onLevel = { l -> _state.update { it.copy(level = l) } })
        _state.update { it.copy(partial = "", level = 0f) }
        return heard
    }

    private suspend fun handle(text: String) {
        addLine(true, text)
        app.store.log("user", text)
        _state.update { it.copy(phase = Phase.THINKING, status = "Thinking…") }
        leftApp = false
        val reply = try {
            agent.ask(text)
        } catch (e: AgentUnavailable) {
            local.handle(text) ?: "${e.message} I can still call people, tell the time, open apps, and send an SOS."
        }
        addLine(false, reply)
        app.store.log("assistant", reply)
        if (leftApp) bringToFrontIfNeeded(reply)
        _state.update { it.copy(phase = Phase.SPEAKING, status = "") }
        app.speaker.speak(reply)
        // Natural back-and-forth: if we asked a question, listen for the answer.
        if (app.prefs.keepListening && reply.trimEnd().endsWith("?")) {
            val next = hear()
            if (next != null) { handle(next); return }
        }
        setIdle()
    }

    private fun bringToFrontIfNeeded(reply: String) {
        // Leave the user in the app we opened (WhatsApp, YouTube...). Only the spoken reply follows them.
        if (reply.trimEnd().endsWith("?")) showApp()
    }

    private fun showApp() {
        runCatching {
            ctx.startActivity(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private fun setIdle() = _state.update { it.copy(phase = Phase.IDLE, status = "", partial = "", level = 0f) }

    private fun addLine(user: Boolean, text: String) =
        _state.update { it.copy(lines = (it.lines + ChatLine(user, text)).takeLast(40)) }

    // ---------------------------------------------------------------- UserBridge

    override fun progress(text: String) = _state.update { it.copy(status = text) }

    override suspend fun confirm(question: String, defaultOnTimeout: Boolean): Boolean {
        val answer = CompletableDeferred<Boolean>()
        confirmAnswer = answer
        _state.update { it.copy(confirmQuestion = question, phase = Phase.SPEAKING) }
        showApp()
        app.speaker.speak(question + if (defaultOnTimeout) " Say no to cancel." else " Say yes or no.")
        // Listen for a spoken yes/no while the buttons are also on screen.
        val voice = scope.launch {
            repeat(2) {
                if (answer.isCompleted) return@launch
                val heard = hear()?.lowercase() ?: return@repeat
                YesNo.parse(heard)?.let { answer.complete(it); return@launch }
            }
        }
        val result = withTimeoutOrNull(30_000) { answer.await() } ?: defaultOnTimeout
        voice.cancel()
        confirmAnswer = null
        _state.update { it.copy(confirmQuestion = null, phase = Phase.ACTING, partial = "") }
        return result
    }

    private fun listenForConfirm() {
        scope.launch { hear()?.lowercase()?.let { YesNo.parse(it) }?.let { answerConfirm(it) } }
    }

    fun answerConfirm(yes: Boolean) { confirmAnswer?.complete(yes) }

}

/** Interprets a spoken yes/no (English and common Hindi). Matches whole words only. */
object YesNo {
    fun parse(s: String): Boolean? {
        val words = s.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        val text = " " + words.joinToString(" ") + " "
        val no = listOf("no", "nope", "don't", "dont", "not", "cancel", "stop", "nahi", "nahin", "mat", "wait", "never")
        val yes = listOf("yes", "yeah", "yep", "yup", "ok", "okay", "sure", "haan", "han", "haa", "ha", "ji", "theek", "thik", "send", "call", "please", "correct", "right")
        val yesPhrases = listOf(" go ahead ", " do it ", " kar do ", " karo ")
        return when {
            words.any { it in no } -> false
            words.any { it in yes } || yesPhrases.any { text.contains(it) } -> true
            else -> null
        }
    }
}
