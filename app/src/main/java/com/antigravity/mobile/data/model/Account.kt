package com.antigravity.mobile.data.model

import kotlinx.serialization.Serializable

/**
 * Represents a user account/profile for Antigravity.
 * Supports multiple accounts (e.g., Personal, Work, Vertex AI).
 */
@Serializable
data class Account(
    val id: String,
    val name: String,
    val email: String = "",
    val apiKey: String, // Gemini API Key
    val endpointType: EndpointType = EndpointType.GEMINI_API,
    val modelName: String = "gemini-3.8-flash",
    val customWorkspaceDir: String = "",
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

enum class EndpointType {
    GEMINI_API,
    VERTEX_AI,
    LOCAL_LITERT
}

@Serializable
data class Project(
    val id: String,
    val name: String,
    val absolutePath: String,
    val lastModified: Long = System.currentTimeMillis(),
    val description: String = ""
)

@Serializable
data class AgentMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val thinking: String? = null,
    val toolCalls: List<ToolCallRecord> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

enum class MessageRole {
    USER,
    AGENT,
    SYSTEM
}

@Serializable
data class ToolCallRecord(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val result: String? = null,
    val isPendingApproval: Boolean = false
)
