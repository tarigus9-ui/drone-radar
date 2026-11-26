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
    var idType: Int? = null,                 // Basic ID type (1 = serial number)
    var spoof: Set<String> = emptySet(),     // forgery signs of the CURRENT state (SpoofCheck / server)
    var everFlagged: Set<String> = emptySet(), // every sign ever seen for this drone (history only)
    var ts10: Int? = null,                   // Location message time: tenths of a second since the hour
    var spoofAlerted: Boolean = false,
    var posTime: Long = 0L,                  // when lat/lon last changed
) {
    /** Serial or operator ID. A radio address (MAC / NaN peer) is NOT an identity: it is shown as "no ID". */
    val label: String get() = serial ?: operatorId ?: ("⛔ " + Lang.noId + " · …" + key.takeLast(5))
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
        val pLat = d.lat; val pLon = d.lon; val pT = d.posTime; val pTs = d.ts10
        if (!decode(d)) return null
        val now = System.currentTimeMillis()
        d.source = source; d.rssi = rssi; d.lastSeen = now
        if (d.lat != pLat || d.lon != pLon) {
            SpoofCheck.local(d, pLat, pLon, if (pT == 0L) now else pT, pTs, Backend.myLocation)
            d.posTime = now
        } else SpoofCheck.local(d, null, null, now, null, Backend.myLocation)
        recordTrail(d)
        map[key] = d
        // Merge records that share a serial (same drone seen on BLE + Wi-Fi)
        val ready = d.serial != null || now - d.firstSeen > 3_000
        val isNew = ready && !d.alerted && map.values.none { it !== d && it.serial != null && it.serial == d.serial && it.alerted }
        if (isNew) d.alerted = true
        // a drone that turns out to be forged gets a second, explicit alarm
        val spoofNew = d.spoof.isNotEmpty() && !d.spoofAlerted && d.alerted
        if (spoofNew) d.spoofAlerted = true
        publish()
        Backend.onDetection(d)
        return d to (isNew || spoofNew)
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
