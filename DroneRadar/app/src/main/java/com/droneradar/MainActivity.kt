package com.droneradar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var status: TextView
    private lateinit var list: TextView
    private lateinit var account: TextView
    private lateinit var toggle: Button
    private lateinit var myLoc: MyLocationNewOverlay
    private val droneOverlays = mutableListOf<org.osmdroid.views.overlay.Overlay>()
    private var focusKey: String? = null
    private var firstFix = true

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

        map = findViewById(R.id.map)
        status = findViewById(R.id.status)
        list = findViewById(R.id.list)
        account = findViewById(R.id.account)
        account.setOnClickListener { onAccountClick() }
        toggle = findViewById(R.id.toggle)

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(25.2048, 55.2708))

        myLoc = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        myLoc.enableMyLocation()
        myLoc.runOnFirstFix { runOnUiThread { if (firstFix) map.controller.animateTo(myLoc.myLocation) } }
        map.overlays.add(myLoc)

        findViewById<Button>(R.id.center).setOnClickListener {
            myLoc.myLocation?.let { map.controller.animateTo(it) }
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
                Backend.status.collect { st ->
                    account.visibility = if (Backend.isConfigured) android.view.View.VISIBLE else android.view.View.GONE
                    account.text = when {
                        !st.loggedIn -> getString(R.string.acc_offline)
                        st.lastError != null && st.pending > 0 -> getString(R.string.acc_error, st.lastError) +
                            "  " + getString(R.string.acc_online, st.email ?: "", st.sent, st.pending)
                        else -> getString(R.string.acc_online, st.email ?: "", st.sent, st.pending)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        focusKey = intent.getStringExtra("focus")
        render(DroneRepository.drones.value)
    }

    override fun onResume() { super.onResume(); if (::map.isInitialized) map.onResume() }
    override fun onPause() { super.onPause(); if (::map.isInitialized) map.onPause() }

    private fun onAccountClick() {
        if (!Backend.isLoggedIn) {
            getSharedPreferences("backend", MODE_PRIVATE).edit().putBoolean("offline_ok", false).apply()
            startActivity(Intent(this, LoginActivity::class.java)); return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.logout_q, Backend.email ?: ""))
            .setPositiveButton(R.string.logout) { _, _ ->
                Backend.logout()
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

    // ---------- Map rendering ----------
    private fun dot(color: Int, sizeDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), Color.WHITE)
        val s = dp(sizeDp); setSize(s, s)
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render(drones: List<DroneInfo>) {
        map.overlays.removeAll(droneOverlays)
        droneOverlays.clear()
        val sb = StringBuilder()
        var focusPoint: GeoPoint? = null

        for (d in drones) {
            val dronePt = if (d.lat != null && d.lon != null) GeoPoint(d.lat!!, d.lon!!) else null
            val opPt = if (d.opLat != null && d.opLon != null) GeoPoint(d.opLat!!, d.opLon!!) else null

            if (dronePt != null && opPt != null) {
                droneOverlays += Polyline(map).apply {
                    setPoints(listOf(opPt, dronePt))
                    outlinePaint.color = Color.argb(160, 255, 140, 0)
                    outlinePaint.strokeWidth = dp(2).toFloat()
                }
            }
            opPt?.let {
                droneOverlays += Marker(map).apply {
                    position = it
                    icon = dot(Color.rgb(0, 200, 83), 18)       // 🟢 operator
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = getString(R.string.operator)
                    snippet = "${d.operatorId ?: ""}\n${d.label}"
                }
            }
            dronePt?.let {
                droneOverlays += Marker(map).apply {
                    position = it
                    icon = dot(Color.rgb(229, 57, 53), 22)      // 🔴 drone
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = d.label
                    snippet = details(d)
                }
            }
            if (d.key == focusKey) focusPoint = opPt ?: dronePt
            sb.append("• ").append(d.label).append(" — ").append(d.source)
                .append(if (opPt != null) " — 🟢" else "").append('\n')
        }
        map.overlays.addAll(droneOverlays)
        map.invalidate()

        status.text = if (drones.isEmpty()) getString(R.string.scanning)
        else getString(R.string.found, drones.size)
        list.text = sb.toString().trim()

        focusPoint?.let { map.controller.animateTo(it); firstFix = false; focusKey = null }
    }

    private fun details(d: DroneInfo) = buildString {
        fun f(v: Float?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
        appendLine("${getString(R.string.source)}: ${d.source}  (${d.rssi} dBm)")
        appendLine("${getString(R.string.height)}: ${f(d.height)} m  |  ${getString(R.string.alt)}: ${f(d.altGeo)} m")
        appendLine("${getString(R.string.speed)}: ${f(d.speed)} m/s  |  ${getString(R.string.heading)}: ${f(d.heading)}°")
        d.operatorId?.let { appendLine("${getString(R.string.operator_id)}: $it") }
        d.description?.let { appendLine(it) }
    }
}
