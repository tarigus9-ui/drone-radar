package com.droneradar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DroneInfo(
    val key: String,                 // MAC / NaN peer id
    var source: String = "",         // BLE / Wi-Fi Beacon / Wi-Fi NaN
    var serial: String? = null,
    var operatorId: String? = null,
    var description: String? = null,
    var uaType: Int = 0,
    var status: Int = 0,
    var lat: Double? = null, var lon: Double? = null,
    var altGeo: Float? = null, var height: Float? = null,
    var speed: Float? = null, var heading: Float? = null,
    var opLat: Double? = null, var opLon: Double? = null, var opAlt: Float? = null,
    var rssi: Int = 0,
    var firstSeen: Long = System.currentTimeMillis(),
    var lastSeen: Long = System.currentTimeMillis(),
    var alerted: Boolean = false,
    var remote: Boolean = false,          // seen by another guard, received from the platform
    var remoteInfo: String? = null,       // "site • reporter"
    var opTrail: List<Pair<Double, Double>> = emptyList(),     // live path of the operator
    var droneTrail: List<Pair<Double, Double>> = emptyList(),  // live path of the drone
) {
    val label: String get() = serial ?: operatorId ?: key
}

/** Process-wide store shared by the scanner service and the map screen. */
object DroneRepository {
    private const val TRAIL_MAX = 600
    private const val TRAIL_MIN_M = 3.0

    private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dLat = (a.first - b.first) * 111_320.0
        val dLon = (a.second - b.second) * 111_320.0 * Math.cos(Math.toRadians(a.first))
        return Math.sqrt(dLat * dLat + dLon * dLon)
    }

    private fun extend(trail: List<Pair<Double, Double>>, lat: Double?, lon: Double?): List<Pair<Double, Double>> {
        if (lat == null || lon == null) return trail
        val p = lat to lon
        val last = trail.lastOrNull()
        if (last != null && dist(last, p) < TRAIL_MIN_M) return trail
        return (trail + p).takeLast(TRAIL_MAX)
    }

    private fun recordTrail(d: DroneInfo) {
        d.opTrail = extend(d.opTrail, d.opLat, d.opLon)
        d.droneTrail = extend(d.droneTrail, d.lat, d.lon)
    }

    private const val STALE_MS = 60_000L
    private val map = LinkedHashMap<String, DroneInfo>()
    private val _drones = MutableStateFlow<List<DroneInfo>>(emptyList())
    val drones: StateFlow<List<DroneInfo>> = _drones

    /** Returns the drone and whether it is new (never alerted). */
    @Synchronized
    fun update(key: String, source: String, rssi: Int, decode: (DroneInfo) -> Boolean): Pair<DroneInfo, Boolean>? {
        val d = map[key] ?: DroneInfo(key)
        if (!decode(d)) return null
        d.source = source; d.rssi = rssi; d.lastSeen = System.currentTimeMillis()
        recordTrail(d)
        map[key] = d
        // Merge records that share a serial (same drone seen on BLE + Wi-Fi)
        val isNew = !d.alerted && map.values.none { it !== d && it.serial != null && it.serial == d.serial && it.alerted }
        if (isNew) d.alerted = true
        publish()
        Backend.onDetection(d)
        return d to isNew
    }

    /** Drone reported by another staff member (from the server). Never re-uploaded. */
    @Synchronized
    fun upsertRemote(key: String, fill: (DroneInfo) -> Unit) {
        val d = map[key] ?: DroneInfo(key, remote = true)
        if (!d.remote) return            // we already see it ourselves — local data wins
        fill(d)
        recordTrail(d)
        d.lastSeen = System.currentTimeMillis()
        map[key] = d
        publish()
    }

    @Synchronized
    fun prune() {
        val now = System.currentTimeMillis()
        if (map.values.removeAll { now - it.lastSeen > STALE_MS }) publish()
    }

    private fun publish() { _drones.value = map.values.map { it.copy() } }
}
