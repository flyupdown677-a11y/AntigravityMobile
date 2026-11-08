package com.antigravity.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Request and Response models for the Google Gemini Interactions API:
 * POST /v1beta/interactions
 * GET  /v1beta/interactions/{id}
 */

@Serializable
data class InteractionRequest(
    val agent: String = "antigravity-preview-09-2026",
    val input: List<InteractionInputContent>,
    val environment: JsonElement, // Can be string "remote", environment ID, or object config
    val background: Boolean = true,
    val tools: List<ToolConfig> = listOf(
        ToolConfig(type = "code_execution"),
        ToolConfig(type = "google_search"),
        ToolConfig(type = "url_context")
    ),
    @SerialName("agent_config")
    val agentConfig: AgentConfig? = null,
    @SerialName("previous_interaction_id")
    val previousInteractionId: String? = null
)

@Serializable
data class InteractionInputContent(
    val type: String = "text",
    val text: String
)

@Serializable
data class ToolConfig(
    val type: String
)

@Serializable
data class AgentConfig(
    val type: String = "antigravity",
    val model: String = "gemini-3.8-flash"
)

@Serializable
data class InteractionResponse(
    val id: String,
    val status: String, // in_progress, completed, failed, cancelled, requires_action, queued
    @SerialName("environment_id")
    val environmentId: String? = null,
    val steps: List<InteractionStep> = emptyList(),
    @SerialName("output_text")
    val outputText: String? = null,
    val error: JsonElement? = null
)

@Serializable
data class InteractionStep(
    val id: String? = null,
    val type: String, // user_input, model_output, thought, code_execution_call, code_execution_result, google_search_call, google_search_result, url_context_call, url_context_result
    val thought: String? = null,
    val summary: String? = null,
    val content: List<StepContentPart> = emptyList(),
    val code: String? = null,
    val output: String? = null,
    val call_id: String? = null,
    val name: String? = null,
    val arguments: JsonObject? = null,
    val result: JsonElement? = null
)

@Serializable
data class StepContentPart(
    val type: String = "text",
    val text: String? = null
)
