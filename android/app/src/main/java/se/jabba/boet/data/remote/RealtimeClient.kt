package se.jabba.boet.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

// A single change event pushed by the server.
data class ChangeEvent(val event: String, val entity: String, val data: JsonObject)

enum class ConnState { CONNECTED, CONNECTING, OFFLINE }

// WebSocket client for real-time sync + presence. Auto-reconnects with backoff.
class RealtimeClient(
    private val json: Json,
    private val baseUrlProvider: () -> String,
    private val onChange: (ChangeEvent) -> Unit,
    private val onPresence: (List<PresenceMember>) -> Unit,
    // Fired when the socket re-establishes after a drop (not the first connect of a
    // session). The live stream only carries deltas while connected, so anything the
    // other member changed during the gap was never delivered — the listener should
    // re-pull a full snapshot to resync.
    private val onReconnect: () -> Unit = {},
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var memberId: String? = null
    private var memberName: String? = null
    private var closed = false
    private var backoffMs = 1000L
    // True once this connect() session has opened at least once, so we can tell a
    // genuine reconnect from the initial open.
    private var everConnected = false
    // Last presence we announced, replayed on every (re)open since the server drops
    // a member's presence the instant their socket closes.
    private var lastStatus: String? = null
    private var lastListId: String? = null
    private val reconnectGeneration = AtomicInteger(0)

    private val _state = MutableStateFlow(ConnState.OFFLINE)
    val state: StateFlow<ConnState> = _state

    fun connect(memberId: String, memberName: String) {
        this.memberId = memberId
        this.memberName = memberName
        closed = false
        everConnected = false
        reconnectGeneration.incrementAndGet()
        ws?.cancel()
        open()
    }

    private fun wsUrl(): String {
        val base = baseUrlProvider().trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        return "$base/ws?memberId=${memberId}&name=${memberName}"
    }

    private fun open() {
        if (closed) return
        _state.value = ConnState.CONNECTING
        val req = Request.Builder().url(wsUrl()).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                backoffMs = 1000L
                _state.value = ConnState.CONNECTED
                // Re-announce presence so the server (which dropped it on disconnect)
                // shows us as active again and the shopping/60s-notify timers resume.
                if (lastStatus != null) sendPresence(lastStatus!!, lastListId)
                // On a genuine reconnect, pull a fresh snapshot to recover anything
                // the live stream missed while we were offline.
                if (everConnected) onReconnect() else everConnected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = ConnState.OFFLINE
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = ConnState.OFFLINE
                scheduleReconnect()
            }
        })
    }

    private fun handle(text: String) {
        try {
            val obj = json.parseToJsonElement(text) as? JsonObject ?: return
            when (obj["type"]?.toString()?.trim('"')) {
                "change" -> {
                    val event = obj["event"]?.toString()?.trim('"') ?: return
                    val entity = obj["entity"]?.toString()?.trim('"') ?: return
                    val data = obj["data"] as? JsonObject ?: return
                    onChange(ChangeEvent(event, entity, data))
                }
                "presence" -> {
                    val members = obj["members"]
                    if (members != null) {
                        onPresence(json.decodeFromString(members.toString()))
                    }
                }
            }
        } catch (_: Exception) { /* ignore malformed frame */ }
    }

    private fun scheduleReconnect() {
        if (closed) return
        val delay = backoffMs
        val generation = reconnectGeneration.get()
        backoffMs = (backoffMs * 2).coerceAtMost(15000L)
        Thread {
            Thread.sleep(delay)
            if (!closed && generation == reconnectGeneration.get()) open()
        }.start()
    }

    fun reconnectNow() {
        if (memberId == null || memberName == null) return
        closed = false
        backoffMs = 1000L
        reconnectGeneration.incrementAndGet()
        ws?.cancel()
        open()
    }

    fun sendPresence(status: String, listId: String?) {
        lastStatus = status
        lastListId = listId
        val payload = buildString {
            append("{\"type\":\"presence\",\"status\":\"$status\"")
            append(",\"name\":\"${memberName}\"")
            if (listId != null) append(",\"listId\":\"$listId\"")
            append("}")
        }
        ws?.send(payload)
    }

    fun close() {
        closed = true
        reconnectGeneration.incrementAndGet()
        ws?.close(1000, "bye")
        _state.value = ConnState.OFFLINE
    }
}
