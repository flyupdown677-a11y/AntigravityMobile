package com.antigravity.mobile.engine

import android.util.Log
import com.antigravity.mobile.data.model.Account
import com.antigravity.mobile.data.remote.InteractionsApiService
import com.antigravity.mobile.data.remote.RemoteInteractionUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class EngineEvent {
    data class StatusChanged(val status: String, val workspace: String) : EngineEvent()
    data class TokenDelta(val token: String) : EngineEvent()
    data class ThoughtDelta(val thought: String) : EngineEvent()
    data class ToolCall(val callId: String, val toolName: String, val argsJson: String) : EngineEvent()
    data class TurnFinished(val fullResponse: String) : EngineEvent()
    data class ErrorOccurred(val message: String) : EngineEvent()
}

/**
 * Dual-Mode Engine Bridge:
 * 1. Cloud-Native Mode (Google GenAI Interactions API `antigravity-preview-09-2026`) - Default & Recommended.
 * 2. Local Container Mode (Headless PRoot Debian daemon over WebSocket).
 */
class AntigravityBridge(
    private val scope: CoroutineScope,
    private val port: Int = 18080
) {
    private val tag = "AntigravityBridge"

    private val remoteApiService = InteractionsApiService()

    // Active session configuration
    var activeAccount: Account? = null

    private val _activeEnvironmentId = MutableStateFlow<String?>(null)
    val activeEnvironmentId: StateFlow<String?> = _activeEnvironmentId.asStateFlow()

    private val _lastInteractionId = MutableStateFlow<String?>(null)
    val lastInteractionId: StateFlow<String?> = _lastInteractionId.asStateFlow()

    private val _activeModel = MutableStateFlow("gemini-3.8-flash")
    val activeModel: StateFlow<String> = _activeModel.asStateFlow()

    private val _useCloudApi = MutableStateFlow(true)
    val useCloudApi: StateFlow<Boolean> = _useCloudApi.asStateFlow()

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _engineEvents = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    val engineEvents: SharedFlow<EngineEvent> = _engineEvents.asSharedFlow()

    private val currentTurnAccumulator = StringBuilder()

    init {
        // Cloud API is ready by default
        _isConnected.value = true
    }

    fun setModel(modelName: String) {
        _activeModel.value = modelName
    }

    fun setUseCloudApi(enable: Boolean) {
        _useCloudApi.value = enable
    }

    fun resetRemoteEnvironment() {
        _activeEnvironmentId.value = null
        _lastInteractionId.value = null
        scope.launch {
            _engineEvents.emit(EngineEvent.StatusChanged("reset", "Cloud Sandbox Environment reset. Next prompt will provision a fresh container."))
        }
    }

    fun connect() {
        if (_useCloudApi.value) {
            _isConnected.value = true
            return
        }

        val request = Request.Builder()
            .url("ws://127.0.0.1:$port/ws")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(tag, "Connected to Antigravity internal container daemon.")
                _isConnected.value = true
            }

            override fun onMessage(ws: WebSocket, text: String) {
                scope.launch(Dispatchers.Default) {
                    handleLocalMessage(text)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(tag, "WebSocket connection failed: ${t.message}")
                _isConnected.value = false
                scope.launch {
                    _engineEvents.emit(EngineEvent.ErrorOccurred(t.message ?: "Connection error"))
                }
            }
        })
    }

    private suspend fun handleLocalMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "status" -> {
                    _engineEvents.emit(
                        EngineEvent.StatusChanged(
                            status = json.optString("status"),
                            workspace = json.optString("workspace")
                        )
                    )
                }
                "turn_start" -> {
                    currentTurnAccumulator.clear()
                }
                "token" -> {
                    val token = json.optString("data")
                    currentTurnAccumulator.append(token)
                    _engineEvents.emit(EngineEvent.TokenDelta(token))
                }
                "thought" -> {
                    val thought = json.optString("data")
                    _engineEvents.emit(EngineEvent.ThoughtDelta(thought))
                }
                "tool_call" -> {
                    val callId = json.optString("call_id")
                    val toolName = json.optString("tool")
                    val args = json.optJSONObject("args")?.toString() ?: "{}"
                    _engineEvents.emit(EngineEvent.ToolCall(callId, toolName, args))
                }
                "turn_end" -> {
                    _engineEvents.emit(EngineEvent.TurnFinished(currentTurnAccumulator.toString()))
                }
                "error" -> {
                    _engineEvents.emit(EngineEvent.ErrorOccurred(json.optString("message")))
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse message from container", e)
        }
    }

    fun sendChatPrompt(prompt: String) {
        val account = activeAccount
        if (account == null || account.apiKey.isBlank()) {
            scope.launch {
                _engineEvents.emit(EngineEvent.ErrorOccurred("No Gemini API Key configured. Please select or add an account."))
            }
            return
        }

        if (_useCloudApi.value) {
            // Dispatch via Google GenAI Cloud Interactions API
            scope.launch {
                try {
                    remoteApiService.executeInteraction(
                        apiKey = account.apiKey,
                        prompt = prompt,
                        modelName = _activeModel.value,
                        environmentId = _activeEnvironmentId.value,
                        previousInteractionId = _lastInteractionId.value
                    ).collect { update ->
                        when (update) {
                            is RemoteInteractionUpdate.Started -> {
                                _lastInteractionId.value = update.interactionId
                                if (update.environmentId != null) {
                                    _activeEnvironmentId.value = update.environmentId
                                }
                            }
                            is RemoteInteractionUpdate.StatusChanged -> {
                                _engineEvents.emit(
                                    EngineEvent.StatusChanged(
                                        status = update.status,
                                        workspace = _activeEnvironmentId.value ?: "Cloud Sandbox"
                                    )
                                )
                            }
                            is RemoteInteractionUpdate.StepAdded -> {
                                val step = update.step
                                when (step.type) {
                                    "thought" -> {
                                        step.thought?.let { _engineEvents.emit(EngineEvent.ThoughtDelta(it)) }
                                    }
                                    "code_execution_call" -> {
                                        _engineEvents.emit(
                                            EngineEvent.ToolCall(
                                                callId = step.id ?: "exec",
                                                toolName = "code_execution",
                                                argsJson = step.code ?: "{}"
                                            )
                                        )
                                    }
                                    "google_search_call" -> {
                                        _engineEvents.emit(
                                            EngineEvent.ToolCall(
                                                callId = step.id ?: "search",
                                                toolName = "google_search",
                                                argsJson = step.arguments?.toString() ?: "{}"
                                            )
                                        )
                                    }
                                    "url_context_call" -> {
                                        _engineEvents.emit(
                                            EngineEvent.ToolCall(
                                                callId = step.id ?: "url",
                                                toolName = "url_context",
                                                argsJson = step.arguments?.toString() ?: "{}"
                                            )
                                        )
                                    }
                                }
                            }
                            is RemoteInteractionUpdate.OutputChunk -> {
                                _engineEvents.emit(EngineEvent.TokenDelta(update.text))
                            }
                            is RemoteInteractionUpdate.Completed -> {
                                _lastInteractionId.value = update.interactionId
                                if (update.environmentId != null) {
                                    _activeEnvironmentId.value = update.environmentId
                                }
                                _engineEvents.emit(EngineEvent.TurnFinished(update.finalOutput))
                            }
                            is RemoteInteractionUpdate.Failed -> {
                                _engineEvents.emit(EngineEvent.ErrorOccurred(update.error))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Cloud interaction execution failed", e)
                    _engineEvents.emit(EngineEvent.ErrorOccurred(e.message ?: "Failed to execute interaction"))
                }
            }
        } else {
            // Local Daemon WebSocket Mode
            val payload = JSONObject().apply {
                put("action", "chat")
                put("prompt", prompt)
            }
            webSocket?.send(payload.toString())
        }
    }

    fun approveTool(callId: String, approved: Boolean) {
        val payload = JSONObject().apply {
            put("action", "approve_tool")
            put("call_id", callId)
            put("approved", approved)
        }
        webSocket?.send(payload.toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        _isConnected.value = false
    }
}
