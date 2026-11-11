package cz.magiak.asistent.mcp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import cz.magiak.asistent.BuildConfig
import cz.magiak.asistent.Config
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps one WebSocket to the Cloudflare relay (folder `connector`). Claude's tool calls arrive
 * through it and are answered here, so the phone itself is the MCP server.
 */
object Connector {

    private const val PING_MS = 30_000L

    @Volatile var online = false
        private set

    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    // One at a time: a print must not race a scan on the same printer.
    private val work = Executors.newSingleThreadExecutor()
    private lateinit var tools: Tools
    private var socket: WebSocket? = null
    private var retryMs = 2_000L
    @Volatile private var lastPongAt = 0L

    fun start(context: Context) {
        if (BuildConfig.CONNECTOR_URL.isEmpty() || ::tools.isInitialized) return
        tools = Tools(context.applicationContext)
        connect()
        main.postDelayed(keepAlive, PING_MS)
    }

    private fun connect() {
        socket = client.newWebSocket(Request.Builder().url(BuildConfig.CONNECTOR_URL).build(), listener)
    }

    // The relay answers "pong" without waking up; silence for three rounds means a dead link
    // that OkHttp would otherwise keep for minutes.
    private val keepAlive = object : Runnable {
        override fun run() {
            socket?.takeIf { online }?.let {
                if (System.currentTimeMillis() - lastPongAt > 3 * PING_MS) it.cancel() else it.send("ping")
            }
            main.postDelayed(this, PING_MS)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            main.post {
                if (webSocket !== socket) return@post
                Log.i(Config.TAG, "Connector connected.")
                retryMs = 2_000L
                lastPongAt = System.currentTimeMillis()
                online = true
                webSocket.send(
                    JSONObject().put("jsonrpc", "2.0").put("method", "tools/announce")
                        .put("params", JSONObject().put("tools", tools.list())).toString()
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text == "pong") {
                lastPongAt = System.currentTimeMillis()
                return
            }
            work.execute { webSocket.send(tools.handle(text)) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, reason)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            lost(webSocket, t.message.orEmpty())
    }

    private fun lost(webSocket: WebSocket, why: String) {
        main.post {
            if (webSocket !== socket) return@post
            Log.w(Config.TAG, "Connector disconnected ($why), retrying in ${retryMs / 1000} s.")
            socket = null
            online = false
            main.postDelayed(::connect, retryMs)
            retryMs = (retryMs * 2).coerceAtMost(60_000L)
        }
    }
}
