package com.droneradar.map

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import com.droneradar.BuildConfig

/**
 * One small map API for the whole app, with two engines:
 *  - Google Maps (satellite + labels) when a Maps API key is built in
 *  - OpenStreetMap (osmdroid) otherwise — no key needed
 * Everything is drawn in named layers so each layer can be redrawn on its own.
 */
interface RadarMap {
    val view: View
    val supportsSatellite: Boolean

    fun setCenter(lat: Double, lon: Double, zoom: Double? = null, animate: Boolean = true)
    fun myLocation(): Pair<Double, Double>?
    fun clear(layer: String)
    fun addDot(layer: String, lat: Double, lon: Double, color: Int, sizeDp: Int, title: String, snippet: String = "")
    fun addLine(layer: String, points: List<Pair<Double, Double>>, color: Int, widthDp: Float, dashed: Boolean = false)
    fun addCircle(layer: String, lat: Double, lon: Double, radiusM: Double, stroke: Int, fill: Int)
    fun fit(points: List<Pair<Double, Double>>)
    fun setSatellite(on: Boolean)

    fun onStart() {}
    fun onResume() {}
    fun onPause() {}
    fun onStop() {}
    fun onDestroy() {}

    companion object {
        val useGoogle: Boolean get() = BuildConfig.MAPS_API_KEY.isNotBlank()

        /** Creates the map inside [container]; [ready] is called once it can be drawn on. */
        fun create(activity: Activity, container: FrameLayout, ready: (RadarMap) -> Unit) {
            if (useGoogle) GoogleRadarMap.create(activity, container, ready)
            else OsmRadarMap(activity).also { container.addView(it.view); ready(it) }
        }

        /** Round dot with a white ring — drones, operators, guards. */
        fun dotBitmap(color: Int, sizePx: Int): Bitmap {
            val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val r = sizePx / 2f
            p.color = Color.WHITE; c.drawCircle(r, r, r, p)
            p.color = color; c.drawCircle(r, r, r * 0.78f, p)
            return b
        }
    }
}
