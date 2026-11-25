package com.droneradar

import android.content.Context
import android.location.Location
import org.json.JSONArray
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Phone-side forgery checks (instant, work offline). The server repeats them with data from
 * all guards and adds "serial clone" and "not heard by a nearby guard".
 * Flag names are the same on phone and server.
 */
object SpoofCheck {
    /** Flags strong enough to override the whitelist (an authorized serial with these = impersonation). */
    val STRONG = setOf("impossible_speed", "serial_clone", "signal_mismatch", "impossible_alt")

    private val CTA = Regex("^[0-9A-HJ-NP-Z]{4}[1-9A-F][0-9A-HJ-NP-Z]{1,15}$")

    fun cta2063Ok(s: String): Boolean {
        val u = s.uppercase()
        if (!CTA.matches(u)) return false
        return u.length - 5 == "0123456789ABCDEF".indexOf(u[4])
    }

    private fun meters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dy = (aLat - bLat) * 111_320.0
        val dx = (aLon - bLon) * 111_320.0 * cos(Math.toRadians(aLat))
        return sqrt(dx * dx + dy * dy)
    }

    /** Called after each decoded update. prev* = the drone position before this update. */
    /** Fastest plausible drone, m/s, and position uncertainty, m, for the jump test. */
    private const val MAX_SPEED = 70.0
    private const val POS_UNCERT = 30.0

    /**
     * Seconds between two positions: from the drone's own Location timestamps when both are
     * present (immune to radio / processing delay), else from the phone's receive times.
     */
    fun interval(prevTs: Int?, ts: Int?, prevTime: Long, now: Long): Double {
        if (prevTs != null && ts != null) {
            val diff = (ts - prevTs + 36_000) % 36_000          // wraps at the full hour
            val recv = (now - prevTime) / 1000.0
            // a timestamp that does not move while time clearly passed is broken: use receive time
            val stuck = diff == 0 && recv > 1.5
            if (!stuck && diff in 0..6_000) return diff / 10.0   // up to 10 minutes apart
        }
        return ((now - prevTime) / 1000.0).coerceAtLeast(0.0)
    }

    /** True if moving [dist] metres in [secs] seconds is not physically plausible. */
    fun impossibleJump(dist: Double, secs: Double): Boolean = dist > MAX_SPEED * secs + POS_UNCERT

    /**
     * Called after each decoded update. prev* = the drone position before this update.
     * Flags describe THIS update only: a bad reading no longer marks the drone forever
     * (the history keeps them in everFlagged).
     */
    fun local(d: DroneInfo, prevLat: Double?, prevLon: Double?, prevTime: Long, prevTs: Int?, me: Location?) {
        val f = mutableSetOf<String>()
        d.speed?.let { if (it > 70f) f += "impossible_speed" }
        d.height?.let { if (it > 1500f || it < -100f) f += "impossible_alt" }

        val lat = d.lat; val lon = d.lon
        if (lat != null && lon != null && prevLat != null && prevLon != null) {
            val dist = meters(lat, lon, prevLat, prevLon)
            val secs = interval(prevTs, d.ts10, prevTime, System.currentTimeMillis())
            if (impossibleJump(dist, secs)) f += "impossible_speed"
        }
        if (lat != null && lon != null && me != null && d.rssi < 0) {
            val dg = meters(lat, lon, me.latitude, me.longitude)
            val bt = d.source.contains("Bluetooth", true)
            if ((bt && d.rssi > -60 && dg > 800) || (!bt && d.rssi > -50 && dg > 1500)) f += "signal_mismatch"
        }
        val oLat = d.opLat; val oLon = d.opLon
        if (lat != null && lon != null && oLat != null && oLon != null && meters(lat, lon, oLat, oLon) > 15_000) f += "operator_far"
        val serial = d.serial
        f += GlobalId.idFlags(d)
        if (serial != null && f.any { it in STRONG } && Whitelist.contains(serial, d.operatorId)) f += "whitelist_spoof"
        d.spoof = f
        d.everFlagged = d.everFlagged + f
    }

    fun fromJson(a: JSONArray?): Set<String> =
        if (a == null) emptySet() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()

    fun isSuspicious(flags: Set<String>) = flags.isNotEmpty()

    /** Authorized and no strong sign of forgery → stay silent. */
    fun silentBecauseWhitelisted(d: DroneInfo) = Whitelist.contains(d.serial, d.operatorId) && d.spoof.none { it in STRONG }

    fun describe(ctx: Context, flags: Collection<String>): String = flags.joinToString(if (ctx.resources.configuration.locales[0].language == "ar") "، " else ", ") { f ->
        when (f) {
            "impossible_speed" -> ctx.getString(R.string.sp_speed)
            "impossible_alt" -> ctx.getString(R.string.sp_alt)
            "serial_clone" -> ctx.getString(R.string.sp_clone)
            "signal_mismatch" -> ctx.getString(R.string.sp_signal)
            "operator_far" -> ctx.getString(R.string.sp_opfar)
            "bad_serial" -> ctx.getString(R.string.sp_serial)
            "bad_operator_id" -> ctx.getString(R.string.sp_opid)
            "not_heard_nearby" -> ctx.getString(R.string.sp_nearby)
            "whitelist_spoof" -> ctx.getString(R.string.sp_wl)
            else -> f
        }
    }
}
