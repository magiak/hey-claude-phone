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
import cz.magiak.asistent.Language
import cz.magiak.asistent.LastAnswer
import cz.magiak.asistent.R
import cz.magiak.asistent.meeting.MeetingArchive
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/** MCP tools Claude can call from a voice call. A new tool goes into list() and call(); descriptions are Claude's instructions. */
class Tools(private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val printer = Printer()
    private val tickets = mutableMapOf<String, Printer.Prepared>()

    init {
        // LastAnswer must skip these titles when it reads the chat; the Claude app shows the announced (localized) text.
        // Every language: the chat keeps titles announced before a language switch.
        LastAnswer.toolTitles = Language.CODES.flatMap { code ->
            val localized = Language.forCode(context, code)
            TITLES.map(localized::getString)
        }.toSet()
    }

    // The phone's language, for what Claude produces on the display itself.
    private val language get() = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)

    fun list(): JSONArray = JSONArray()
        .put(
            tool(
                "set_volume", R.string.tool_set_volume,
                "Sets the volume of the phone you are talking to the user through, including your own voice. " +
                    "level = 0 to 100 %, or step = up / down by a tenth. Without parameters it only reports the current volume.",
                JSONObject()
                    .put("level", JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 100))
                    .put("step", JSONObject().put("type", "string").put("enum", JSONArray().put("up").put("down"))),
            )
        )
        .put(
            tool(
                "prepare_print", R.string.tool_prepare_print,
                "First step of printing on the home printer. Send plain text without Markdown (a recipe, a list, a note). " +
                    "Returns the page count, the printer and ink status and a ticket. Then tell the user the page count and ink status " +
                    "and ask whether to print and how many copies. If any ink is at 0 %, say so and print only on their explicit yes.",
                JSONObject()
                    .put("text", JSONObject().put("type", "string"))
                    .put("title", JSONObject().put("type", "string")),
                required = listOf("text"),
            )
        )
        .put(
            tool(
                "print", R.string.tool_print,
                "Prints the document prepared by prepare_print. Call it only after the user agrees.",
                JSONObject()
                    .put("ticket", JSONObject().put("type", "string"))
                    .put("copies", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 5)),
                required = listOf("ticket"),
            )
        )
        .put(
            tool(
                "scan", R.string.tool_scan,
                "Scans the paper on the home printer's glass and returns an image for you to read. " +
                    "First make sure a paper is lying on the glass. A scan takes up to half a minute.",
                JSONObject(),
            )
        )
        .put(
            tool(
                "set_home_screen", R.string.tool_set_home_screen,
                "Sets the home screen of the phone you are talking to the user through. The display lies in landscape, " +
                    "the visible area is about ${screenSize()}, read from a few metres away: large type and few things. " +
                    "Send one complete short HTML document (up to 5,000 characters). The screen stays until the user wants another one. " +
                    "Always call get_home_screen first and modify what is there, even if it is the original clock: " +
                    "the user wants to keep their screen and only change it. Without a base (replace: true) send a page " +
                    "only when they explicitly want a completely different screen. " +
                    "The page is display-only and receives no taps. The top right corner (70 × 70 px) is covered by the settings icon. " +
                    "Live data: await asistent.json(url) and await asistent.text(url) fetch a public address through the phone, " +
                    "without CORS limits. Without a key, for example api.coingecko.com (crypto) and " +
                    "query1.finance.yahoo.com/v8/finance/chart/TICKER (stocks) work. Refresh at most once a minute " +
                    "and on error keep the last value with the time it was valid. The phone cannot fetch data from your other tools: " +
                    "paste it in as values and write the moment they are valid for. asistent.lastAnswer() returns the last question and answer " +
                    "of the call as {question, answer, at} or null. Compute the clock and date in JavaScript, in $language. " +
                    "Colors of the phone's current look: ${CustomScreen.palette(context)}, big numbers in a serif face. " +
                    "The screen appears once the call ends. Speak as little as possible: do not repeat what the user wants, " +
                    "do not announce reading or writing, and call tools silently. After saving say only \"Done\" and a few words " +
                    "on where it is. Do not read the code aloud.",
                JSONObject()
                    .put("html", JSONObject().put("type", "string"))
                    .put("replace", JSONObject().put("type", "boolean")),
                required = listOf("html"),
            )
        )
        .put(
            tool(
                "get_home_screen", R.string.tool_get_home_screen,
                "Returns the HTML of the phone's current home screen so you can modify it with set_home_screen. " +
                    "If the original clock is on it, returns a copy of it as a page. Call it before every screen change, silently.",
                JSONObject(),
            )
        )
        .put(
            tool(
                "reset_home_screen", R.string.tool_reset_home_screen,
                "Puts the original clock with the last answer back on the phone's home screen in place of the custom page.",
                JSONObject(),
            )
        )
        .put(
            tool(
                "find_meetings", R.string.tool_find_meetings,
                "Meetings recorded on the phone: summaries with decisions and tasks. Without query returns the latest meetings, " +
                    "with query those where the word was said, including transcript lines. It matches exact text, so also try another form of the word. " +
                    "Answer briefly from it, without commenting on the search.",
                JSONObject().put("query", JSONObject().put("type", "string")),
            )
        )
        .put(
            tool(
                "read_meeting", R.string.tool_read_meeting,
                "The full transcript of one meeting by the id from find_meetings. It is long, so call it only when the summary is not enough.",
                JSONObject().put("id", JSONObject().put("type", "string")),
                required = listOf("id"),
            )
        )

    /** One JSON-RPC tools/call in, its JSON-RPC reply out. Failures go back to Claude as text. */
    fun handle(text: String): String {
        val message = JSONObject(text)
        val params = message.optJSONObject("params") ?: JSONObject()
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val result = try {
            Log.i(Config.TAG, "Tool $name $args")
            call(name, args)
        } catch (e: Exception) {
            Log.w(Config.TAG, "Tool $name failed", e)
            textResult("Failed: ${e.message}", error = true)
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
                "Tell the user nothing, modify the page and call set_home_screen right away.\n\n" + (
                    CustomScreen.load(context)
                        ?: ("The display shows the original clock with the last answer. Here is a copy of it as a page, " +
                            "add or change in it what the user wants:\n\n" + CustomScreen.original(context))
                    )
            )
        }
        "reset_home_screen" -> {
            CustomScreen.reset(context)
            textResult("The clock returns when the call ends. Tell the user only \"Done\".")
        }
        "find_meetings" -> textResult(MeetingArchive.find(context, args.optString("query")))
        "read_meeting" -> MeetingArchive.transcript(context, args.getString("id"))?.let { textResult(it) }
            ?: textResult("No meeting with id ${args.getString("id")}, take the id from find_meetings.", error = true)
        else -> textResult("Unknown tool $name.", error = true)
    }

    // The description alone did not hold: asked for one more thing, Claude rewrote his page from
    // scratch and the clock was gone (2026-11-09). So a set must build on what get returned.
    @Volatile private var readRevision = -1

    // In voice mode every step gets narrated aloud ("now I read it, now I write it"), and he found it
    // tiring (2026-11-10). Results are what Claude reads right before speaking, so they say what to say.
    private fun setScreen(html: String, replace: Boolean): JSONObject {
        if (!replace && readRevision != CustomScreen.revision) return textResult(
            "Not saved. Do not tell the user: call get_home_screen, modify the current screen and send it again. " +
                "Send replace: true only when they explicitly want a completely different one.",
            error = true,
        )
        if (!html.contains("<")) return textResult("Send a complete HTML document.", error = true)
        if (html.length > MAX_SCREEN_CHARS) return textResult("The page is too long, shorten it below $MAX_SCREEN_CHARS characters.", error = true)
        CustomScreen.save(context, html)
        // What Claude just sent is the page now, so more tweaks in the same call need no new read.
        readRevision = CustomScreen.revision
        return textResult("Saved, it appears when the call ends. Tell the user only \"Done\" and a few words on where it is, nothing more.")
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
        Log.i(Config.TAG, "Volume $percent % (${audio.getStreamVolume(stream)}/$max)")
        return "The volume is $percent %."
    }

    private fun preparePrint(args: JSONObject): JSONObject {
        val status = printer.status()
        val prepared = printer.prepare(args.getString("text"), args.optString("title").ifBlank { null }, status)
        val ticket = UUID.randomUUID().toString().take(8)
        synchronized(tickets) { tickets[ticket] = prepared }
        return textResult(
            "Pages: ${prepared.pages}. Printer: ${status.state}. Ink: ${status.ink}. Ticket: $ticket. " +
                "Tell the user the page count and ink status and print only after they agree."
        )
    }

    private fun print(args: JSONObject): JSONObject {
        val ticket = args.getString("ticket")
        val prepared = synchronized(tickets) { tickets.remove(ticket) }
            ?: return textResult("Unknown ticket $ticket. Call prepare_print again.", error = true)
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
                .put(JSONObject().put("type", "text").put("text", "Scan of the glass."))
        )
    }

    private fun tool(name: String, title: Int, description: String, properties: JSONObject, required: List<String> = emptyList()) =
        JSONObject().put("name", name).put("title", context.getString(title)).put("description", description).put(
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

        val TITLES = listOf(
            R.string.tool_set_volume, R.string.tool_prepare_print, R.string.tool_print, R.string.tool_scan,
            R.string.tool_set_home_screen, R.string.tool_get_home_screen, R.string.tool_reset_home_screen,
            R.string.tool_find_meetings, R.string.tool_read_meeting,
        )
    }
}
