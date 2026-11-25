package com.droneradar

import android.content.Context
import org.json.JSONArray

/**
 * Authorized drones (company drones, filming permits). Cached on the phone so even a guard
 * offline gets no alarm for them. Managers edit the list in the admin screen.
 */
object Whitelist {
    /** sha256(SERIAL) → entry. The server gives guards hashes only, never the serial list. */
    private data class Entry(val label: String, val opHash: String?)
    @Volatile private var serials: Map<String, Entry> = emptyMap()

    private fun sha(s: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(s.trim().uppercase().toByteArray()).joinToString("") { "%02x".format(it) }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("whitelist", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val raw = prefs(ctx).getString("list", null) ?: return
        serials = parse(raw)
    }

    /** Authorized = serial matches and, if the entry is bound to an operator ID, that matches too. */
    fun contains(serial: String?, operatorId: String? = null): Boolean {
        val e = serial?.let { serials[sha(it)] } ?: return false
        return e.opHash == null || (operatorId != null && sha(operatorId) == e.opHash)
    }
    fun label(serial: String?): String? = serial?.let { serials[sha(it)]?.label }

    fun clear(ctx: Context) { serials = emptyMap(); prefs(ctx).edit().clear().apply() }

    /** Blocking — refresh from the server (all active entries of the company). */
    fun refresh(ctx: Context) {
        if (!Backend.isLoggedIn) return
        val (code, body) = Backend.call("POST", "/rest/v1/rpc/list_whitelist", "{}")
        if (code !in 200..299) return
        serials = parse(body)
        prefs(ctx).edit().putString("list", body).apply()
    }

    private fun parse(raw: String): Map<String, Entry> = runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optBoolean("active", true) && it.has("h") }
            .associate {
                it.getString("h") to Entry(
                    it.optString("label", "").takeIf { l -> l.isNotBlank() && l != "null" } ?: "مصرّح",
                    if (it.isNull("oh")) null else it.optString("oh").ifBlank { null })
            }
    }.getOrDefault(emptyMap())
}
