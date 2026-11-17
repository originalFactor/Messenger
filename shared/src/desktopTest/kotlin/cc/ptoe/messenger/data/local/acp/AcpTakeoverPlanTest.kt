/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpTakeoverPlanTest {
    private val token = "relay-" + "t".repeat(40)
    private val base = "http://127.0.0.1:43123/v1"
    private val models = listOf(
        AcpTakeoverModel("chat", "Chat", "chat", true),
        AcpTakeoverModel("response", "Response", "responses", true),
        AcpTakeoverModel("message", "Message", "messages", true),
        AcpTakeoverModel("interaction", "Interaction", "interactions", true),
        AcpTakeoverModel("disabled", "Disabled", "chat", false),
        AcpTakeoverModel("unsupported", "Unsupported", "other", true),
    )
    private fun plan(id: String, env: Map<String, String> = emptyMap(), preferred: String? = null, version: Int? = null) =
        AcpTakeoverPlan.configure(id, env, models, preferred, base, token, version)

    @Test fun codexAndClaudeUseOnlyRelayCredentialsAndPreserveSettings() {
        val input = mapOf("CODEX_CONFIG" to """{"approval_policy":"never","model_providers":{"custom":{"unknown":7}},"future":[1]}""")
        val result = plan("codex", input)
        val env = result.environment
        val config = Json.parseToJsonElement(env.getValue("CODEX_CONFIG")).jsonObject
        assertEquals("messenger/response", config.getValue("model").jsonPrimitive.content)
        assertEquals(7, config.getValue("model_providers").jsonObject.getValue("custom").jsonObject.getValue("unknown").jsonPrimitive.int)
        assertEquals(1, config.getValue("future").jsonArray.single().jsonPrimitive.int)
        assertEquals("MESSENGER_RELAY_TOKEN", config.getValue("model_providers").jsonObject.getValue("messenger").jsonObject.getValue("env_key").jsonPrimitive.content)
        assertEquals(token, env["MESSENGER_RELAY_TOKEN"])
        assertFalse(result.toString().contains(token))
        assertEquals("never", Json.parseToJsonElement(input.getValue("CODEX_CONFIG")).jsonObject.getValue("approval_policy").jsonPrimitive.content)
        val claude = plan("claude-code", mapOf("ANTHROPIC_API_KEY" to "old"), "chat").environment
        assertEquals("", claude["ANTHROPIC_API_KEY"])
        assertEquals("http://127.0.0.1:43123", claude["ANTHROPIC_BASE_URL"])
        assertEquals(token, claude["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("messenger/chat", claude["ANTHROPIC_MODEL"])
        assertEquals("messenger/chat", claude["ANTHROPIC_DEFAULT_OPUS_MODEL"])
    }

    @Test fun openCodeRetainsUnknownSettingsAndUsesVersionSpecificCatalog() {
        val existing = mapOf("OPENCODE_CONFIG_CONTENT" to """{"theme":"test","provider":{"other":{"future":true}},"providers":{"existing":{}}}""")
        val v1 = Json.parseToJsonElement(plan("opencode", existing, "response", 1).environment.getValue("OPENCODE_CONFIG_CONTENT")).jsonObject
        assertEquals("test", v1.getValue("theme").jsonPrimitive.content)
        assertTrue(v1.getValue("provider").jsonObject.getValue("other").jsonObject.getValue("future").jsonPrimitive.boolean)
        assertEquals("messenger-responses/response", v1.getValue("model").jsonPrimitive.content)
        val providers1 = v1.getValue("provider").jsonObject
        assertEquals("@ai-sdk/openai", providers1.getValue("messenger-responses").jsonObject.getValue("npm").jsonPrimitive.content)
        assertEquals("@ai-sdk/anthropic", providers1.getValue("messenger-messages").jsonObject.getValue("npm").jsonPrimitive.content)
        val regular = providers1.getValue("messenger").jsonObject.getValue("models").jsonObject
        assertEquals(setOf("chat", "interaction"), regular.keys)
        val v2 = Json.parseToJsonElement(plan("opencode", existing, version = 2).environment.getValue("OPENCODE_CONFIG_CONTENT")).jsonObject
        val providers2 = v2.getValue("providers").jsonObject
        assertTrue("existing" in providers2)
        val generated = providers2.getValue("messenger").jsonObject
        assertEquals(token, generated.getValue("settings").jsonObject.getValue("apiKey").jsonPrimitive.content)
        val catalog = generated.getValue("models").jsonObject
        assertEquals(setOf("chat", "response", "message", "interaction"), catalog.keys)
        assertEquals("@opencode/ai/providers/google/interactions", catalog.getValue("interaction").jsonObject.getValue("package").jsonPrimitive.content)
        assertEquals("messenger/response", catalog.getValue("response").jsonObject.getValue("modelID").jsonPrimitive.content)
    }

    @Test fun invalidConfigurationAndNonLoopbackRelaysFailClosed() {
        assertEquals(1, AcpTakeoverPlan.parseOpenCodeMajor("1.2.3\n"))
        assertEquals(2, AcpTakeoverPlan.parseOpenCodeMajor("opencode v2.3.4-beta+1\n"))
        assertFails { AcpTakeoverPlan.parseOpenCodeMajor("not a version") }
        assertFails { AcpTakeoverPlan.parseOpenCodeMajor("3.0.0") }
        assertFails { plan("custom") }
        assertFails { plan("opencode", version = 3) }
        assertFails { plan("opencode") }
        assertFails { plan("codex", mapOf("CODEX_CONFIG" to "[]")) }
        assertFails { plan("codex", mapOf("CODEX_CONFIG" to """{"model_providers":null}""")) }
        assertFails { plan("opencode", mapOf("OPENCODE_CONFIG_CONTENT" to """{"providers":[]}"""), version = 2) }
        assertFails { AcpTakeoverPlan.configure("codex", emptyMap(), emptyList(), null, base, token) }
        assertFails { AcpTakeoverPlan.configure("codex", emptyMap(), models + models.first(), null, base, token) }
        for (url in listOf("https://127.0.0.1:5/v1", "http://localhost:5/v1", "http://evil@127.0.0.1:5/v1", "http://127.0.0.1:5/v1?x=1", "http://127.0.0.1:5/v1/")) {
            assertFails { AcpTakeoverPlan.configure("codex", emptyMap(), models, null, url, token) }
        }
        assertFails { AcpTakeoverPlan.configure("codex", emptyMap(), models, null, base, "weak") }
        assertFails { AcpTakeoverPlan.configure("codex", emptyMap(), models, null, base, "x".repeat(35) + ":") }
        assertFails { AcpTakeoverPlan.configure("codex", emptyMap(), models, null, base, "x".repeat(35) + "=y") }
    }
}
