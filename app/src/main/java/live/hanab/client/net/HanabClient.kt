package live.hanab.client.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

const val BASE_HOST = "hanab.live"

private class MemoryCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.removeAll { old -> cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path } }
        store.addAll(cookies)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.removeAll { it.expiresAt < now }
        return store.filter { it.matches(url) }
    }
}

enum class ConnState { Disconnected, Connecting, Connected }

/**
 * Talks to hanab.live: POST /login (sets a session cookie), then a WebSocket at /ws.
 * Frames are `command {json}`.
 */
class HanabClient {
    private val http = OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar())
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null

    private val _state = MutableStateFlow(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state

    private val _messages = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 512)
    val messages: SharedFlow<ServerMessage> = _messages

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errors: SharedFlow<String> = _errors

    /** Returns null on success, otherwise a human-readable error. */
    suspend fun login(username: String, password: String): String? = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            // The server rejects stale client versions; "bot" skips that check.
            .add("version", "bot")
            .build()
        val req = Request.Builder().url("https://$BASE_HOST/login").post(body).build()
        try {
            http.newCall(req).execute().use { resp: Response ->
                if (resp.isSuccessful) null
                else resp.body?.string()?.trim().takeUnless { it.isNullOrEmpty() } ?: "Login failed (${resp.code})"
            }
        } catch (e: Exception) {
            "Network error: ${e.message}"
        }
    }

    fun connect() {
        if (_state.value != ConnState.Disconnected) return
        _state.value = ConnState.Connecting
        val req = Request.Builder()
            .url("https://$BASE_HOST/ws")
            .header("Origin", "https://$BASE_HOST")
            .build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = ConnState.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                unpack(text)?.let { _messages.tryEmit(it) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = ConnState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = ConnState.Disconnected
                _errors.tryEmit("Connection lost: ${t.message ?: response?.code}")
            }
        })
    }

    fun send(command: String, data: JsonObject? = null) {
        socket?.send(pack(command, data))
    }

    fun disconnect() {
        socket?.close(1000, null)
        socket = null
        _state.value = ConnState.Disconnected
    }
}
