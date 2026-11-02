package com.caremate.assistant.brain

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import com.anthropic.models.messages.WebSearchTool20260209
import com.caremate.assistant.CareMateApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/** Thrown when Claude can't be reached, so the engine can fall back to offline commands. */
class AgentUnavailable(message: String) : Exception(message)

/**
 * The Claude tool-use loop. One [Agent] holds the running conversation; history is only
 * ever appended to (never edited) and is reset after a quiet period.
 */
class Agent(private val ctx: Context, private val bridge: UserBridge) {
    private val tools = ToolBox(ctx, bridge)
    private val history = mutableListOf<MessageParam>()
    private var lastUsed = 0L
    private var client: AnthropicClient? = null
    private var clientKey = ""

    /** Called before each tool so the UI can react (e.g. step aside for screen tools). */
    var onTool: (name: String) -> Unit = {}

    fun reset() { history.clear() }

    private fun client(): AnthropicClient {
        val key = CareMateApp.instance.prefs.apiKey
        if (key.isBlank()) throw AgentUnavailable("No Claude API key set.")
        if (client == null || key != clientKey) {
            client = AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(120)).maxRetries(2).build()
            clientKey = key
        }
        return client!!
    }

    suspend fun ask(userText: String): String = withContext(Dispatchers.IO) {
        val prefs = CareMateApp.instance.prefs
        val now = System.currentTimeMillis()
        // New conversation after 10 quiet minutes, or when it gets long. We start fresh
        // rather than trimming, so the history stays append-only.
        if (now - lastUsed > 10 * 60_000 || history.size > 80) history.clear()
        lastUsed = now

        history += MessageParam.ofUser(userText)
        val model = prefs.model.ifBlank { com.caremate.assistant.Prefs.DEFAULT_MODEL }
        val spoken = StringBuilder()

        repeat(40) {
            val params = buildParams(model, prefs.effort, SystemPrompt.stable(prefs.assistantName), SystemPrompt.dynamic(), history)

            val response = try {
                client().messages().create(params)
            } catch (e: Exception) {
                // Start the next request from a clean, valid conversation.
                history.clear()
                throw AgentUnavailable(
                    when (e) {
                        is AgentUnavailable -> e.message ?: "Claude is unavailable."
                        is UnauthorizedException -> "The Claude API key was rejected. Please check it in settings."
                        is RateLimitException -> "Claude is busy right now."
                        is AnthropicServiceException -> "Claude had a problem (error ${e.statusCode()})."
                        else -> "I can't reach the internet right now."
                    }
                )
            }

            val stop = response.stopReason().orElse(StopReason.END_TURN)
            if (stop == StopReason.REFUSAL) {
                history.clear()
                return@withContext "Sorry, I can't help with that one."
            }
            history += response.toParam()
            response.content().forEach { b -> b.text().ifPresent { spoken.append(it.text()).append(' ') } }

            when (stop) {
                StopReason.TOOL_USE -> {
                    val results = mutableListOf<ContentBlockParam>()
                    for (block in response.content()) {
                        val use = block.toolUse().orElse(null) ?: continue
                        val input = parseInput(use)
                        onTool(use.name())
                        val out = tools.run(use.name(), input)
                        results += ContentBlockParam.ofToolResult(toResult(use.id(), out))
                    }
                    history += MessageParam.ofUserOfContentBlockParams(results)
                    spoken.clear() // Only the text after the last tool call is the final answer.
                }
                StopReason.PAUSE_TURN -> { /* server tool (web search) paused: send the history back to continue */ }
                StopReason.MAX_TOKENS -> return@withContext spoken.toString().trim().ifBlank { "Sorry, that answer got too long." }
                else -> return@withContext spoken.toString().trim().ifBlank { "Done." }
            }
        }
        "I've been working on that for a while. Shall I keep going?"
    }

    companion object {
        private val FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-opus-5")

        /** Builds one Messages API request. Pure function so it can be unit tested. */
        fun buildParams(model: String, effort: String, stablePrompt: String, dynamicPrompt: String, history: List<MessageParam>): MessageCreateParams {
            val builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .systemOfTextBlockParams(
                    listOf(
                        // Tools + this block are identical every request, so they are prompt-cached.
                        TextBlockParam.builder().text(stablePrompt).cacheControl(CacheControlEphemeral.builder().build()).build(),
                        TextBlockParam.builder().text(dynamicPrompt).build(),
                    )
                )
                .messages(history.toList())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort)).build())
                .addTool(WebSearchTool20260209.builder().maxUses(3L).build())
            ToolBox.definitions.forEach { builder.addTool(it) }
            if (model in FALLBACK_MODELS) {
                // Server-side fallback: if a safety classifier declines, the API retries on another model.
                builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
            return builder.build()
        }

        @Suppress("UNCHECKED_CAST")
        fun parseInput(use: ToolUseBlock): Map<String, Any?> =
            runCatching { use._input().convert(Map::class.java) as Map<String, Any?> }.getOrNull() ?: emptyMap()

        fun toResult(id: String, out: ToolOutput): ToolResultBlockParam {
            val b = ToolResultBlockParam.builder().toolUseId(id)
            if (out.imageJpegBase64 != null) {
                b.contentOfBlocks(
                    listOf(
                        ToolResultBlockParam.Content.Block.ofText(out.text),
                        ToolResultBlockParam.Content.Block.ofImage(
                            ImageBlockParam.builder().source(
                                Base64ImageSource.builder().data(out.imageJpegBase64).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build()
                            ).build()
                        ),
                    )
                )
            } else b.content(out.text)
            if (out.isError) b.isError(true)
            return b.build()
        }
    }
}
