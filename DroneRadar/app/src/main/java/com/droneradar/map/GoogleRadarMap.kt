package com.droneradar.map

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions

/** Google Maps engine: satellite imagery with road/place labels (hybrid). */
class GoogleRadarMap private constructor(private val activity: Activity, private val mv: MapView, private val gm: GoogleMap) : RadarMap {

    private val layers = HashMap<String, MutableList<Any>>()
    private val density = activity.resources.displayMetrics.density
    private var movedByUser = false

    override val view: View get() = mv
    override val supportsSatellite = true

    companion object {
        fun create(activity: Activity, container: FrameLayout, ready: (RadarMap) -> Unit) {
            val mv = MapView(activity)
            container.addView(mv)
            mv.onCreate(null)
            mv.getMapAsync { gm -> ready(GoogleRadarMap(activity, mv, gm).also { it.setup() }) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setup() {
        gm.mapType = GoogleMap.MAP_TYPE_HYBRID
        gm.uiSettings.isZoomControlsEnabled = true
        gm.uiSettings.isCompassEnabled = true
        gm.uiSettings.isMyLocationButtonEnabled = false
        runCatching { gm.isMyLocationEnabled = true }
        gm.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(25.2048, 55.2708), 14f))
        gm.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) movedByUser = true
        }
        gm.setOnMyLocationChangeListener { loc ->
            if (!movedByUser) { movedByUser = true; gm.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15f)) }
        }
        // Multi-line info window (title + snippet lines)
        gm.setInfoWindowAdapter(object : GoogleMap.InfoWindowAdapter {
            override fun getInfoWindow(m: Marker): View? = null
            override fun getInfoContents(m: Marker): View = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply { text = m.title; textSize = 15f; setTextColor(Color.BLACK) })
                if (!m.snippet.isNullOrBlank()) addView(TextView(activity).apply { text = m.snippet; textSize = 13f; setTextColor(Color.DKGRAY) })
            }
        })
    }

    override fun setCenter(lat: Double, lon: Double, zoom: Double?, animate: Boolean) {
        movedByUser = true
        val u = if (zoom != null) CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom.toFloat())
                else CameraUpdateFactory.newLatLng(LatLng(lat, lon))
        if (animate) gm.animateCamera(u) else gm.moveCamera(u)
    }

    @Suppress("DEPRECATION")
    override fun myLocation(): Pair<Double, Double>? = runCatching { gm.myLocation }.getOrNull()?.let { it.latitude to it.longitude }

    override fun clear(layer: String) {
        layers.remove(layer)?.forEach {
            when (it) {
                is Marker -> it.remove()
                is com.google.android.gms.maps.model.Polyline -> it.remove()
                is com.google.android.gms.maps.model.Circle -> it.remove()
            }
        }
    }

    private fun keep(layer: String, o: Any?) { if (o != null) layers.getOrPut(layer) { mutableListOf() }.add(o) }

    override fun addDot(layer: String, lat: Double, lon: Double, color: Int, sizeDp: Int, title: String, snippet: String) {
        keep(layer, gm.addMarker(MarkerOptions()
            .position(LatLng(lat, lon))
            .icon(BitmapDescriptorFactory.fromBitmap(RadarMap.dotBitmap(color, (sizeDp * density).toInt())))
            .anchor(0.5f, 0.5f)
            .title(title).snippet(snippet)))
    }

    override fun addLine(layer: String, points: List<Pair<Double, Double>>, color: Int, widthDp: Float, dashed: Boolean) {
        if (points.size < 2) return
        val o = PolylineOptions().addAll(points.map { LatLng(it.first, it.second) }).color(color).width(widthDp * density)
        if (dashed) o.pattern(listOf(Dash(24f), Gap(16f)))
        keep(layer, gm.addPolyline(o))
    }

    override fun addCircle(layer: String, lat: Double, lon: Double, radiusM: Double, stroke: Int, fill: Int) {
        keep(layer, gm.addCircle(CircleOptions().center(LatLng(lat, lon)).radius(radiusM)
            .strokeColor(stroke).fillColor(fill).strokeWidth(2 * density)))
    }

    override fun fit(points: List<Pair<Double, Double>>) {
        if (points.isEmpty()) return
        movedByUser = true
        if (points.size == 1) { setCenter(points[0].first, points[0].second, 17.0); return }
        val b = LatLngBounds.builder().apply { points.forEach { include(LatLng(it.first, it.second)) } }.build()
        mv.post { runCatching { gm.animateCamera(CameraUpdateFactory.newLatLngBounds(b, (64 * density).toInt())) } }
    }

    override fun setSatellite(on: Boolean) { gm.mapType = if (on) GoogleMap.MAP_TYPE_HYBRID else GoogleMap.MAP_TYPE_NORMAL }

    override fun onStart() = mv.onStart()
    override fun onResume() = mv.onResume()
    override fun onPause() = mv.onPause()
    override fun onStop() = mv.onStop()
    override fun onDestroy() = mv.onDestroy()
}
