package cz.magiak.asistent

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shows CustomScreen's page and gives it `window.asistent` (see SHIM) for live data. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class ScreenView(context: Context, private val onFailed: () -> Unit) : WebView(context) {

    private val main = Handler(Looper.getMainLooper())

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        setBackgroundColor(context.getColor(if (UserSettings.dark(context)) R.color.surface_dark else R.color.surface))
        addJavascriptInterface(Bridge(), "AsistentNative")
        webViewClient = object : WebViewClient() {
            // A link would leave the page and keep the bridge for whatever site it opened.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(Config.TAG, "Custom screen died (crash=${detail.didCrash()}).")
                main.post(onFailed)
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.i(Config.TAG, "Screen: ${message.message()} (line ${message.lineNumber()})")
                return true
            }
        }
    }

    fun show(html: String) = loadDataWithBaseURL(BASE_URL, withBridge(html), "text/html", "utf-8", null)

    // Taps belong to the dashboard (open Claude, hold for settings), never to the page.
    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent) = false

    private inner class Bridge {
        // Fetched natively so the page is not bound by CORS: most price APIs refuse browsers.
        @JavascriptInterface
        fun get(id: String, url: String) {
            val target = url.toHttpUrlOrNull() ?: return reply(id, false, context.getString(R.string.bridge_http_only))
            val request = Request.Builder().url(target).header("User-Agent", USER_AGENT).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = reply(id, false, e.message ?: context.getString(R.string.bridge_network_down))

                override fun onResponse(call: Call, response: Response) = response.use {
                    if (it.isSuccessful) reply(id, true, it.peekBody(MAX_BYTES).string())
                    else reply(id, false, "HTTP ${it.code}")
                }
            })
        }

        @JavascriptInterface
        fun lastAnswer(): String? {
            if (!Config.SHOW_LAST_ANSWER) return null
            val last = LastAnswer.load(context) ?: return null
            return JSONObject().put("question", last.question).put("answer", last.answer).put("at", last.at).toString()
        }
    }

    private fun reply(id: String, ok: Boolean, body: String) {
        main.post { evaluateJavascript("window.__asistentReply(${JSONObject.quote(id)}, $ok, ${JSONObject.quote(body)})", null) }
    }

    // Before the page's own scripts, so they can use window.asistent right away.
    private fun withBridge(html: String): String {
        val at = HEAD.find(html)?.range?.last?.plus(1) ?: DOCTYPE.find(html)?.range?.last?.plus(1) ?: 0
        return html.substring(0, at) + SHIM + html.substring(at)
    }

    private companion object {
        const val BASE_URL = "https://asistent.local/"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10) Asistent"
        const val MAX_BYTES = 1_000_000L
        val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
        val HEAD = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
        val DOCTYPE = Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE)
        const val SHIM = """<script>
window.asistent = (function () {
  var waiting = {}, next = 0;
  window.__asistentReply = function (id, ok, body) {
    var w = waiting[id]; delete waiting[id];
    if (w) { if (ok) w[0](body); else w[1](new Error(body)); }
  };
  function text(url) {
    return new Promise(function (resolve, reject) {
      var id = String(++next); waiting[id] = [resolve, reject];
      AsistentNative.get(id, String(url));
    });
  }
  return {
    text: text,
    json: function (url) { return text(url).then(JSON.parse); },
    lastAnswer: function () { var s = AsistentNative.lastAnswer(); return s ? JSON.parse(s) : null; }
  };
})();
</script>"""
    }
}
