package cz.magiak.asistent.mcp

import android.content.Context
import android.media.AudioManager
import android.util.Base64
import android.util.Log
import cz.magiak.asistent.Config
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

/** MCP tools Claude can call from a voice call. A new tool goes into list() and call(); descriptions are Claude's instructions. */
class Tools(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val printer = Printer()
    private val tickets = mutableMapOf<String, Printer.Prepared>()

    fun list(): JSONArray = JSONArray()
        .put(
            tool(
                "set_volume", "Hlasitost",
                "Nastaví hlasitost telefonu, přes který s uživatelem mluvíš, včetně tvého hlasu. " +
                    "level = 0 až 100 %, nebo step = up / down o desetinu. Bez parametrů jen řekne současnou hlasitost.",
                JSONObject()
                    .put("level", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 100))
                    .put("step", JSONObject().put("type", "string").put("enum", JSONArray().put("up").put("down"))),
            )
        )
        .put(
            tool(
                "prepare_print", "Připravit tisk",
                "První krok tisku na domácí tiskárně. Pošli prostý text bez Markdownu (recept, seznam, poznámku). " +
                    "Vrátí počet stran, stav tiskárny a inkoustu a tiket. Pak uživateli řekni počet stran a stav inkoustu " +
                    "a zeptej se, jestli tisknout a kolik kopií. Když je některý inkoust na 0 %, řekni to a tiskni jen na jeho výslovné ano.",
                JSONObject()
                    .put("text", JSONObject().put("type", "string"))
                    .put("title", JSONObject().put("type", "string")),
                required = listOf("text"),
            )
        )
        .put(
            tool(
                "print", "Vytisknout",
                "Vytiskne dokument připravený nástrojem prepare_print. Volej až po souhlasu uživatele.",
                JSONObject()
                    .put("ticket", JSONObject().put("type", "string"))
                    .put("copies", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 5)),
                required = listOf("ticket"),
            )
        )
        .put(
            tool(
                "scan", "Naskenovat",
                "Naskenuje papír ze skla domácí tiskárny a vrátí obrázek, abys ho přečetl. " +
                    "Nejdřív se ujisti, že na skle papír leží. Sken trvá až půl minuty.",
                JSONObject(),
            )
        )

    /** One JSON-RPC tools/call in, its JSON-RPC reply out. Failures go back to Claude as text. */
    fun handle(text: String): String {
        val message = JSONObject(text)
        val params = message.optJSONObject("params") ?: JSONObject()
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val result = try {
            Log.i(Config.TAG, "Nástroj $name $args")
            call(name, args)
        } catch (e: Exception) {
            Log.w(Config.TAG, "Nástroj $name selhal", e)
            textResult("Nepovedlo se: ${e.message}", error = true)
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", message.opt("id")).put("result", result).toString()
    }

    private fun call(name: String, args: JSONObject): JSONObject = when (name) {
        "set_volume" -> textResult(setVolume(args))
        "prepare_print" -> preparePrint(args)
        "print" -> print(args)
        "scan" -> scan()
        else -> textResult("Neznámý nástroj $name.", error = true)
    }

    // Claude's voice mode plays as USAGE_MEDIA in MODE_NORMAL (dumpsys audio during a call, 2026-11-09).
    private fun setVolume(args: JSONObject): String {
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        val now = audio.getStreamVolume(stream)
        val step = (max * VOLUME_STEP).roundToInt().coerceAtLeast(1)
        val target = when {
            args.has("level") -> args.getInt("level")
            args.optString("step") == "up" -> (now + step) * 100 / max
            args.optString("step") == "down" -> (now - step) * 100 / max
            else -> null
        }
        if (target != null) audio.setStreamVolume(stream, (target.coerceIn(0, 100) * max / 100.0).roundToInt(), 0)
        val percent = audio.getStreamVolume(stream) * 100 / max
        Log.i(Config.TAG, "Hlasitost $percent % (${audio.getStreamVolume(stream)}/$max)")
        return "Hlasitost je $percent %."
    }

    private fun preparePrint(args: JSONObject): JSONObject {
        val status = printer.status()
        val prepared = printer.prepare(args.getString("text"), args.optString("title").ifBlank { null }, status)
        val ticket = UUID.randomUUID().toString().take(8)
        synchronized(tickets) { tickets[ticket] = prepared }
        return textResult(
            "Stran: ${prepared.pages}. Tiskárna: ${status.state}. Inkoust: ${status.ink}. Tiket: $ticket. " +
                "Řekni uživateli počet stran a stav inkoustu a tiskni až po jeho souhlasu."
        )
    }

    private fun print(args: JSONObject): JSONObject {
        val ticket = args.getString("ticket")
        val prepared = synchronized(tickets) { tickets.remove(ticket) }
            ?: return textResult("Tiket $ticket neznám. Zavolej znovu prepare_print.", error = true)
        return textResult(printer.print(prepared, args.optInt("copies", 1).coerceIn(1, 5)))
    }

    private fun scan(): JSONObject {
        val jpeg = printer.scan()
        return JSONObject().put(
            "content", JSONArray()
                .put(
                    JSONObject().put("type", "image").put("mimeType", "image/jpeg")
                        .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                )
                .put(JSONObject().put("type", "text").put("text", "Sken skla."))
        )
    }

    private fun tool(name: String, title: String, description: String, properties: JSONObject, required: List<String> = emptyList()) =
        JSONObject().put("name", name).put("title", title).put("description", description).put(
            "inputSchema", JSONObject().put("type", "object").put("properties", properties)
                .apply { if (required.isNotEmpty()) put("required", JSONArray(required)) }
        )

    private fun textResult(text: String, error: Boolean = false): JSONObject =
        JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .apply { if (error) put("isError", true) }

    private companion object {
        const val VOLUME_STEP = 0.1
    }
}
