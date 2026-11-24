package com.droneradar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds an A4 Arabic (right-to-left) PDF from report_summary() data:
 * key figures, incidents per day / hour, per site, top drones, forgery signs, staff activity, incident log.
 */
class ReportPdf(private val ctx: Context, private val j: JSONObject, private val periodLabel: String) {

    private val W = 595; private val H = 842; private val M = 36f
    private val inner = W - 2 * M
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private lateinit var c: Canvas
    private var y = 0f
    private var pageNo = 0

    private val NAVY = Color.rgb(21, 45, 84); private val RED = Color.rgb(211, 47, 47)
    private val PURPLE = Color.rgb(123, 31, 162); private val TEAL = Color.rgb(0, 137, 123)
    private val GREY = Color.rgb(110, 110, 110); private val LIGHT = Color.rgb(240, 243, 247)

    private val isoIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val dt = SimpleDateFormat("d/M HH:mm", Locale.US)
    private fun local(iso: String?): String = runCatching { dt.format(isoIn.parse(iso!!.take(19))!!) }.getOrDefault(iso ?: "")

    fun write(out: File): File {
        newPage()
        header()
        kpis()
        dayChart()
        hourChart()
        siteTable()
        droneTable()
        flagsBlock()
        staffTable()
        incidentLog()
        finishPage()
        out.parentFile?.mkdirs()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    // ------------------------------------------------------------------ page handling
    private fun newPage() {
        finishPage()
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        c = page!!.canvas
        y = M
    }

    private fun finishPage() {
        val p = page ?: return
        text(ctx.getString(R.string.rp_footer, pageNo), M, H - 26f, inner, 8f, color = GREY, center = true)
        doc.finishPage(p)
        page = null
    }

    private fun need(h: Float) { if (y + h > H - 48f) newPage() }

    // ------------------------------------------------------------------ drawing helpers
    /** RTL text block; returns its height. */
    private fun text(s: String, x: Float, top: Float, width: Float, size: Float, bold: Boolean = false,
                     color: Int = Color.BLACK, center: Boolean = false): Float {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val sl = StaticLayout.Builder.obtain(s, 0, s.length, tp, width.toInt().coerceAtLeast(10))
            .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setIncludePad(false)
            .build()
        c.save(); c.translate(x, top); sl.draw(c); c.restore()
        return sl.height.toFloat()
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float, color: Int) {
        c.drawRect(l, t, r, b, Paint().apply { this.color = color })
    }

    private fun section(title: String) {
        need(60f)
        y += 14f
        y += text(title, M, y, inner, 13f, bold = true, color = NAVY) + 4f
        c.drawLine(M, y, W - M, y, Paint().apply { color = NAVY; strokeWidth = 1.2f })
        y += 8f
    }

    /** Right-to-left table: first column is drawn at the right edge. */
    private fun table(headers: List<String>, rows: List<List<String>>, weights: List<Float>, rowColor: (Int) -> Int? = { null }) {
        val total = weights.sum()
        val widths = weights.map { it / total * inner }
        fun drawRow(cells: List<String>, bold: Boolean, bg: Int?, fg: Int = Color.BLACK) {
            val hgt = cells.mapIndexed { i, s -> measure(s, widths[i] - 8f, 9f) }.maxOrNull()!! + 8f
            need(hgt)
            if (bg != null) rect(M, y, W - M, y + hgt, bg)
            var right = W - M
            cells.forEachIndexed { i, s ->
                text(s, right - widths[i] + 4f, y + 4f, widths[i] - 8f, 9f, bold = bold, color = fg)
                right -= widths[i]
            }
            y += hgt
        }
        drawRow(headers, true, NAVY, Color.WHITE)
        rows.forEachIndexed { i, r -> drawRow(r, false, rowColor(i) ?: if (i % 2 == 1) LIGHT else null) }
        y += 4f
    }

    private fun measure(s: String, width: Float, size: Float): Float {
        val tp = TextPaint().apply { textSize = size }
        return StaticLayout.Builder.obtain(s, 0, s.length, tp, width.toInt().coerceAtLeast(10)).setIncludePad(false).build().height.toFloat()
    }

    private fun bars(values: List<Pair<String, Int>>, height: Float, color: Int, labelEvery: Int) {
        need(height + 30f)
        val maxV = (values.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
        val n = values.size.coerceAtLeast(1)
        val slot = inner / n
        val base = y + height
        c.drawLine(M, base, W - M, base, Paint().apply { this.color = GREY; strokeWidth = 0.6f })
        values.forEachIndexed { i, (label, v) ->
            // time runs left → right (as on a calendar strip)
            val left = M + i * slot + slot * 0.15f
            val h = height * v / maxV
            if (v > 0) {
                rect(left, base - h, left + slot * 0.7f, base, color)
                if (n <= 31) text(v.toString(), left - 4f, base - h - 11f, slot * 0.7f + 8f, 7f, color = GREY, center = true)
            }
            if (i % labelEvery == 0) text(label, left - 6f, base + 3f, slot * 0.7f + 12f, 7f, color = GREY, center = true)
        }
        y = base + 18f
    }

    // ------------------------------------------------------------------ sections
    private fun header() {
        rect(0f, 0f, W.toFloat(), 92f, NAVY)
        text(ctx.getString(R.string.rp_title), M, 18f, inner, 20f, bold = true, color = Color.WHITE)
        text("${j.optString("company")}  •  $periodLabel", M, 48f, inner, 11f, color = Color.WHITE)
        val gen = SimpleDateFormat("d/M/yyyy HH:mm", Locale.US).format(Date())
        text(ctx.getString(R.string.rp_generated, gen), M, 68f, inner, 8.5f, color = Color.rgb(200, 210, 230))
        y = 108f
    }

    private fun kpis() {
        val items = listOf(
            ctx.getString(R.string.rp_k_incidents) to j.optInt("incidents"),
            ctx.getString(R.string.rp_k_drones) to j.optInt("drones"),
            ctx.getString(R.string.rp_k_suspicious) to j.optInt("suspicious"),
            ctx.getString(R.string.rp_k_authorized) to j.optInt("authorized"),
            ctx.getString(R.string.rp_k_opknown) to j.optInt("op_known"),
            ctx.getString(R.string.rp_k_sightings) to j.optInt("sightings"),
            ctx.getString(R.string.rp_k_triang) to j.optInt("triangulated"),
            ctx.getString(R.string.rp_k_points) to j.optInt("detections"),
        )
        val cols = 4; val gap = 8f; val bw = (inner - gap * (cols - 1)) / cols; val bh = 54f
        items.forEachIndexed { i, (label, v) ->
            val col = i % cols; val row = i / cols
            val right = W - M - col * (bw + gap)
            val top = y + row * (bh + gap)
            val accent = when (i) { 2 -> PURPLE; 3 -> TEAL; 0 -> RED; else -> NAVY }
            rect(right - bw, top, right, top + bh, LIGHT)
            rect(right - 4f, top, right, top + bh, accent)
            text(v.toString(), right - bw + 6f, top + 6f, bw - 14f, 18f, bold = true, color = accent)
            text(label, right - bw + 6f, top + 32f, bw - 14f, 8.5f, color = GREY)
        }
        y += 2 * bh + gap + 6f
        if (j.optInt("incidents") == 0) {
            y += text(ctx.getString(R.string.rp_none), M, y + 6f, inner, 11f, color = GREY) + 10f
        }
    }

    private fun dayChart() {
        val days = JSONArray(j.optString("by_day", "[]"))
        if (days.length() == 0) return
        section(ctx.getString(R.string.rp_by_day))
        val vals = (0 until days.length()).map { days.getJSONObject(it) }.map {
            it.optString("day").takeLast(5).replace('-', '/') to it.optInt("n")
        }
        bars(vals, 90f, RED, labelEvery = ((vals.size + 9) / 10).coerceAtLeast(1))
    }

    private fun hourChart() {
        val hours = JSONArray(j.optString("by_hour", "[]"))
        if (hours.length() == 0) return
        section(ctx.getString(R.string.rp_by_hour))
        val m = IntArray(24)
        for (i in 0 until hours.length()) hours.getJSONObject(i).let { m[it.optInt("h").coerceIn(0, 23)] = it.optInt("n") }
        bars((0 until 24).map { "%02d".format(Locale.US, it) to m[it] }, 70f, NAVY, labelEvery = 3)
    }

    private fun siteTable() {
        val a = JSONArray(j.optString("by_site", "[]"))
        if (a.length() == 0) return
        section(ctx.getString(R.string.rp_by_site))
        val rows = (0 until a.length()).map { a.getJSONObject(it) }.map {
            listOf(it.optString("site").let { s -> if (s == "—") ctx.getString(R.string.outside_sites) else s },
                it.optInt("n").toString(), it.optInt("sus").toString(), it.optInt("auth").toString())
        }
        table(listOf(ctx.getString(R.string.rp_site), ctx.getString(R.string.rp_k_incidents),
            ctx.getString(R.string.rp_k_suspicious), ctx.getString(R.string.rp_k_authorized)), rows, listOf(3f, 1.2f, 1.2f, 1.2f))
    }

    private fun droneTable() {
        val a = JSONArray(j.optString("top_drones", "[]"))
        if (a.length() == 0) return
        section(ctx.getString(R.string.rp_top_drones))
        val items = (0 until a.length()).map { a.getJSONObject(it) }
        val rows = items.map {
            listOf(it.optString("serial"), it.optInt("n").toString(), local(it.optString("last")), status(it.optBoolean("sus"), it.optBoolean("auth")))
        }
        table(listOf(ctx.getString(R.string.rp_serial), ctx.getString(R.string.rp_times), ctx.getString(R.string.rp_last_seen),
            ctx.getString(R.string.rp_status)), rows, listOf(3f, 1f, 1.6f, 1.6f)) { i ->
            if (items[i].optBoolean("sus")) Color.rgb(243, 229, 245) else null
        }
    }

    private fun status(sus: Boolean, auth: Boolean) = when {
        sus -> ctx.getString(R.string.rp_st_sus)
        auth -> ctx.getString(R.string.rp_st_auth)
        else -> ctx.getString(R.string.rp_st_unknown)
    }

    private fun flagsBlock() {
        val a = JSONArray(j.optString("flags", "[]"))
        if (a.length() == 0) return
        section(ctx.getString(R.string.rp_flags))
        val rows = (0 until a.length()).map { a.getJSONObject(it) }.map {
            listOf(SpoofCheck.describe(ctx, listOf(it.optString("flag"))), it.optInt("n").toString())
        }
        table(listOf(ctx.getString(R.string.rp_reason), ctx.getString(R.string.rp_k_incidents)), rows, listOf(4f, 1f))
        y += text(ctx.getString(R.string.rp_flags_note), M, y, inner, 8f, color = GREY) + 4f
    }

    private fun staffTable() {
        val a = JSONArray(j.optString("staff", "[]"))
        if (a.length() == 0) return
        section(ctx.getString(R.string.rp_staff))
        val rows = (0 until a.length()).map { a.getJSONObject(it) }.map {
            listOf(it.optString("name"), it.optInt("det").toString(), it.optInt("sight").toString())
        }
        table(listOf(ctx.getString(R.string.rp_name), ctx.getString(R.string.rp_k_points), ctx.getString(R.string.rp_k_sightings)), rows, listOf(3f, 1.4f, 1.4f))
    }

    private fun incidentLog() {
        val a = JSONArray(j.optString("list", "[]"))
        if (a.length() == 0) return
        section(ctx.getString(R.string.rp_log, a.length()))
        val items = (0 until a.length()).map { a.getJSONObject(it) }
        val rows = items.map {
            listOf(local(it.optString("started")) + " ← " + local(it.optString("ended")).substringAfter(' '),
                it.optString("site").ifBlank { ctx.getString(R.string.outside_sites) }.let { s -> if (s == "null") ctx.getString(R.string.outside_sites) else s },
                it.optString("serial"),
                status(it.optBoolean("sus"), it.optBoolean("auth")) + if (it.optBoolean("op")) " • " + ctx.getString(R.string.rp_op) else "")
        }
        table(listOf(ctx.getString(R.string.rp_time), ctx.getString(R.string.rp_site), ctx.getString(R.string.rp_serial),
            ctx.getString(R.string.rp_status)), rows, listOf(1.8f, 1.8f, 2.4f, 1.6f)) { i ->
            if (items[i].optBoolean("sus")) Color.rgb(243, 229, 245) else null
        }
    }
}
