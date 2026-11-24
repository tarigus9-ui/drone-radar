package com.droneradar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * For managers: pulls detections made by their staff (the server already filters by role:
 * site manager → his site, region manager → his region, admin → whole company, all countries),
 * shows them on the map and raises one alert per drone per 10 minutes.
 */
object ManagerAlerts {
    private const val REALERT_MS = 10 * 60_000L
    private val alerted = HashMap<String, Long>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("backend", Context.MODE_PRIVATE)

    /** Blocking — call from a background thread every ~15 s. */
    fun poll(ctx: Context) {
        if (!Backend.isLoggedIn || !Backend.isManager) return
        val since = prefs(ctx).getString("alerts_since", null) ?: isoNow(-2 * 60_000L)
        val (code, body) = Backend.call("POST", "/rest/v1/rpc/alerts_since", JSONObject().put("p_since", since).toString())
        if (code !in 200..299) return
        val rows = runCatching { JSONArray(body) }.getOrNull() ?: return
        var last = since
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            last = r.optString("created_at", last)
            handle(ctx, r)
        }
        prefs(ctx).edit().putString("alerts_since", last).apply()
    }

    fun reset(ctx: Context) = prefs(ctx).edit().remove("alerts_since").remove("sightings_since").apply()

    /** Blocking — "I saw a drone" reports from staff. */
    fun pollSightings(ctx: Context) {
        if (!Backend.isLoggedIn || !Backend.isManager) return
        val since = prefs(ctx).getString("sightings_since", null) ?: isoNow(-2 * 60_000L)
        val (code, body) = Backend.call("POST", "/rest/v1/rpc/sightings_since", JSONObject().put("p_since", since).toString())
        if (code !in 200..299) return
        val rows = runCatching { JSONArray(body) }.getOrNull() ?: return
        val last = if (rows.length() > 0) rows.getJSONObject(0).optString("created_at", since) else since  // newest first
        for (i in rows.length() - 1 downTo 0) {
            val r = rows.getJSONObject(i)
            if (r.optBoolean("mine")) continue
            val place = r.str("site") ?: ctx.getString(R.string.outside_sites)
            val fix = if (r.dbl("est_lat") != null) ctx.getString(R.string.sighting_fix_yes) else ""
            notify(ctx, "sighting-" + r.optLong("id"),
                ctx.getString(R.string.sighting_alert_title, place),
                ctx.getString(R.string.sighting_alert_text, r.str("reporter") ?: "حارس",
                    Compass.dirName(ctx, r.optDouble("bearing").toFloat()), r.optDouble("bearing").toInt()) + fix)
        }
        prefs(ctx).edit().putString("sightings_since", last).apply()
    }

    private fun handle(ctx: Context, r: JSONObject) {
        val serial = r.str("serial")
        val key = "remote-" + (serial ?: r.str("drone_key") ?: r.optLong("id").toString())
        val site = r.str("site")
        val reporter = r.str("reporter") ?: "حارس"
        val place = site ?: ctx.getString(R.string.outside_sites)

        DroneRepository.upsertRemote(key) { d ->
            d.serial = serial ?: d.serial
            d.operatorId = r.str("operator_id") ?: d.operatorId
            d.source = ctx.getString(R.string.remote_source, reporter)
            d.remoteInfo = "$place • $reporter"
            d.uaType = r.optInt("ua_type", d.uaType)
            r.dbl("drone_lat")?.let { d.lat = it }; r.dbl("drone_lon")?.let { d.lon = it }
            r.dbl("drone_height")?.let { d.height = it.toFloat() }
            r.dbl("speed")?.let { d.speed = it.toFloat() }; r.dbl("heading")?.let { d.heading = it.toFloat() }
            r.dbl("op_lat")?.let { d.opLat = it }; r.dbl("op_lon")?.let { d.opLon = it }
        }

        val now = System.currentTimeMillis()
        if (now - (alerted[key] ?: 0) < REALERT_MS) return
        alerted[key] = now
        notify(ctx, key, ctx.getString(R.string.mgr_alert_title, place),
            ctx.getString(R.string.mgr_alert_text, reporter, serial ?: "—",
                if (r.dbl("op_lat") != null) ctx.getString(R.string.op_known) else ctx.getString(R.string.op_unknown)))
    }

    private fun notify(ctx: Context, key: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        Alerts.createChannels(ctx)
        val n = NotificationCompat.Builder(ctx, Alerts.CH_ALERT)
            .setSmallIcon(R.drawable.ic_drone)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(Alerts.openAppIntent(ctx, key))
            .build()
        NotificationManagerCompat.from(ctx).notify(key.hashCode(), n)
    }

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k, "").ifBlank { null }
    private fun JSONObject.dbl(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }

    private fun isoNow(offsetMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(System.currentTimeMillis() + offsetMs))
}
