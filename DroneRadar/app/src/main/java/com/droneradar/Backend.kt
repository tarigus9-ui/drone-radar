package com.droneradar

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Connection to the platform server (Supabase).
 * - Email/password login, token refresh
 * - Persistent upload queue for detections (survives app restarts / no network)
 * - Device heartbeat for the live coverage map
 */
object Backend {
    private const val TAG = "Backend"
    private const val MAX_QUEUE = 1000
    private const val RESEND_MS = 5_000L   // same drone: at most one update every 5 s

    val url: String get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey: String get() = BuildConfig.SUPABASE_ANON_KEY
    val isConfigured: Boolean get() = url.startsWith("https://") && anonKey.isNotBlank()

    private lateinit var app: Context
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()

    private val queue = ArrayList<JSONObject>()
    private val lastSent = HashMap<String, Long>()
    @Volatile var myLocation: Location? = null; private set

    data class Status(val loggedIn: Boolean, val email: String?, val sent: Int, val pending: Int, val lastError: String?)
    private val _status = MutableStateFlow(Status(false, null, 0, 0, null))
    val status: StateFlow<Status> = _status
    private var sentCount = 0
    private var lastError: String? = null

    @Synchronized
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        loadQueue()
        publish()
    }

    private fun prefs() = app.getSharedPreferences("backend", Context.MODE_PRIVATE)
    val isLoggedIn: Boolean get() = ::app.isInitialized && prefs().getString("refresh", null) != null
    val email: String? get() = prefs().getString("email", null)

    val deviceId: String
        get() = prefs().getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs().edit().putString("device_id", it).apply()
        }

    // ------------------------------------------------------------------ auth
    /** Blocking — call from a background thread. Returns null on success or an error message. */
    fun login(email: String, password: String): String? {
        if (!isConfigured) return "السيرفر غير مُعدّ في هذه النسخة"
        val body = JSONObject().put("email", email.trim()).put("password", password)
        return try {
            val res = authRequest("password", body)
            saveSession(res, email.trim())
            null
        } catch (e: Exception) {
            Log.w(TAG, "login", e)
            when {
                e.message?.contains("invalid", true) == true -> "البريد أو كلمة المرور غير صحيحة"
                else -> "تعذّر الاتصال بالسيرفر"
            }
        }
    }

    fun logout() {
        prefs().edit().remove("access").remove("refresh").remove("exp").remove("email").apply()
        publish()
    }

    private fun authRequest(grant: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("$url/auth/v1/token?grant_type=$grant")
            .header("apikey", anonKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("auth ${r.code}: $txt")
            return JSONObject(txt)
        }
    }

    private fun saveSession(res: JSONObject, email: String? = null) {
        val exp = System.currentTimeMillis() / 1000 + res.optLong("expires_in", 3600)
        val ed = prefs().edit()
            .putString("access", res.getString("access_token"))
            .putString("refresh", res.getString("refresh_token"))
            .putLong("exp", exp)
        if (email != null) ed.putString("email", email)
        ed.apply()
        publish()
    }

    @Synchronized
    private fun accessToken(forceRefresh: Boolean = false): String? {
        val p = prefs()
        val refresh = p.getString("refresh", null) ?: return null
        val exp = p.getLong("exp", 0)
        if (!forceRefresh && System.currentTimeMillis() / 1000 < exp - 60) return p.getString("access", null)
        return try {
            saveSession(authRequest("refresh_token", JSONObject().put("refresh_token", refresh)))
            p.getString("access", null)
        } catch (e: Exception) {
            if (e.message?.contains("auth 400") == true || e.message?.contains("auth 401") == true) logout()
            null
        }
    }

    // ------------------------------------------------------------------ detections
    /** Called for every decoded Remote ID update. Cheap; queues at most one row per drone per 5 s. */
    fun onDetection(d: DroneInfo) {
        if (!::app.isInitialized || !isConfigured) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - (lastSent[d.key] ?: 0) < RESEND_MS) return
            lastSent[d.key] = now
        }
        val loc = myLocation
        val row = JSONObject().apply {
            put("device_id", deviceId)
            put("seen_at", iso(now))
            put("source", d.source)
            put("drone_key", d.key)
            putOpt("serial", d.serial)
            putOpt("operator_id", d.operatorId)
            putOpt("description", d.description)
            put("ua_type", d.uaType)
            putOpt("drone_lat", d.lat); putOpt("drone_lon", d.lon)
            putOpt("drone_alt", d.altGeo); putOpt("drone_height", d.height)
            putOpt("speed", d.speed); putOpt("heading", d.heading)
            putOpt("op_lat", d.opLat); putOpt("op_lon", d.opLon)
            put("rssi", d.rssi)
            if (loc != null) {
                put("reporter_lat", loc.latitude); put("reporter_lon", loc.longitude)
                put("reporter_acc", loc.accuracy.toDouble())
            }
        }
        synchronized(queue) {
            queue.add(row)
            while (queue.size > MAX_QUEUE) queue.removeAt(0)
            saveQueue()
        }
        publish()
    }

    /** Blocking — send everything queued. Call periodically from a background thread. */
    fun flush() {
        if (!::app.isInitialized || !isConfigured || !isLoggedIn) return
        val batch: List<JSONObject> = synchronized(queue) { queue.take(100) }
        if (batch.isEmpty()) return
        val ok = post("/rest/v1/detections", JSONArray(batch).toString(), "return=minimal")
        if (ok) {
            synchronized(queue) { repeat(batch.size) { if (queue.isNotEmpty()) queue.removeAt(0) }; saveQueue() }
            sentCount += batch.size
            lastError = null
        }
        publish()
    }

    // ------------------------------------------------------------------ heartbeat
    fun heartbeat(scanning: Boolean) {
        if (!::app.isInitialized || !isConfigured || !isLoggedIn) return
        val bat = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pct = bat?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0) l * 100 / s else null
        }
        val loc = myLocation
        val row = JSONObject().apply {
            put("device_id", deviceId)
            put("kind", "phone")
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            putOpt("battery", pct)
            put("scanning", scanning)
            if (loc != null) { put("lat", loc.latitude); put("lon", loc.longitude) }
        }
        post("/rest/v1/devices?on_conflict=device_id", row.toString(), "resolution=merge-duplicates,return=minimal")
        publish()
    }

    private fun post(path: String, json: String, prefer: String, retried: Boolean = false): Boolean {
        val token = accessToken() ?: run { lastError = "انتهت الجلسة — سجّل الدخول"; return false }
        val req = Request.Builder()
            .url(url + path)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $token")
            .header("Prefer", prefer)
            .post(json.toRequestBody(JSON))
            .build()
        return try {
            http.newCall(req).execute().use { r ->
                when {
                    r.isSuccessful -> true
                    r.code == 401 && !retried -> { accessToken(forceRefresh = true); post(path, json, prefer, true) }
                    else -> { lastError = "السيرفر ${r.code}"; Log.w(TAG, "post $path ${r.code} ${r.body?.string()}"); false }
                }
            }
        } catch (e: Exception) {
            lastError = "لا يوجد اتصال"; false
        }
    }

    // ------------------------------------------------------------------ guard location
    private var locListener: LocationListener? = null

    @SuppressLint("MissingPermission")
    fun startLocation(ctx: Context) {
        if (locListener != null) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val l = LocationListener { loc -> if (better(loc)) myLocation = loc }
        locListener = l
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                if (lm.isProviderEnabled(p)) {
                    lm.getLastKnownLocation(p)?.let { if (better(it)) myLocation = it }
                    lm.requestLocationUpdates(p, 10_000L, 10f, l, Looper.getMainLooper())
                }
            }
        }
    }

    fun stopLocation(ctx: Context) {
        val l = locListener ?: return
        ctx.getSystemService(LocationManager::class.java)?.removeUpdates(l)
        locListener = null
    }

    private fun better(n: Location): Boolean {
        val c = myLocation ?: return true
        if (n.time - c.time > 30_000) return true
        return n.accuracy <= c.accuracy || n.time - c.time > 10_000
    }

    // ------------------------------------------------------------------ persistence
    private fun qFile() = File(app.filesDir, "upload_queue.json")
    private fun saveQueue() = runCatching { qFile().writeText(JSONArray(queue).toString()) }
    private fun loadQueue() = runCatching {
        val f = qFile(); if (!f.exists()) return@runCatching
        val a = JSONArray(f.readText())
        synchronized(queue) { for (i in 0 until a.length()) queue.add(a.getJSONObject(i)) }
    }

    private fun publish() {
        if (!::app.isInitialized) return
        _status.value = Status(isLoggedIn, email, sentCount, synchronized(queue) { queue.size }, lastError)
    }

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun iso(ms: Long) = synchronized(isoFmt) { isoFmt.format(Date(ms)) }
}
