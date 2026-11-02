package com.caremate.assistant

import com.fasterxml.jackson.databind.ObjectMapper
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.ContentBlockParam
import com.caremate.assistant.brain.Agent
import com.caremate.assistant.brain.ToolBox
import com.caremate.assistant.brain.ToolOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRequestTest {
    private val json = ObjectMapper().findAndRegisterModules()

    private fun body(model: String, history: List<MessageParam> = listOf(MessageParam.ofUser("Call my son"))) =
        json.readTree(json.writeValueAsString(Agent.buildParams(model, "low", "STABLE", "DYNAMIC", history)._body()))

    @Test fun requestShape() {
        val b = body("claude-opus-5-5")
        assertEquals("claude-opus-5-5", b["model"].asText())
        assertEquals("low", b["output_config"]["effort"].asText())
        assertEquals("default", b["fallbacks"].asText())
        assertEquals("ephemeral", b["system"][0]["cache_control"]["type"].asText())
        assertFalse(b["system"][1].has("cache_control"))
        assertFalse("thinking must be omitted on Opus 5.5", b.has("thinking"))
        val tools = b["tools"]
        assertEquals(ToolBox.definitions.size + 1, tools.size())
        assertEquals("web_search_20260209", tools[0]["type"].asText())
        val names = (1 until tools.size()).map { tools[it]["name"].asText() }
        assertEquals("tool names must be unique", names.size, names.toSet().size)
        names.forEach { assertTrue("bad tool name $it", Regex("^[a-zA-Z0-9_-]{1,64}$").matches(it)) }
        // Every required property must be declared.
        for (i in 1 until tools.size()) {
            val schema = tools[i]["input_schema"]
            assertEquals("object", schema["type"].asText())
            schema["required"]?.forEach { r -> assertTrue("${tools[i]["name"]} missing $r", schema["properties"].has(r.asText())) }
        }
    }

    @Test fun haikuHasNoFallback() {
        assertFalse(body("claude-haiku-5-5").has("fallbacks"))
    }

    @Test fun toolLoopRoundTrip() {
        // A response as the API would return it: thinking, text, and a tool call.
        val response = json.readValue(
            """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
               "content":[{"type":"thinking","thinking":"","signature":"sig"},
                          {"type":"text","text":"Let me call him."},
                          {"type":"tool_use","id":"toolu_1","name":"call","input":{"recipient":"Rahul","extra":3}}],
               "stop_reason":"tool_use","stop_sequence":null,
               "usage":{"input_tokens":10,"output_tokens":5}}""",
            Message::class.java
        )
        val use = response.content().first { it.isToolUse() }.asToolUse()
        val input = Agent.parseInput(use)
        assertEquals("Rahul", input["recipient"])
        assertEquals(3, (input["extra"] as Number).toInt())

        val history = mutableListOf(MessageParam.ofUser("Call my son"), response.toParam())
        history += MessageParam.ofUserOfContentBlockParams(listOf(
            ContentBlockParam.ofToolResult(Agent.toResult("toolu_1", ToolOutput("Calling.")))
        ))
        history += MessageParam.ofUserOfContentBlockParams(listOf(
            ContentBlockParam.ofToolResult(Agent.toResult("toolu_2", ToolOutput("Screenshot", imageJpegBase64 = "AAAA", isError = false)))
        ))
        val msgs = body("claude-opus-5-5", history)["messages"]
        assertEquals("assistant", msgs[1]["role"].asText())
        assertEquals("thinking", msgs[1]["content"][0]["type"].asText())
        assertEquals("sig", msgs[1]["content"][0]["signature"].asText())
        assertEquals("tool_use", msgs[1]["content"][2]["type"].asText())
        assertEquals("tool_result", msgs[2]["content"][0]["type"].asText())
        assertEquals("Calling.", msgs[2]["content"][0]["content"].asText())
        val img = msgs[3]["content"][0]["content"]
        assertEquals("text", img[0]["type"].asText())
        assertEquals("image", img[1]["type"].asText())
        assertEquals("image/jpeg", img[1]["source"]["media_type"].asText())
    }

    @Test fun yesNo() {
        assertEquals(true, YesNo.parse("Yes please"))
        assertEquals(true, YesNo.parse("haan kar do"))
        assertEquals(true, YesNo.parse("go ahead"))
        assertEquals(false, YesNo.parse("no don't"))
        assertEquals(false, YesNo.parse("nahi"))
        assertNull(YesNo.parse("I know him"))
        assertNull(YesNo.parse("what?"))
    }

    @Test fun contactsParsing() {
        val c = Prefs.parseContacts("Rahul (son), +91 98765 43210\n\nPriya: +44 7700 900123\n112")
        assertEquals(3, c.size)
        assertEquals("Rahul (son)", c[0].name)
        assertEquals("+91 98765 43210", c[0].number)
        assertEquals("112", c[2].number)
    }
}
