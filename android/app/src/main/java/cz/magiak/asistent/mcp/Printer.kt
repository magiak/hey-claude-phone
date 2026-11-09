package cz.magiak.asistent.mcp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import cz.magiak.asistent.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** A network printer on the home Wi-Fi, straight from the phone: IPP to print, eSCL (AirScan) to scan. */
class Printer {

    class Status(val code: Int, val state: String, val ink: String, val formats: List<String>)
    class Prepared(val format: String, val documents: List<ByteArray>, val pages: Int)

    // Short connect timeout: an asleep printer should fail fast, not eat Claude's tool budget.
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val host = BuildConfig.PRINTER_HOST
    private val ippUrl = "http://$host:631/ipp/print"

    fun status(): Status {
        val reply = ipp(
            Ipp.GET_PRINTER_ATTRIBUTES,
            listOf(
                Ipp.text(
                    Ipp.KEYWORD, "requested-attributes", "printer-state", "printer-state-reasons",
                    "marker-names", "marker-levels", "document-format-supported",
                )
            ),
        )
        val code = reply.ints("printer-state").firstOrNull() ?: 0
        val reasons = reply.strings("printer-state-reasons").filter { it != "none" }
        val state = (STATES[code] ?: "neznámý stav") + if (reasons.isEmpty()) "" else " (${reasons.joinToString()})"
        val ink = reply.strings("marker-names").zip(reply.ints("marker-levels"))
            .joinToString { (name, level) -> "$name $level %" }.ifEmpty { "tiskárna ho neuvádí" }
        return Status(code, state, ink, reply.strings("document-format-supported"))
    }

    fun prepare(text: String, title: String?, status: Status): Prepared {
        val pages = layout(text, title)
        return when {
            "application/pdf" in status.formats -> Prepared("application/pdf", listOf(pdf(pages)), pages.starts.size)
            // One JPEG is one page, so a longer text becomes several jobs.
            "image/jpeg" in status.formats -> Prepared("image/jpeg", jpegs(pages), pages.starts.size)
            else -> throw IOException("Tiskárna nebere PDF ani JPEG, umí jen: ${status.formats.joinToString()}.")
        }
    }

    fun print(prepared: Prepared, copies: Int): String {
        for (document in prepared.documents) {
            val reply = ipp(
                Ipp.PRINT_JOB,
                listOf(
                    Ipp.text(Ipp.NAME, "job-name", "Asistent"),
                    Ipp.text(Ipp.MIME, "document-format", prepared.format),
                ),
                listOf(Ipp.integer("copies", copies)),
                document,
            )
            if (!reply.ok) throw IOException("Tiskárna úlohu odmítla (IPP 0x%04x).".format(reply.status))
        }
        // An accepted job is not paper yet: wait until the printer prints and goes idle again.
        var printing = false
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(2_000)
            when (status().code) {
                4 -> printing = true
                3 -> if (printing) return "Vytištěno."
            }
        }
        return if (printing) "Tiskárna tiskne." else "Tiskárna úlohu přijala, ale tisknout zatím nezačala."
    }

    fun scan(): ByteArray = reach {
        val base = "http://$host/eSCL"
        http.newCall(Request.Builder().url("$base/ScannerCapabilities").build()).execute().use {
            if (!it.isSuccessful) throw IOException("Tiskárna neumí síťové skenování eSCL (HTTP ${it.code}).")
        }
        val job = http.newCall(
            Request.Builder().url("$base/ScanJobs")
                .post(scanSettings().toRequestBody("text/xml".toMediaType())).build()
        ).execute().use {
            if (it.code != 201) throw IOException("Skener sken odmítl (HTTP ${it.code}).")
            it.header("Location") ?: throw IOException("Skener nevrátil adresu skenu.")
        }
        val document = Request.Builder().url(absolute(base, job).trimEnd('/') + "/NextDocument").build()
        val jpeg = http.newCall(document).execute().use {
            if (!it.isSuccessful) throw IOException("Sken se nepovedl stáhnout (HTTP ${it.code}).")
            it.body!!.bytes()
        }
        shrink(jpeg)
    }

    // The Location header may be absolute or a path on the printer.
    private fun absolute(base: String, location: String) =
        if (location.startsWith("http")) location else base.substringBefore("/eSCL") + location

    private fun scanSettings() = """
        <?xml version="1.0" encoding="UTF-8"?>
        <scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
          <pwg:Version>2.0</pwg:Version>
          <pwg:ScanRegions>
            <pwg:ScanRegion>
              <pwg:Height>3508</pwg:Height>
              <pwg:Width>2480</pwg:Width>
              <pwg:XOffset>0</pwg:XOffset>
              <pwg:YOffset>0</pwg:YOffset>
              <pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
            </pwg:ScanRegion>
          </pwg:ScanRegions>
          <pwg:InputSource>Platen</pwg:InputSource>
          <scan:ColorMode>RGB24</scan:ColorMode>
          <scan:XResolution>$SCAN_DPI</scan:XResolution>
          <scan:YResolution>$SCAN_DPI</scan:YResolution>
          <pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat>
          <scan:DocumentFormatExt>image/jpeg</scan:DocumentFormatExt>
        </scan:ScanSettings>
    """.trimIndent()

    // A tool result is text in a JSON message; a full-size scan would only slow the call down.
    private fun shrink(jpeg: ByteArray): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val scale = SCAN_MAX_PX.toFloat() / max(bitmap.width, bitmap.height)
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        var quality = 80
        while (true) {
            val out = ByteArrayOutputStream()
            sized.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= SCAN_MAX_BYTES || quality <= 50) return out.toByteArray()
            quality -= 10
        }
    }

    private fun ipp(operation: Int, operationAttrs: List<Ipp.Attr>, jobAttrs: List<Ipp.Attr> = emptyList(), document: ByteArray? = null): Ipp.Reply {
        val head = Ipp.encode(
            operation,
            listOf(
                Ipp.text(Ipp.CHARSET, "attributes-charset", "utf-8"),
                Ipp.text(Ipp.LANGUAGE, "attributes-natural-language", "en"),
                Ipp.text(Ipp.URI, "printer-uri", "ipp://$host:631/ipp/print"),
                Ipp.text(Ipp.NAME, "requesting-user-name", "asistent"),
            ) + operationAttrs,
            jobAttrs,
        )
        val body = (head + (document ?: ByteArray(0))).toRequestBody("application/ipp".toMediaType())
        return reach {
            http.newCall(Request.Builder().url(ippUrl).post(body).build()).execute().use {
                if (!it.isSuccessful) throw IOException("Tiskárna odpověděla HTTP ${it.code}.")
                Ipp.decode(it.body!!.bytes())
            }
        }
    }

    // Claude reads this out loud, so an asleep printer must not come back as a socket error.
    private fun <T> reach(block: () -> T): T = try {
        if (host.isEmpty()) throw IOException("Tiskárna není nastavená, chybí printer.host v local.properties.")
        block()
    } catch (e: java.net.ConnectException) {
        throw IOException("Tiskárna na $host neodpovídá. Je zapnutá?")
    } catch (e: java.net.SocketTimeoutException) {
        throw IOException("Tiskárna na $host neodpovídá. Je zapnutá?")
    }

    private class Pages(val layout: StaticLayout, val starts: List<Int>)

    private fun layout(text: String, title: String?): Pages {
        val content = SpannableStringBuilder()
        if (title != null) {
            content.append(title)
            content.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            content.setSpan(RelativeSizeSpan(1.4f), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            content.append("\n\n")
        }
        content.append(text.trim())
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = FONT_PT
        }
        val layout = StaticLayout.Builder.obtain(content, 0, content.length, paint, PAGE_W - 2 * MARGIN).build()
        val starts = mutableListOf(0)
        for (line in 0 until layout.lineCount) {
            if (layout.getLineBottom(line) - layout.getLineTop(starts.last()) > PAGE_H - 2 * MARGIN) starts += line
        }
        return Pages(layout, starts)
    }

    private fun draw(canvas: Canvas, pages: Pages, page: Int) {
        val layout = pages.layout
        val top = layout.getLineTop(pages.starts[page])
        val end = pages.starts.getOrNull(page + 1)
        val bottom = if (end != null) layout.getLineTop(end) else layout.getLineBottom(layout.lineCount - 1)
        canvas.save()
        canvas.translate(MARGIN.toFloat(), (MARGIN - top).toFloat())
        canvas.clipRect(0, top, PAGE_W - 2 * MARGIN, bottom)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun pdf(pages: Pages): ByteArray {
        val document = PdfDocument()
        pages.starts.indices.forEach { i ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, i + 1).create())
            draw(page.canvas, pages, i)
            document.finishPage(page)
        }
        return ByteArrayOutputStream().also { document.writeTo(it); document.close() }.toByteArray()
    }

    private fun jpegs(pages: Pages): List<ByteArray> = pages.starts.indices.map { i ->
        val scale = JPEG_DPI / 72f
        val bitmap = Bitmap.createBitmap((PAGE_W * scale).toInt(), (PAGE_H * scale).toInt(), Bitmap.Config.RGB_565)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            scale(scale, scale)
            draw(this, pages, i)
        }
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            .also { bitmap.recycle() }
    }

    private companion object {
        val STATES = mapOf(3 to "nečinná", 4 to "tiskne", 5 to "zastavená")

        // A4 in PostScript points, 2 cm margins.
        const val PAGE_W = 595
        const val PAGE_H = 842
        const val MARGIN = 57
        const val FONT_PT = 12f
        const val JPEG_DPI = 200

        const val SCAN_DPI = 150
        const val SCAN_MAX_PX = 1600
        const val SCAN_MAX_BYTES = 600_000
    }
}
