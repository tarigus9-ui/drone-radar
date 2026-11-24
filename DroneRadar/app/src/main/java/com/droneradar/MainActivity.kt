package com.droneradar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.droneradar.map.RadarMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.osmdroid.config.Configuration
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var map: RadarMap? = null
    private lateinit var status: TextView
    private lateinit var list: TextView
    private lateinit var account: TextView
    private lateinit var adminBtn: Button
    private lateinit var historyBtn: Button
    private lateinit var satBtn: Button
    private lateinit var toggle: Button
    private var focusKey: String? = null
    private var satellite = true

    private val basePerms: Array<String>
        get() = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasBase()) { askBackgroundLocation(); startScanning() }
            else status.text = getString(R.string.perm_needed)
        }

    private val bgLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askBatteryExemption() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        Backend.init(this)
        val offlineOk = getSharedPreferences("backend", MODE_PRIVATE).getBoolean("offline_ok", false)
        if (Backend.isConfigured && !Backend.isLoggedIn && !offlineOk) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        Alerts.createChannels(this)

        status = findViewById(R.id.status)
        list = findViewById(R.id.list)
        account = findViewById(R.id.account)
        account.setOnClickListener { onAccountClick() }
        adminBtn = findViewById(R.id.admin)
        adminBtn.setOnClickListener { startActivity(Intent(this, AdminActivity::class.java)) }
        historyBtn = findViewById(R.id.history)
        historyBtn.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        satBtn = findViewById(R.id.sat)
        satBtn.setOnClickListener {
            satellite = !satellite
            map?.setSatellite(satellite)
            satBtn.text = getString(if (satellite) R.string.map_plain else R.string.map_sat)
        }
        toggle = findViewById(R.id.toggle)
        lifecycleScope.launch(Dispatchers.IO) { runCatching { Backend.refreshWho() } }

        RadarMap.create(this, findViewById<FrameLayout>(R.id.map_container)) { m ->
            map = m
            satBtn.visibility = if (m.supportsSatellite) View.VISIBLE else View.GONE
            m.onStart(); m.onResume()
            render(DroneRepository.drones.value)
            lifecycleScope.launch { refreshOverlays(sitesToo = true) }
        }

        findViewById<Button>(R.id.center).setOnClickListener {
            (map?.myLocation() ?: Backend.myLocation?.let { it.latitude to it.longitude })
                ?.let { map?.setCenter(it.first, it.second, 16.0) }
        }
        toggle.setOnClickListener {
            val on = !Prefs.enabled(this)
            Prefs.setEnabled(this, on)
            if (on) startScanning() else ScanService.stop(this)
            refreshToggle()
        }
        refreshToggle()
        focusKey = intent.getStringExtra("focus")

        if (hasBase()) startScanning() else permLauncher.launch(basePerms)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DroneRepository.drones.collect { render(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Backend.status.collect { st -> renderAccount(st) }
            }
        }
        // Sites every 5 min, staff coverage every 30 s (managers)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var n = 0
                while (isActive) {
                    refreshOverlays(sitesToo = n % 10 == 0)
                    n++
                    delay(30_000)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        focusKey = intent.getStringExtra("focus")
        render(DroneRepository.drones.value)
    }

    override fun onStart() { super.onStart(); map?.onStart() }
    override fun onResume() { super.onResume(); map?.onResume() }
    override fun onPause() { map?.onPause(); super.onPause() }
    override fun onStop() { map?.onStop(); super.onStop() }
    override fun onDestroy() { map?.onDestroy(); super.onDestroy() }

    private fun renderAccount(st: Backend.Status) {
        account.visibility = if (Backend.isConfigured) View.VISIBLE else View.GONE
        val w = Backend.who
        val roleLine = when {
            !st.loggedIn || w == null -> ""
            !w.hasProfile -> "\n" + getString(R.string.waiting_activation)
            else -> "\n" + getString(R.string.role_line, roleName(w.role), w.site ?: w.region ?: w.company ?: "")
        }
        account.text = when {
            !st.loggedIn -> getString(R.string.acc_offline)
            st.lastError != null && st.pending > 0 -> getString(R.string.acc_error, st.lastError) +
                "  " + getString(R.string.acc_online, st.email ?: "", st.sent, st.pending)
            else -> getString(R.string.acc_online, st.email ?: "", st.sent, st.pending)
        } + roleLine
        adminBtn.visibility = if (Backend.isAdmin) View.VISIBLE else View.GONE
        historyBtn.visibility = if (st.loggedIn && w?.hasProfile == true) View.VISIBLE else View.GONE
    }

    private fun roleName(r: String?) = when (r) {
        "admin" -> getString(R.string.role_admin)
        "region_manager" -> getString(R.string.role_region)
        "site_manager" -> getString(R.string.role_site)
        else -> getString(R.string.role_guard)
    }

    private fun onAccountClick() {
        if (!Backend.isLoggedIn) {
            getSharedPreferences("backend", MODE_PRIVATE).edit().putBoolean("offline_ok", false).apply()
            startActivity(Intent(this, LoginActivity::class.java)); return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.logout_q, Backend.email ?: ""))
            .setPositiveButton(R.string.logout) { _, _ ->
                Backend.logout()
                ManagerAlerts.reset(this)
                getSharedPreferences("backend", MODE_PRIVATE).edit().putBoolean("offline_ok", false).apply()
                startActivity(Intent(this, LoginActivity::class.java)); finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun hasBase() = basePerms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startScanning() {
        if (!Prefs.enabled(this) || !hasBase()) return
        ScanService.start(this)
        status.text = getString(R.string.scanning)
    }

    private fun refreshToggle() {
        toggle.text = getString(if (Prefs.enabled(this)) R.string.stop else R.string.start)
    }

    /** Needed so the OS keeps delivering scan results while the app is closed. */
    private fun askBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29) return askBatteryExemption()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            == PackageManager.PERMISSION_GRANTED) return askBatteryExemption()
        AlertDialog.Builder(this)
            .setTitle(R.string.bg_title)
            .setMessage(R.string.bg_msg)
            .setPositiveButton(R.string.ok) { _, _ -> bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
            .setNegativeButton(R.string.later) { _, _ -> askBatteryExemption() }
            .show()
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    // ---------- Platform overlays: sites + live staff coverage ----------
    private suspend fun refreshOverlays(sitesToo: Boolean) {
        val m = map ?: return
        if (!Backend.isLoggedIn || Backend.who?.hasProfile != true) return
        if (sitesToo) {
            val (c, b) = withContext(Dispatchers.IO) { Backend.call("POST", "/rest/v1/rpc/list_sites", "{}") }
            if (c in 200..299) {
                m.clear(L_SITES)
                runCatching { JSONArray(b) }.getOrNull()?.let { a ->
                    for (i in 0 until a.length()) {
                        val s = a.getJSONObject(i)
                        val lat = s.optDouble("lat"); val lon = s.optDouble("lon")
                        if (lat.isNaN() || lon.isNaN()) continue
                        m.addCircle(L_SITES, lat, lon, s.optDouble("radius_m", 500.0),
                            Color.argb(220, 255, 193, 7), Color.argb(40, 255, 193, 7))
                    }
                }
            }
        }
        if (Backend.isManager) {
            val (c, b) = withContext(Dispatchers.IO) { Backend.call("POST", "/rest/v1/rpc/coverage", "{}") }
            if (c in 200..299) {
                m.clear(L_STAFF)
                runCatching { JSONArray(b) }.getOrNull()?.let { a ->
                    for (i in 0 until a.length()) {
                        val g = a.getJSONObject(i)
                        val lat = g.optDouble("lat"); val lon = g.optDouble("lon")
                        if (lat.isNaN() || lon.isNaN()) continue
                        val online = g.optBoolean("online")
                        val bat = if (g.isNull("battery")) "—" else "${g.optInt("battery")}%"
                        m.addDot(L_STAFF, lat, lon,
                            if (online) Color.rgb(30, 136, 229) else Color.rgb(158, 158, 158), 16,
                            "🛡️ ${g.optString("name", "")}",
                            getString(if (online) R.string.staff_online else R.string.staff_offline, bat))
                    }
                }
            }
        }
    }

    // ---------- Drones ----------
    private fun render(drones: List<DroneInfo>) {
        val sb = StringBuilder()
        for (d in drones) {
            sb.append(if (d.remote) "📡 " else "• ").append(d.label).append(" — ").append(d.remoteInfo ?: d.source)
                .append(if (d.opLat != null) " — 🟢" else "").append('\n')
        }
        status.text = if (drones.isEmpty()) getString(R.string.scanning) else getString(R.string.found, drones.size)
        list.text = sb.toString().trim()

        val m = map ?: return
        m.clear(L_DRONES)
        var focus: Pair<Double, Double>? = null
        for (d in drones) {
            val dronePt = if (d.lat != null && d.lon != null) d.lat!! to d.lon!! else null
            val opPt = if (d.opLat != null && d.opLon != null) d.opLat!! to d.opLon!! else null
            // live trails: drone (thin red) and operator (green dashed) — how they moved during this incident
            m.addLine(L_DRONES, d.droneTrail, Color.argb(150, 229, 57, 53), 2f)
            m.addLine(L_DRONES, d.opTrail, Color.rgb(0, 200, 83), 3f, dashed = true)
            if (dronePt != null && opPt != null) m.addLine(L_DRONES, listOf(opPt, dronePt), Color.argb(200, 255, 140, 0), 2.5f)
            opPt?.let {
                m.addDot(L_DRONES, it.first, it.second, Color.rgb(0, 200, 83), 18,
                    getString(R.string.operator), "${d.operatorId ?: ""}  ${d.label}")
            }
            dronePt?.let { m.addDot(L_DRONES, it.first, it.second, Color.rgb(229, 57, 53), 22, d.label, details(d)) }
            if (d.key == focusKey) focus = opPt ?: dronePt
        }
        focus?.let { m.setCenter(it.first, it.second, 17.0); focusKey = null }
    }

    private fun details(d: DroneInfo) = buildString {
        fun f(v: Float?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
        if (d.remote) appendLine("📡 ${d.remoteInfo ?: ""}")
        else appendLine("${getString(R.string.source)}: ${d.source}  (${d.rssi} dBm)")
        appendLine("${getString(R.string.height)}: ${f(d.height)} m  |  ${getString(R.string.speed)}: ${f(d.speed)} m/s")
        d.operatorId?.let { appendLine("${getString(R.string.operator_id)}: $it") }
        d.description?.let { append(it) }
    }.trim()

    companion object {
        private const val L_SITES = "sites"
        private const val L_STAFF = "staff"
        private const val L_DRONES = "drones"
    }
}
