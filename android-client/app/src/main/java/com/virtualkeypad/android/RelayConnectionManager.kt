package com.virtualkeypad.android

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

data class RelaySnapshot(
    val serverStatus: String = "Disconnected",
    val roomStatus: String = "Not joined",
    val peerStatus: String = "Not connected",
    val lastEvent: String = "-",
    val roomId: String = "",
    val isConnected: Boolean = false
)

object RelayConnectionManager {
    interface Listener {
        fun onSnapshotChanged(snapshot: RelaySnapshot)
        fun onLog(line: String)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var snapshot = RelaySnapshot()
    private var roomId: String = ""

    fun addListener(listener: Listener) {
        listeners.add(listener)
        mainHandler.post { listener.onSnapshotChanged(snapshot) }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun connect(serverUrl: String, roomId: String) {
        disconnect("reconnect")

        this.roomId = roomId
        updateSnapshot(
            snapshot.copy(
                serverStatus = "Connecting",
                roomStatus = "Joining room",
                peerStatus = "Waiting",
                lastEvent = "-",
                roomId = roomId,
                isConnected = false
            )
        )
        dispatchLog("[connect] $serverUrl room=$roomId")

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                updateSnapshot(snapshot.copy(serverStatus = "Connected", isConnected = true))
                sendJson(
                    JSONObject()
                        .put("type", "join")
                        .put("role", "android")
                        .put("roomId", roomId)
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                dispatchLog("< $text")
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                updateSnapshot(snapshot.copy(serverStatus = "Closing"))
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                this@RelayConnectionManager.webSocket = null
                updateSnapshot(
                    RelaySnapshot(
                        serverStatus = "Disconnected",
                        roomStatus = "Not joined",
                        peerStatus = "Not connected",
                        lastEvent = "-",
                        roomId = this@RelayConnectionManager.roomId
                    )
                )
                dispatchLog("[closed] ${reason.ifBlank { "closed($code)" }}")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this@RelayConnectionManager.webSocket = null
                updateSnapshot(
                    snapshot.copy(
                        serverStatus = "Error",
                        roomStatus = "Not joined",
                        peerStatus = "Not connected",
                        isConnected = false
                    )
                )
                dispatchError(t.message ?: "WebSocket failure")
            }
        })
    }

    fun disconnect(reason: String = "client_disconnect") {
        val socket = webSocket ?: return
        webSocket = null
        socket.close(1000, reason)
        updateSnapshot(
            RelaySnapshot(
                serverStatus = "Disconnected",
                roomStatus = "Not joined",
                peerStatus = "Not connected",
                lastEvent = "-",
                roomId = roomId
            )
        )
        dispatchLog("[disconnect] $reason")
    }

    fun sendKeyDown(key: String) {
        sendKeyDown(
            KeypadButtonState(
                prefKey = "legacy",
                label = key,
                key = key,
                delayMs = 0L
            )
        )
    }

    fun sendKeyDown(button: KeypadButtonState) {
        val keys = KeyComboPickerDialog.parseDisplayValue(button.key)
        updateSnapshot(snapshot.copy(lastEvent = "key_down ${keys.joinToString(" + ")}"))
        keys.forEachIndexed { index, singleKey ->
            mainHandler.postDelayed({
                sendJson(
                    JSONObject()
                        .put("type", "key_down")
                        .put("key", singleKey)
                )
            }, index * button.delayMs)
        }
    }

    fun sendKeyUp(key: String) {
        sendKeyUp(
            KeypadButtonState(
                prefKey = "legacy",
                label = key,
                key = key,
                delayMs = 0L
            )
        )
    }

    fun sendKeyUp(button: KeypadButtonState) {
        val keys = KeyComboPickerDialog.parseDisplayValue(button.key)
        updateSnapshot(snapshot.copy(lastEvent = "key_up ${keys.joinToString(" + ")}"))
        keys.asReversed().forEachIndexed { index, singleKey ->
            mainHandler.postDelayed({
                sendJson(
                    JSONObject()
                        .put("type", "key_up")
                        .put("key", singleKey)
                )
            }, index * button.delayMs)
        }
    }

    fun releaseAll() {
        updateSnapshot(snapshot.copy(lastEvent = "release_all"))
        sendJson(JSONObject().put("type", "release_all"))
    }

    private fun handleServerMessage(message: String) {
        val payload = JSONObject(message)
        when (payload.optString("type")) {
            "joined" -> updateSnapshot(
                snapshot.copy(
                    roomStatus = "Joined ${payload.optString("roomId")}",
                    peerStatus = "Waiting for Windows peer"
                )
            )

            "paired" -> updateSnapshot(
                snapshot.copy(
                    roomStatus = "Joined ${payload.optString("roomId")}",
                    peerStatus = "Windows connected"
                )
            )

            "peer_missing" -> updateSnapshot(snapshot.copy(peerStatus = "Windows not connected"))
            "peer_disconnected" -> updateSnapshot(snapshot.copy(peerStatus = "Windows disconnected"))
            "error" -> dispatchError(payload.optString("message", "Unknown server error"))
        }
    }

    private fun sendJson(payload: JSONObject) {
        val socket = webSocket
        if (socket == null) {
            dispatchError("Not connected")
            return
        }

        val raw = payload.toString()
        dispatchLog("> $raw")
        val sent = socket.send(raw)
        if (!sent) {
            dispatchError("Failed to send message")
        }
    }

    private fun updateSnapshot(next: RelaySnapshot) {
        snapshot = next
        mainHandler.post {
            listeners.forEach { it.onSnapshotChanged(snapshot) }
        }
    }

    private fun dispatchLog(line: String) {
        mainHandler.post {
            listeners.forEach { it.onLog(line) }
        }
    }

    private fun dispatchError(message: String) {
        mainHandler.post {
            listeners.forEach { it.onError(message) }
        }
    }
}
