package com.droneradar

import android.content.Context
import org.json.JSONArray

/**
 * Authorized drones (company drones, filming permits). Cached on the phone so even a guard
 * offline gets no alarm for them. Managers edit the list in the admin screen.
 */
object Whitelist {
    @Volatile private var serials: Map<String, String> = emptyMap()   // SERIAL → label

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("whitelist", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val raw = prefs(ctx).getString("list", null) ?: return
        serials = parse(raw)
    }

    fun contains(serial: String?): Boolean = serial != null && serials.containsKey(serial.trim().uppercase())
    fun label(serial: String?): String? = serial?.let { serials[it.trim().uppercase()] }

    /** Blocking — refresh from the server (all active entries of the company). */
    fun refresh(ctx: Context) {
        if (!Backend.isLoggedIn) return
        val (code, body) = Backend.call("POST", "/rest/v1/rpc/list_whitelist", "{}")
        if (code !in 200..299) return
        serials = parse(body)
        prefs(ctx).edit().putString("list", body).apply()
    }

    private fun parse(raw: String): Map<String, String> = runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optBoolean("active", true) }
            .associate { it.getString("serial").trim().uppercase() to (it.optString("label", "").ifBlank { "مصرّح" }) }
    }.getOrDefault(emptyMap())
}
