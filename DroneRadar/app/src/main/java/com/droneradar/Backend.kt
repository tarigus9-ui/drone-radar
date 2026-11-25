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

    private val cfg: ServerConfig.Cfg? get() = if (::app.isInitialized) ServerConfig.current(app) else null
    val url: String get() = cfg?.url?.trimEnd('/') ?: ""
    private val anonKey: String get() = cfg?.key ?: ""
    val isConfigured: Boolean get() = url.startsWith("https://") && anonKey.isNotBlank()
    val serverName: String? get() = cfg?.name

    private lateinit var app: Context
    /** Localized text (follows the in-app language). */
    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)
    private var httpVersion = -1
    private var httpClient: OkHttpClient = OkHttpClient()
    /** Rebuilt when the customer server (or its certificate pin) changes. */
    private val http: OkHttpClient
        @Synchronized get() {
            if (httpVersion != ServerConfig.version) { httpClient = ServerConfig.httpClient(cfg); httpVersion = ServerConfig.version }
            return httpClient
        }
    private val JSON = "application/json".toMediaType()

    private val queue = ArrayList<JSONObject>()
    private val lastSent = HashMap<String, Long>()
    @Volatile var myLocation: Location? = null; private set

    data class Status(val loggedIn: Boolean, val email: String?, val sent: Int, val pending: Int, val lastError: String?, val rev: Int = 0)
    private val _status = MutableStateFlow(Status(false, null, 0, 0, null))
    val status: StateFlow<Status> = _status
    private var sentCount = 0
    private var rev = 0
    private var lastError: String? = null

    @Synchronized
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        loadQueue()
        publish()
    }

    @Volatile private var securePrefs: android.content.SharedPreferences? = null
    /** Session tokens live in encrypted storage (moved once from the old plain file). */
    private fun prefs(): android.content.SharedPreferences {
        securePrefs?.let { return it }
        synchronized(this) {
            securePrefs?.let { return it }
            val sp = ServerConfig.securePrefs(app, "backend")
            if (!sp.getBoolean("migrated", false)) {
                val old = app.getSharedPreferences("backend", Context.MODE_PRIVATE)
                val ed = sp.edit()
                for (k in listOf("access", "refresh", "email", "who", "device_id")) old.getString(k, null)?.let { ed.putString(k, it) }
                if (old.contains("exp")) ed.putLong("exp", old.getLong("exp", 0))
                ed.putBoolean("migrated", true).apply()
                old.edit().remove("access").remove("refresh").remove("exp").remove("email").remove("who").remove("device_id").apply()
            }
            securePrefs = sp
            return sp
        }
    }

    /** Switch to another customer server: sign out and drop everything of the old one. */
    fun switchServer(c: ServerConfig.Cfg?) {
        logout()
        synchronized(queue) { queue.clear(); saveQueue() }
        prefs().edit().remove("pending_invite").apply()
        if (c == null) ServerConfig.clear(app) else ServerConfig.save(app, c)
        lastError = null
        publish()
    }
    val isLoggedIn: Boolean get() = ::app.isInitialized && prefs().getString("refresh", null) != null
    val email: String? get() = prefs().getString("email", null)

    val deviceId: String
        get() = prefs().getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs().edit().putString("device_id", it).apply()
        }

    // ------------------------------------------------------------------ auth
    /** Blocking — call from a background thread. Returns null on success or an error message. */
    fun login(email: String, password: String): String? {
        if (!isConfigured) return str(R.string.e_not_configured)
        val body = JSONObject().put("email", email.trim()).put("password", password)
        return try {
            val res = authRequest("password", body)
            saveSession(res, email.trim())
            redeemPendingInvite()?.let { lastError = it; publish() }
            null
        } catch (e: Exception) {
            Log.w(TAG, "login", e)
            when {
                e.message?.contains("invalid", true) == true -> str(R.string.e_bad_login)
                else -> str(R.string.e_no_server)
            }
        }
    }

    fun logout() {
        prefs().edit().remove("access").remove("refresh").remove("exp").remove("email")
            .remove("who").apply()
        publish()
    }

    /** Blocking. New staff create their account with the one-time invite code from their admin. */
    fun signUp(email: String, password: String, invite: String, fullName: String?): String? {
        if (!isConfigured) return str(R.string.e_not_configured)
        if (password.length < 8) return str(R.string.e_pw_short)
        val code = invite.uppercase().filter { it.isLetterOrDigit() }
        if (code.length != 10) return str(R.string.e_need_invite)
        prefs().edit().putString("pending_invite", code).putString("pending_name", fullName?.trim().orEmpty()).apply()
        val req = Request.Builder()
            .url("$url/auth/v1/signup")
            .header("apikey", anonKey)
            .post(JSONObject().put("email", email.trim()).put("password", password).toString().toRequestBody(JSON))
            .build()
        return try {
            http.newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    // Existing account (e.g. a code that failed earlier): log in and use the new code.
                    return if (txt.contains("already", true)) login(email, password) else str(R.string.e_signup, r.code)
                }
                val j = JSONObject(txt)
                if (j.has("access_token")) { saveSession(j, email.trim()); redeemPendingInvite() }
                else str(R.string.e_confirm_mail)
            }
        } catch (e: Exception) { str(R.string.e_no_server) }
    }

    /** Blocking. Joins the company with a stored invite code (after sign-up / first login). */
    fun redeemPendingInvite(): String? {
        val code = prefs().getString("pending_invite", null) ?: return null
        val name = prefs().getString("pending_name", null)
        val (c, body) = call("POST", "/rest/v1/rpc/redeem_invite",
            JSONObject().put("p_code", code).put("p_full_name", name ?: JSONObject.NULL).toString())
        if (c in 200..299 || body.contains("already belongs")) {
            prefs().edit().remove("pending_invite").remove("pending_name").apply()
            return null
        }
        prefs().edit().remove("pending_invite").remove("pending_name").apply()
        return if (c == 0) str(R.string.e_no_server) else str(R.string.e_bad_invite)
    }

    // ------------------------------------------------------------------ profile / role
    data class Who(val hasProfile: Boolean, val role: String?, val name: String?, val company: String?,
                   val region: String?, val site: String?, val companyId: String? = null)

    val who: Who?
        get() = prefs().getString("who", null)?.let { parseWho(JSONObject(it)) }

    val isManager: Boolean get() = who?.role in setOf("admin", "region_manager", "site_manager")
    val isAdmin: Boolean get() = who?.role == "admin"

    private fun parseWho(j: JSONObject) = Who(
        j.optBoolean("has_profile"), j.optStr("role"), j.optStr("full_name"),
        j.optStr("company"), j.optStr("region"), j.optStr("site"), j.optStr("company_id"))

    private fun JSONObject.optStr(k: String): String? = if (isNull(k)) null else optString(k, "").ifBlank { null }

    /** Blocking — refresh role/company from the server. */
    fun refreshWho(): Who? {
        if (!isLoggedIn) return null
        val (code, body) = call("POST", "/rest/v1/rpc/whoami", "{}")
        if (code !in 200..299) return who
        val j = runCatching { JSONObject(body) }.getOrNull() ?: return who
        prefs().edit().putString("who", j.toString()).apply()
        publish()
        return parseWho(j)
    }

    /** Blocking — call any REST path or RPC. Returns (http code, body). code 0 = no network. */
    fun call(method: String, path: String, json: String? = null, prefer: String? = null, retried: Boolean = false): Pair<Int, String> {
        val token = accessToken() ?: return 401 to "no session"
        val b = Request.Builder()
            .url(url + path)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $token")
        if (prefer != null) b.header("Prefer", prefer)
        val body = json?.toRequestBody(JSON)
        when (method) {
            "GET" -> b.get()
            "DELETE" -> b.delete(body)
            else -> b.method(method, body ?: "{}".toRequestBody(JSON))
        }
        return try {
            http.newCall(b.build()).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (r.code == 401 && !retried) { accessToken(forceRefresh = true); return call(method, path, json, prefer, true) }
                r.code to txt
            }
        } catch (e: Exception) { 0 to (e.message ?: "network") }
    }

    /** Logged-in user's id (from the access token). */
    fun userId(): String? = runCatching {
        val tok = accessToken() ?: return null
        val payload = tok.split(".")[1]
        val json = String(android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING))
        JSONObject(json).getString("sub")
    }.getOrNull()

    /** Blocking — upload a file to Supabase Storage. Returns true on success. */
    fun upload(bucketPath: String, bytes: ByteArray, contentType: String): Boolean {
        val token = accessToken() ?: return false
        val req = Request.Builder()
            .url("$url/storage/v1/object/$bucketPath")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $token")
            .header("x-upsert", "true")
            .post(bytes.toRequestBody(contentType.toMediaType()))
            .build()
        return runCatching { http.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** Blocking — download a private file from Supabase Storage. */
    fun download(bucketPath: String): ByteArray? {
        val token = accessToken() ?: return null
        val req = Request.Builder()
            .url("$url/storage/v1/object/authenticated/$bucketPath")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $token")
            .get().build()
        return runCatching { http.newCall(req).execute().use { if (it.isSuccessful) it.body?.bytes() else null } }.getOrNull()
    }

    /** Server error text → short Arabic message. */
    fun errorText(code: Int, body: String): String = when {
        code == 0 -> str(R.string.e_no_internet)
        body.contains("admin only") -> str(R.string.e_admin_only)
        body.contains("no account") -> str(R.string.e_no_account)
        body.contains("another company") -> str(R.string.e_other_company)
        body.contains("not in your company") -> str(R.string.e_not_yours)
        body.contains("cannot remove yourself") -> str(R.string.e_not_self)
        body.contains("managers only") -> str(R.string.e_managers_only)
        body.contains("own site only") -> str(R.string.e_own_site)
        body.contains("serial required") -> str(R.string.e_serial_req)
        body.contains("region managers") -> str(R.string.e_region_only)
        body.contains("rate limit") -> str(R.string.e_rate)
        else -> str(R.string.e_server, code)
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
        if (!::app.isInitialized || !isConfigured || d.remote) return
        val now = System.currentTimeMillis()
        // The serial (Basic ID) usually arrives a second after the first position. Wait for it (up to
        // 10 s) so the server does not split one flight into a MAC-address incident and a serial one.
        if (d.serial == null && d.operatorId == null && now - d.firstSeen < 10_000) return
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
            putOpt("id_type", d.idType)
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
        // PostgREST bulk insert rejects (400) rows whose key sets differ, so give every row
        // the same keys, using null where a row lacks a value.
        val keys = batch.flatMap { it.keys().asSequence().toList() }.toSet()
        val rows = JSONArray()
        batch.forEach { r -> rows.put(JSONObject().apply { keys.forEach { k -> put(k, r.opt(k) ?: JSONObject.NULL) } }) }
        val ok = post("/rest/v1/detections", rows.toString(), "return=minimal")
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
        val token = accessToken() ?: run { lastError = str(R.string.e_session); return false }
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
                    else -> {
                        val msg = runCatching { JSONObject(r.body?.string() ?: "").optString("message") }.getOrNull().orEmpty()
                        lastError = str(R.string.e_server_msg, r.code) + if (msg.isNotBlank()) " — ${msg.take(80)}" else ""
                        Log.w(TAG, "post $path ${r.code} $msg"); false
                    }
                }
            }
        } catch (e: Exception) {
            lastError = str(R.string.e_offline); false
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
        _status.value = Status(isLoggedIn, email, sentCount, synchronized(queue) { queue.size }, lastError, ++rev)
    }

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun iso(ms: Long) = synchronized(isoFmt) { isoFmt.format(Date(ms)) }
}
