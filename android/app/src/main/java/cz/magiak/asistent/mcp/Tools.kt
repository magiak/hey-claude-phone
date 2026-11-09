package cz.magiak.asistent.mcp

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import cz.magiak.asistent.Config
import cz.magiak.asistent.CustomScreen
import cz.magiak.asistent.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

/** MCP tools Claude can call from a voice call. A new tool goes into list() and call(); descriptions are Claude's instructions. */
class Tools(private val context: Context) {

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
        .put(
            tool(
                "set_home_screen", "Nastavit obrazovku",
                "Nastaví domovskou obrazovku telefonu, přes který s uživatelem mluvíš. Displej leží na šířku, " +
                    "viditelná plocha je asi ${screenSize()}, čte se z pár metrů: velké písmo a málo věcí. " +
                    "Pošli celý krátký HTML dokument (do 5 000 znaků). Obrazovka zůstane, dokud ji uživatel nechce jinou. " +
                    "Vždy nejdřív zavolej get_home_screen a uprav, co tam je, i když jsou tam původní hodiny: " +
                    "uživatel chce svou obrazovku zachovat a jen ji měnit. Bez předlohy (replace: true) pošli stránku, " +
                    "jen když výslovně chce úplně jinou obrazovku. " +
                    "Stránka se jen zobrazuje a klepnutí nedostane. Pravý horní roh (70 × 70 px) zakrývá ikona nastavení. " +
                    "Živá data: await asistent.json(url) a await asistent.text(url) stáhnou veřejnou adresu přes telefon, " +
                    "bez omezení CORS. Bez klíče fungují třeba api.coingecko.com (kryptoměny) a " +
                    "query1.finance.yahoo.com/v8/finance/chart/TICKER (akcie). Obnovuj nejvýš jednou za minutu " +
                    "a při chybě nech poslední hodnotu s časem, kdy platila. Data z tvých jiných nástrojů telefon sám nestáhne: " +
                    "vlož je jako hodnoty a napiš, k jaké chvíli platí. asistent.lastAnswer() vrátí poslední otázku a odpověď " +
                    "z hovoru jako {question, answer, at} nebo null. Hodiny a datum počítej v JavaScriptu, česky. " +
                    "Barvy současného vzhledu telefonu: ${CustomScreen.palette(context)}, velká čísla patkovým písmem. " +
                    "Obrazovka se ukáže, až hovor skončí. Mluv co nejméně: neopakuj, co uživatel chce, " +
                    "neohlašuj čtení ani zápis a nástroje volej mlčky. Po uložení řekni jen „Hotovo“ a pár slov, " +
                    "kde to je. Kód nečti nahlas.",
                JSONObject()
                    .put("html", JSONObject().put("type", "string"))
                    .put("replace", JSONObject().put("type", "boolean")),
                required = listOf("html"),
            )
        )
        .put(
            tool(
                "get_home_screen", "Přečíst obrazovku",
                "Vrátí HTML současné domovské obrazovky telefonu, abys ji upravil nástrojem set_home_screen. " +
                    "Když jsou na ní původní hodiny, vrátí jejich kopii jako stránku. Volej před každou změnou obrazovky, mlčky.",
                JSONObject(),
            )
        )
        .put(
            tool(
                "reset_home_screen", "Vrátit hodiny",
                "Vrátí na domovskou obrazovku telefonu původní hodiny s poslední odpovědí místo vlastní stránky.",
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
        "set_home_screen" -> setScreen(args.getString("html"), args.optBoolean("replace"))
        "get_home_screen" -> {
            readRevision = CustomScreen.revision
            textResult(
                "Nic uživateli neříkej, uprav stránku a rovnou zavolej set_home_screen.\n\n" + (
                    CustomScreen.load(context)
                        ?: ("Na displeji jsou původní hodiny s poslední odpovědí. Tady je jejich kopie jako stránka, " +
                            "přidej nebo změň v ní, co uživatel chce:\n\n" + CustomScreen.original(context))
                    )
            )
        }
        "reset_home_screen" -> {
            CustomScreen.reset(context)
            textResult("Hodiny se vrátí po konci hovoru. Uživateli řekni jen „Hotovo“.")
        }
        else -> textResult("Neznámý nástroj $name.", error = true)
    }

    // The description alone did not hold: asked for one more thing, Claude rewrote his page from
    // scratch and the clock was gone (2026-11-09). So a set must build on what get returned.
    @Volatile private var readRevision = -1

    // In voice mode every step gets narrated aloud ("now I read it, now I write it"), and he found it
    // tiring (2026-11-10). Results are what Claude reads right before speaking, so they say what to say.
    private fun setScreen(html: String, replace: Boolean): JSONObject {
        if (!replace && readRevision != CustomScreen.revision) return textResult(
            "Neuloženo. Uživateli to neříkej: zavolej get_home_screen, uprav současnou obrazovku a pošli ji znovu. " +
                "replace: true pošli, jen když výslovně chce úplně jinou.",
            error = true,
        )
        if (!html.contains("<")) return textResult("Pošli celý HTML dokument.", error = true)
        if (html.length > MAX_SCREEN_CHARS) return textResult("Stránka je moc dlouhá, zkrať ji pod $MAX_SCREEN_CHARS znaků.", error = true)
        CustomScreen.save(context, html)
        // What Claude just sent is the page now, so more tweaks in the same call need no new read.
        readRevision = CustomScreen.revision
        return textResult("Uloženo, ukáže se po konci hovoru. Uživateli řekni jen „Hotovo“ a pár slov, kde to je, nic víc.")
    }

    // The page fills the holder's window, which hides the notch strip and a thin edge (dimens.xml).
    private fun screenSize(): String {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val res = context.resources
        val edge = res.getDimension(R.dimen.hidden_edge)
        val w = (maxOf(metrics.widthPixels, metrics.heightPixels) - res.getDimension(R.dimen.hidden_start) - edge) / metrics.density
        val h = (minOf(metrics.widthPixels, metrics.heightPixels) - 2 * edge) / metrics.density
        return "${w.roundToInt()} × ${h.roundToInt()} px"
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
        // The description asks for 5 000 (voice mode is silent while it writes); this only stops a runaway page.
        const val MAX_SCREEN_CHARS = 60_000
    }
}
