package com.antigravity.mobile.engine

import android.util.Log
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
 * WebSocket IPC client that bridges the native Android UI
 * to the headless Antigravity daemon running inside PRoot Debian.
 */
class AntigravityBridge(
    private val scope: CoroutineScope,
    private val port: Int = 18080
) {
    private val tag = "AntigravityBridge"

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

    fun connect() {
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
                    handleMessage(text)
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

    private suspend fun handleMessage(text: String) {
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
        val payload = JSONObject().apply {
            put("action", "chat")
            put("prompt", prompt)
        }
        webSocket?.send(payload.toString())
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
