package com.droneradar.map

import android.content.Context
import android.graphics.DashPathEffect
import android.graphics.drawable.BitmapDrawable
import android.view.View
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/** OpenStreetMap engine (no API key). */
class OsmRadarMap(private val ctx: Context) : RadarMap {
    private val map = MapView(ctx)
    private val layers = HashMap<String, MutableList<Overlay>>()
    private val density = ctx.resources.displayMetrics.density
    private val myLoc = MyLocationNewOverlay(GpsMyLocationProvider(ctx), map)
    private var firstFix = true

    override val view: View get() = map
    override val supportsSatellite = false

    init {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(25.2048, 55.2708))
        runCatching { myLoc.enableMyLocation() }
        myLoc.runOnFirstFix {
            map.post { if (firstFix) myLoc.myLocation?.let { map.controller.animateTo(it) } }
        }
        map.overlays.add(myLoc)
    }

    override fun setCenter(lat: Double, lon: Double, zoom: Double?, animate: Boolean) {
        firstFix = false
        zoom?.let { map.controller.setZoom(it) }
        if (animate) map.controller.animateTo(GeoPoint(lat, lon)) else map.controller.setCenter(GeoPoint(lat, lon))
    }

    override fun myLocation(): Pair<Double, Double>? = myLoc.myLocation?.let { it.latitude to it.longitude }

    override fun clear(layer: String) {
        layers.remove(layer)?.let { map.overlays.removeAll(it) }
        map.invalidate()
    }

    private fun add(layer: String, o: Overlay) {
        layers.getOrPut(layer) { mutableListOf() }.add(o)
        // keep "my location" on top
        map.overlays.remove(myLoc); map.overlays.add(o); map.overlays.add(myLoc)
        map.invalidate()
    }

    override fun addDot(layer: String, lat: Double, lon: Double, color: Int, sizeDp: Int, title: String, snippet: String) {
        add(layer, Marker(map).apply {
            position = GeoPoint(lat, lon)
            icon = BitmapDrawable(ctx.resources, RadarMap.dotBitmap(color, (sizeDp * density).toInt()))
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            this.title = title; this.snippet = snippet
        })
    }

    override fun addLine(layer: String, points: List<Pair<Double, Double>>, color: Int, widthDp: Float, dashed: Boolean) {
        if (points.size < 2) return
        add(layer, Polyline(map).apply {
            setPoints(points.map { GeoPoint(it.first, it.second) })
            outlinePaint.color = color
            outlinePaint.strokeWidth = widthDp * density
            if (dashed) outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 14f), 0f)
            infoWindow = null
        })
    }

    override fun addCircle(layer: String, lat: Double, lon: Double, radiusM: Double, stroke: Int, fill: Int) {
        add(layer, Polygon(map).apply {
            points = Polygon.pointsAsCircle(GeoPoint(lat, lon), radiusM)
            fillPaint.color = fill
            outlinePaint.color = stroke
            outlinePaint.strokeWidth = 2 * density
            infoWindow = null
        })
    }

    override fun fit(points: List<Pair<Double, Double>>) {
        if (points.isEmpty()) return
        firstFix = false
        if (points.size == 1) { setCenter(points[0].first, points[0].second, 17.0); return }
        val box = BoundingBox.fromGeoPoints(points.map { GeoPoint(it.first, it.second) })
        map.post { map.zoomToBoundingBox(box.increaseByScale(1.4f), true) }
    }

    override fun setSatellite(on: Boolean) {}
    override fun onResume() = map.onResume()
    override fun onPause() = map.onPause()
}
