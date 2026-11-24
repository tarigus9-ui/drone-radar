package com.droneradar

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Company admin: countries/regions, sites (with alert radius) and staff roles. */
class AdminActivity : AppCompatActivity() {

    private data class Region(val id: String, val name: String, val country: String?)
    private data class Site(val id: String, val name: String, val region: String?, val lat: Double, val lon: Double, val radius: Int)

    private var regions = listOf<Region>()
    private var sites = listOf<Site>()

    private lateinit var regionList: LinearLayout
    private lateinit var siteList: LinearLayout
    private lateinit var staffList: LinearLayout
    private lateinit var wlList: LinearLayout
    private lateinit var info: TextView
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Backend.init(this)
        title = getString(R.string.admin_title)

        info = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, pad) }
        regionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        siteList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        staffList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wlList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply { text = getString(R.string.admin_title); textSize = 22f })
            addView(info)
            addView(section(getString(R.string.sec_regions), getString(R.string.add_region)) { addRegionDialog() })
            addView(regionList)
            addView(section(getString(R.string.sec_sites), getString(R.string.add_site)) { addSiteDialog() })
            addView(siteList)
            addView(section(getString(R.string.sec_staff), getString(R.string.add_staff)) { assignDialog() })
            addView(TextView(context).apply { text = getString(R.string.staff_hint); textSize = 12f })
            addView(staffList)
            addView(section(getString(R.string.sec_whitelist), getString(R.string.add_whitelist)) { addWhitelistDialog() })
            addView(TextView(context).apply { text = getString(R.string.whitelist_hint); textSize = 12f })
            addView(wlList)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        reload()
    }

    private fun section(title: String, action: String, onAdd: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, pad, 0, pad / 3)
        addView(TextView(context).apply {
            text = title; textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(Button(context).apply { text = action; setOnClickListener { onAdd() } })
    }

    private fun row(text: String, onLong: (() -> Unit)? = null) = TextView(this).apply {
        this.text = text; textSize = 15f
        setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        setBackgroundColor(0x0F000000)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = pad / 4 }
        if (onLong != null) setOnLongClickListener { onLong(); true }
    }

    // ------------------------------------------------------------------ load
    private fun reload(): kotlinx.coroutines.Job = lifecycleScope.launch {
        info.text = getString(R.string.loading)
        val (rc, rb) = io { Backend.call("GET", "/rest/v1/regions?select=id,name,country_code&order=name") }
        val (sc, sb) = io { Backend.call("POST", "/rest/v1/rpc/list_sites", "{}") }
        val (tc, tb) = io { Backend.call("POST", "/rest/v1/rpc/list_staff", "{}") }
        if (rc !in 200..299 || sc !in 200..299 || tc !in 200..299) {
            info.text = Backend.errorText(listOf(rc, sc, tc).first { it !in 200..299 }, rb + sb + tb); return@launch
        }
        val who = Backend.who
        info.text = getString(R.string.admin_info, who?.company ?: "", who?.name ?: "")

        regions = JSONArray(rb).objects().map { Region(it.getString("id"), it.getString("name"), it.str("country_code")) }
        regionList.removeAllViews()
        if (regions.isEmpty()) regionList.addView(row(getString(R.string.none_yet)))
        regions.forEach { r -> regionList.addView(row("🌍 ${r.name}  ${r.country ?: ""}")) }

        sites = JSONArray(sb).objects().map {
            Site(it.getString("id"), it.getString("name"), it.str("region"),
                it.optDouble("lat"), it.optDouble("lon"), it.optInt("radius_m"))
        }
        siteList.removeAllViews()
        if (sites.isEmpty()) siteList.addView(row(getString(R.string.none_yet)))
        sites.forEach { s ->
            siteList.addView(row("🏢 ${s.name}\n${s.region ?: "—"} • ${s.radius} م • %.5f, %.5f".format(Locale.US, s.lat, s.lon)) {
                confirm(getString(R.string.delete_site_q, s.name)) {
                    rpc("admin_delete_site", JSONObject().put("p_site_id", s.id))
                }
            })
        }

        staffList.removeAllViews()
        val staff = JSONArray(tb).objects()
        if (staff.isEmpty()) staffList.addView(row(getString(R.string.none_yet)))
        staff.forEach { p ->
            val email = p.getString("email")
            val seen = p.str("last_seen")?.let { ago(it) } ?: getString(R.string.never_seen)
            val bat = if (p.isNull("battery")) "" else " • 🔋${p.optInt("battery")}%"
            staffList.addView(row("${roleIcon(p.str("role"))} ${p.str("full_name") ?: email}  (${roleName(p.str("role"))})\n" +
                "$email\n${p.str("site") ?: p.str("region") ?: getString(R.string.all_company)} • $seen$bat") {
                confirm(getString(R.string.remove_staff_q, email)) {
                    rpc("admin_unassign", JSONObject().put("p_email", email))
                }
            })
        }

        val (wc, wb) = io { Backend.call("POST", "/rest/v1/rpc/list_whitelist", "{}") }
        wlList.removeAllViews()
        if (wc in 200..299) {
            val items = JSONArray(wb).objects()
            if (items.isEmpty()) wlList.addView(row(getString(R.string.none_yet)))
            items.forEach { w ->
                val until = when {
                    !w.optBoolean("active", true) -> getString(R.string.expired)
                    w.str("valid_until") == null -> getString(R.string.permanent)
                    else -> getString(R.string.until, w.str("valid_until")!!.take(10))
                }
                val serial = w.getString("serial")
                wlList.addView(row("✅ $serial\n${w.str("label") ?: ""} • ${w.str("site") ?: getString(R.string.all_sites)} • $until") {
                    confirm(getString(R.string.delete_wl_q, serial)) {
                        rpc("remove_whitelist", JSONObject().put("p_id", w.getLong("id")))
                    }
                })
            }
            lifecycleScope.launch(Dispatchers.IO) { runCatching { Whitelist.refresh(this@AdminActivity) } }
        }
    }

    private fun addWhitelistDialog() {
        val serial = edit(getString(R.string.wl_serial)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val desc = edit(getString(R.string.wl_label))
        val site = spinner(listOf(getString(R.string.all_sites)) + sites.map { it.name })
        val days = edit(getString(R.string.wl_days), number = true)
        form(getString(R.string.add_whitelist), serial, desc, label(R.string.site), site, days) {
            if (serial.text.isBlank()) { toast(getString(R.string.wl_serial)); return@form }
            val o = JSONObject().put("p_serial", serial.text.toString().trim()).put("p_label", desc.text.toString().trim())
            if (site.selectedItemPosition > 0) o.put("p_site_id", sites[site.selectedItemPosition - 1].id)
            days.text.toString().toIntOrNull()?.let { o.put("p_valid_days", it) }
            rpc("add_whitelist", o)
        }
    }

    // ------------------------------------------------------------------ dialogs
    private fun addRegionDialog() {
        val name = edit(getString(R.string.region_name))
        val country = edit(getString(R.string.country_code)).apply { setText("AE") }
        val tz = edit(getString(R.string.timezone)).apply { setText("Asia/Dubai") }
        form(getString(R.string.add_region), name, country, tz) {
            if (name.text.isBlank()) return@form
            rpc("admin_add_region", JSONObject().put("p_name", name.text.toString().trim())
                .put("p_country", country.text.toString().trim()).put("p_timezone", tz.text.toString().trim()))
        }
    }

    private fun addSiteDialog() {
        if (regions.isEmpty()) { toast(getString(R.string.need_region_first)); return }
        val loc = Backend.myLocation
        val name = edit(getString(R.string.site_name))
        val region = spinner(regions.map { it.name })
        val lat = edit(getString(R.string.lat), number = true).apply { setText(loc?.latitude?.let { "%.6f".format(Locale.US, it) } ?: "") }
        val lon = edit(getString(R.string.lon), number = true).apply { setText(loc?.longitude?.let { "%.6f".format(Locale.US, it) } ?: "") }
        val radius = edit(getString(R.string.radius), number = true).apply { setText("500") }
        val hint = TextView(this).apply { text = getString(R.string.site_loc_hint); textSize = 12f }
        form(getString(R.string.add_site), name, region, lat, lon, radius, hint) {
            val la = lat.text.toString().toDoubleOrNull(); val lo = lon.text.toString().toDoubleOrNull()
            if (name.text.isBlank() || la == null || lo == null) { toast(getString(R.string.fill_fields_site)); return@form }
            rpc("admin_add_site", JSONObject().put("p_name", name.text.toString().trim())
                .put("p_region_id", regions[region.selectedItemPosition].id)
                .put("p_lat", la).put("p_lon", lo)
                .put("p_radius_m", radius.text.toString().toIntOrNull() ?: 500))
        }
    }

    private val roles = listOf("guard", "site_manager", "region_manager", "admin")

    private fun assignDialog() {
        val email = edit(getString(R.string.email)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val name = edit(getString(R.string.full_name))
        val role = spinner(roles.map { roleName(it) })
        val siteNames = listOf(getString(R.string.no_site)) + sites.map { "${it.name} (${it.region ?: "—"})" }
        val site = spinner(siteNames)
        val regionNames = listOf(getString(R.string.no_region)) + regions.map { it.name }
        val region = spinner(regionNames)
        val hint = TextView(this).apply { text = getString(R.string.assign_hint); textSize = 12f }
        form(getString(R.string.add_staff), email, name, label(R.string.role), role, label(R.string.site), site,
            label(R.string.region_for_manager), region, hint) {
            if (email.text.isBlank()) return@form
            val o = JSONObject().put("p_email", email.text.toString().trim())
                .put("p_role", roles[role.selectedItemPosition])
                .put("p_full_name", name.text.toString().trim())
            if (site.selectedItemPosition > 0) o.put("p_site_id", sites[site.selectedItemPosition - 1].id)
            if (region.selectedItemPosition > 0) o.put("p_region_id", regions[region.selectedItemPosition - 1].id)
            rpc("admin_assign", o)
        }
    }

    // ------------------------------------------------------------------ helpers
    private fun rpc(fn: String, args: JSONObject): kotlinx.coroutines.Job = lifecycleScope.launch {
        val (code, body) = io { Backend.call("POST", "/rest/v1/rpc/$fn", args.toString()) }
        if (code in 200..299) { toast(getString(R.string.saved)); reload() } else toast(Backend.errorText(code, body))
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun form(title: String, vararg views: View, onOk: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(pad, pad / 2, pad, 0)
            views.forEach { addView(it) }
        }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.save) { _, _ -> onOk() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun confirm(msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton(R.string.delete) { _, _ -> onYes() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun edit(hint: String, number: Boolean = false) = EditText(this).apply {
        this.hint = hint
        if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@AdminActivity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun label(res: Int) = TextView(this).apply { text = getString(res); textSize = 12f; setPadding(0, pad / 2, 0, 0) }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun roleName(r: String?) = when (r) {
        "admin" -> getString(R.string.role_admin)
        "region_manager" -> getString(R.string.role_region)
        "site_manager" -> getString(R.string.role_site)
        else -> getString(R.string.role_guard)
    }

    private fun roleIcon(r: String?) = when (r) { "admin" -> "👔"; "region_manager" -> "🌍"; "site_manager" -> "🏢"; else -> "🛡️" }

    private fun ago(iso: String): String {
        val t = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(iso.take(19))!!.time
        }.getOrNull() ?: return iso
        val min = (System.currentTimeMillis() - t) / 60_000
        return when {
            min < 2 -> getString(R.string.online_now)
            min < 60 -> getString(R.string.min_ago, min)
            min < 1440 -> getString(R.string.hours_ago, min / 60)
            else -> getString(R.string.days_ago, min / 1440)
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.str(k: String): String? = if (isNull(k) || !has(k)) null else optString(k, "").ifBlank { null }
}
