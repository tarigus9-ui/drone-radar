package com.droneradar

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.droneradar.map.RadarMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Incident log: every drone episode the user may see, with its flight path on the map. */
class HistoryActivity : AppCompatActivity() {

    private var map: RadarMap? = null
    private lateinit var listBox: LinearLayout
    private lateinit var info: TextView
    private val pad by lazy { (12 * resources.displayMetrics.density).toInt() }
    private var days = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Backend.init(this)
        title = getString(R.string.history_title)

        val mapBox = FrameLayout(this)
        info = TextView(this).apply { textSize = 13f; setPadding(pad, pad / 2, pad, pad / 2) }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, 0, pad, pad) }

        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            listOf(1 to R.string.f_day, 7 to R.string.f_week, 30 to R.string.f_month).forEach { (d, label) ->
                addView(Button(context, null, android.R.attr.buttonBarButtonStyle).apply {
                    text = getString(label); setOnClickListener { days = d; load() }
                })
            }
            addView(Button(context, null, android.R.attr.buttonBarButtonStyle).apply {
                text = getString(R.string.rp_button)
                setOnClickListener { startActivity(android.content.Intent(this@HistoryActivity, ReportActivity::class.java)) }
            })
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            addView(mapBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(filters)
            addView(info)
            addView(ScrollView(context).apply { addView(listBox) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        RadarMap.create(this, mapBox) { m -> map = m; m.onStart(); m.onResume() }
        load()
    }

    override fun onStart() { super.onStart(); map?.onStart() }
    override fun onResume() { super.onResume(); map?.onResume() }
    override fun onPause() { map?.onPause(); super.onPause() }
    override fun onStop() { map?.onStop(); super.onStop() }
    override fun onDestroy() { map?.onDestroy(); super.onDestroy() }

    private fun load(): kotlinx.coroutines.Job = lifecycleScope.launch {
        info.text = getString(R.string.loading)
        val since = iso(System.currentTimeMillis() - days * 86_400_000L)
        val (code, body) = withContext(Dispatchers.IO) {
            Backend.call("POST", "/rest/v1/rpc/incidents", JSONObject().put("p_since", since).toString())
        }
        listBox.removeAllViews()
        if (code !in 200..299) { info.text = Backend.errorText(code, body); return@launch }
        val rows = JSONArray(body)
        info.text = getString(R.string.history_count, rows.length())
        val all = mutableListOf<Pair<Double, Double>>()
        map?.clear(L_LAST)
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            listBox.addView(row(r))
            val lat = r.optDouble("last_lat"); val lon = r.optDouble("last_lon")
            if (!lat.isNaN() && !lon.isNaN()) {
                all += lat to lon
                map?.addDot(L_LAST, lat, lon, Color.rgb(229, 57, 53), 14, r.optString("drone"), r.optString("site", ""))
            }
        }
        // "I saw a drone" reports in the same period
        val (sc, sb) = withContext(Dispatchers.IO) {
            Backend.call("POST", "/rest/v1/rpc/sightings_since", JSONObject().put("p_since", since).toString())
        }
        if (sc in 200..299) {
            val sl = JSONArray(sb)
            if (sl.length() > 0) {
                listBox.addView(TextView(this@HistoryActivity).apply {
                    text = getString(R.string.sec_sightings) + " (${sl.length()})"; textSize = 17f
                    setPadding(0, pad, 0, 0)
                })
                for (i in 0 until sl.length()) listBox.addView(sightingRow(sl.getJSONObject(i)))
            }
        }
        map?.fit(all)
    }

    private fun sightingRow(r: JSONObject): View {
        val br = r.optDouble("bearing")
        val fix = if (r.isNull("est_lat")) "" else "\n" + getString(R.string.est_position)
        return TextView(this).apply {
            text = "👁 ${r.str("reporter") ?: ""} • ${r.str("site") ?: getString(R.string.outside_sites)}\n" +
                "🧭 ${br.toInt()}° ${Compass.dirName(this@HistoryActivity, br.toFloat())} • ⬆ ${r.optDouble("elevation", 0.0).toInt()}° • 🕒 ${local(r.optString("created_at"))}" +
                (r.str("note")?.let { "\n📝 $it" } ?: "") + fix +
                (if (r.str("photo_path") != null) "\n" + getString(R.string.show_photo) else "")
            textSize = 14f
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0x1AFF6F00)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = pad / 2 }
            setOnClickListener { showSighting(r) }
        }
    }

    private fun showSighting(r: JSONObject): kotlinx.coroutines.Job = lifecycleScope.launch {
        val m = map
        val lat = r.optDouble("lat"); val lon = r.optDouble("lon"); val br = r.optDouble("bearing")
        if (m != null && !lat.isNaN() && !lon.isNaN()) {
            m.clear(L_LAST); m.clear(L_TRACK)
            val d = 1500.0
            val eLat = lat + d * Math.cos(Math.toRadians(br)) / 111_320.0
            val eLon = lon + d * Math.sin(Math.toRadians(br)) / (111_320.0 * Math.cos(Math.toRadians(lat)))
            m.addLine(L_TRACK, listOf(lat to lon, eLat to eLon), Color.rgb(255, 111, 0), 3f, dashed = true)
            m.addDot(L_TRACK, lat, lon, Color.rgb(255, 111, 0), 16, getString(R.string.sighting_title, r.optString("reporter")))
            val pts = mutableListOf(lat to lon, eLat to eLon)
            val fLat = r.optDouble("est_lat"); val fLon = r.optDouble("est_lon")
            if (!fLat.isNaN() && !fLon.isNaN()) {
                m.addDot(L_TRACK, fLat, fLon, Color.rgb(156, 39, 176), 22, getString(R.string.est_position), getString(R.string.est_hint))
                pts += fLat to fLon
            }
            m.fit(pts)
        }
        val path = r.str("photo_path") ?: return@launch
        val bytes = withContext(Dispatchers.IO) { Backend.download("sightings/$path") }
        val bmp = bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        androidx.appcompat.app.AlertDialog.Builder(this@HistoryActivity)
            .setTitle(getString(R.string.sighting_title, r.optString("reporter")))
            .apply {
                if (bmp != null) setView(android.widget.ImageView(this@HistoryActivity).apply {
                    setImageBitmap(bmp); adjustViewBounds = true
                }) else setMessage(R.string.no_photo)
            }
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun row(r: JSONObject): View {
        val serial = r.str("serial") ?: "⛔ " + getString(R.string.no_id_addr, r.optString("drone"))
        val site = r.str("site") ?: getString(R.string.outside_sites)
        val start = local(r.optString("started")); val end = local(r.optString("ended"))
        val h = if (r.isNull("max_height")) "—" else "%.0f".format(Locale.US, r.optDouble("max_height"))
        val op = if (r.isNull("op_lat")) getString(R.string.op_unknown) else getString(R.string.op_known)
        val auth = if (r.optBoolean("authorized")) "  " + getString(R.string.authorized) else ""
        val flags = SpoofCheck.fromJson(r.optJSONArray("spoof_flags"))
        val spoofLine = if (flags.isEmpty()) "" else "\n⚠️ " + SpoofCheck.describe(this, flags)
        return TextView(this).apply {
            text = "🚁 $serial$auth\n🏢 $site • ${r.str("region") ?: ""}\n🕒 $start ← $end\n" +
                "👁 ${r.str("reporters") ?: ""} • ${r.optInt("points")} ${getString(R.string.points)} • ⬆ $h m • $op" + spoofLine
            textSize = 14f
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0x12000000)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = pad / 2 }
            setOnClickListener { showTrack(r) }
        }
    }

    private fun showTrack(r: JSONObject): kotlinx.coroutines.Job = lifecycleScope.launch {
        val m = map ?: return@launch
        val args = JSONObject().put("p_drone", r.optString("drone"))
            .put("p_from", r.optString("started")).put("p_to", r.optString("ended"))
        val (code, body) = withContext(Dispatchers.IO) { Backend.call("POST", "/rest/v1/rpc/incident_track", args.toString()) }
        if (code !in 200..299) { info.text = Backend.errorText(code, body); return@launch }
        val pts = JSONArray(body)
        val drone = mutableListOf<Pair<Double, Double>>()
        val op = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until pts.length()) {
            val p = pts.getJSONObject(i)
            val la = p.optDouble("lat"); val lo = p.optDouble("lon")
            if (!la.isNaN() && !lo.isNaN()) drone += la to lo
            val ola = p.optDouble("op_lat"); val olo = p.optDouble("op_lon")
            if (!ola.isNaN() && !olo.isNaN()) op += ola to olo
        }
        m.clear(L_LAST); m.clear(L_TRACK)
        m.addLine(L_TRACK, drone, Color.rgb(229, 57, 53), 3f)
        m.addLine(L_TRACK, op, Color.rgb(0, 200, 83), 2f, dashed = true)
        drone.firstOrNull()?.let { m.addDot(L_TRACK, it.first, it.second, Color.WHITE, 14, getString(R.string.track_start)) }
        drone.lastOrNull()?.let { m.addDot(L_TRACK, it.first, it.second, Color.rgb(229, 57, 53), 20, getString(R.string.track_end), r.optString("drone")) }
        op.lastOrNull()?.let { m.addDot(L_TRACK, it.first, it.second, Color.rgb(0, 200, 83), 18, getString(R.string.operator), r.str("operator_id") ?: "") }
        m.fit(drone + op)
        info.text = getString(R.string.track_info, r.str("serial") ?: getString(R.string.no_id_addr, r.optString("drone")), drone.size)
    }

    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun local(isoStr: String): String = runCatching {
        val d = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(isoStr.take(19))!!
        SimpleDateFormat("d/M HH:mm", Locale.US).format(d)
    }.getOrDefault(isoStr)

    private fun JSONObject.str(k: String): String? = if (isNull(k) || !has(k)) null else optString(k, "").ifBlank { null }

    companion object {
        private const val L_LAST = "last"
        private const val L_TRACK = "track"
    }
}
