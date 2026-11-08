package com.antigravity.mobile.data.remote

import android.util.Log
import com.antigravity.mobile.data.model.AgentConfig
import com.antigravity.mobile.data.model.InteractionInputContent
import com.antigravity.mobile.data.model.InteractionRequest
import com.antigravity.mobile.data.model.InteractionResponse
import com.antigravity.mobile.data.model.InteractionStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class RemoteInteractionUpdate {
    data class Started(val interactionId: String, val environmentId: String?) : RemoteInteractionUpdate()
    data class StatusChanged(val status: String, val message: String) : RemoteInteractionUpdate()
    data class StepAdded(val step: InteractionStep) : RemoteInteractionUpdate()
    data class OutputChunk(val text: String) : RemoteInteractionUpdate()
    data class Completed(val finalOutput: String, val environmentId: String?, val interactionId: String) : RemoteInteractionUpdate()
    data class Failed(val error: String) : RemoteInteractionUpdate()
}

class InteractionsApiService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
) {
    private val tag = "InteractionsApiService"
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/interactions"

    /**
     * Executes an agent prompt using the Antigravity managed agent
     * and polls for updates in a reactive Flow.
     */
    fun executeInteraction(
        apiKey: String,
        prompt: String,
        modelName: String = "gemini-3.8-flash",
        environmentId: String? = null,
        previousInteractionId: String? = null
    ): Flow<RemoteInteractionUpdate> = flow {
        emit(RemoteInteractionUpdate.StatusChanged("provisioning", "Connecting to Google Cloud Antigravity Agent..."))

        // Build Environment config: Reuse existing environment or create new unrestricted remote sandbox
        val envJson = if (!environmentId.isNullOrBlank()) {
            JsonPrimitive(environmentId)
        } else {
            // New sandbox with unrestricted network access
            buildJsonObject {
                put("type", "remote")
                put("network", buildJsonObject {
                    put("allowlist", buildJsonArray {
                        add(buildJsonObject { put("domain", "*") })
                    })
                })
            }
        }

        val requestPayload = InteractionRequest(
            agent = "antigravity-preview-09-2026",
            input = listOf(InteractionInputContent(type = "text", text = prompt)),
            environment = envJson,
            background = true,
            agentConfig = AgentConfig(type = "antigravity", model = modelName),
            previousInteractionId = previousInteractionId
        )

        val requestBodyString = json.encodeToString(InteractionRequest.serializer(), requestPayload)
        Log.d(tag, "Dispatching interaction request: $requestBodyString")

        val createRequest = Request.Builder()
            .url(baseUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-goog-api-key", apiKey)
            .post(requestBodyString.toRequestBody("application/json".toMediaType()))
            .build()

        val initialResponse: InteractionResponse = withContext(Dispatchers.IO) {
            client.newCall(createRequest).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}: $bodyStr")
                }
                json.decodeFromString(InteractionResponse.serializer(), bodyStr)
            }
        }

        val interactionId = initialResponse.id
        var currentEnvironmentId = initialResponse.environmentId ?: environmentId
        emit(RemoteInteractionUpdate.Started(interactionId, currentEnvironmentId))
        emit(RemoteInteractionUpdate.StatusChanged(initialResponse.status, "Sandbox provisioned ($interactionId). Running autonomous loop..."))

        var seenStepCount = 0
        var isFinished = false
        var pollAttempts = 0
        val maxPollAttempts = 120 // ~4-5 minutes max wait

        while (!isFinished && pollAttempts < maxPollAttempts) {
            delay(2500)
            pollAttempts++

            val pollRequest = Request.Builder()
                .url("$baseUrl/$interactionId")
                .addHeader("x-goog-api-key", apiKey)
                .get()
                .build()

            val currentStatus: InteractionResponse = try {
                withContext(Dispatchers.IO) {
                    client.newCall(pollRequest).execute().use { response ->
                        val bodyStr = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            throw IOException("Poll error HTTP ${response.code}: $bodyStr")
                        }
                        json.decodeFromString(InteractionResponse.serializer(), bodyStr)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Polling attempt $pollAttempts warning: ${e.message}")
                continue
            }

            if (currentStatus.environmentId != null) {
                currentEnvironmentId = currentStatus.environmentId
            }

            // Stream any newly observed steps
            if (currentStatus.steps.size > seenStepCount) {
                for (i in seenStepCount until currentStatus.steps.size) {
                    val step = currentStatus.steps[i]
                    emit(RemoteInteractionUpdate.StepAdded(step))

                    // If it's a thought step, emit as status
                    if (step.type == "thought" && !step.thought.isNullOrBlank()) {
                        emit(RemoteInteractionUpdate.StatusChanged("thinking", step.thought))
                    } else if (step.type == "code_execution_call") {
                        emit(RemoteInteractionUpdate.StatusChanged("executing_code", "Running shell command in container..."))
                    } else if (step.type == "google_search_call" || step.type == "url_context_call") {
                        emit(RemoteInteractionUpdate.StatusChanged("searching", "Searching web / fetching URLs..."))
                    }
                }
                seenStepCount = currentStatus.steps.size
            }

            when (currentStatus.status) {
                "completed" -> {
                    isFinished = true
                    val output = currentStatus.outputText
                        ?: currentStatus.steps.lastOrNull { it.type == "model_output" }
                            ?.content?.joinToString("") { it.text.orEmpty() }
                        ?: "Task finished successfully without text output."

                    emit(RemoteInteractionUpdate.Completed(output, currentEnvironmentId, interactionId))
                }
                "failed" -> {
                    isFinished = true
                    val errorMsg = currentStatus.error?.toString() ?: "Interaction failed with unknown error."
                    emit(RemoteInteractionUpdate.Failed(errorMsg))
                }
                "cancelled" -> {
                    isFinished = true
                    emit(RemoteInteractionUpdate.Failed("Task was cancelled by the server."))
                }
                else -> {
                    emit(RemoteInteractionUpdate.StatusChanged(currentStatus.status, "Agent working in sandbox ($pollAttempts)..."))
                }
            }
        }

        if (!isFinished) {
            emit(RemoteInteractionUpdate.Failed("Interaction timed out while waiting for cloud sandbox completion."))
        }
    }.flowOn(Dispatchers.IO)
}
