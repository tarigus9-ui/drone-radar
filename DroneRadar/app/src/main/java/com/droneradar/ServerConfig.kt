package com.droneradar

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Which customer server this phone talks to. One APK for every customer: the server is set on
 * first run by scanning the customer's setup QR (made by deploy/install.sh) or typing it in.
 *
 * QR text (JSON):  {"v":1,"n":"Emaar","u":"https://10.0.0.5","k":"<anon key>","p":"<sha256 SPKI pin, base64>"}
 * "p" is used by on-prem servers with their own self-signed certificate: the phone then trusts
 * exactly that key and nothing else.
 */
object ServerConfig {
    data class Cfg(val name: String, val url: String, val key: String, val pin: String?)

    @Volatile private var cached: Cfg? = null
    @Volatile var version = 0; private set

    /** Encrypted storage (Android Keystore). Falls back to private prefs on broken keystores. */
    fun securePrefs(ctx: Context, name: String): SharedPreferences {
        val app = ctx.applicationContext
        return runCatching {
            val mk = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(app, "${name}_secure", mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.getOrElse { app.getSharedPreferences(name, Context.MODE_PRIVATE) }
    }

    private fun prefs(ctx: Context) = securePrefs(ctx, "server")

    /** Saved server, else the server built into this APK (if any). */
    fun current(ctx: Context): Cfg? {
        cached?.let { return it }
        val p = prefs(ctx)
        val u = p.getString("u", null)
        val c = if (u != null) Cfg(p.getString("n", "") ?: "", u, p.getString("k", "") ?: "", p.getString("p", null))
        else BuildConfig.SUPABASE_URL.takeIf { it.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank() }
            ?.let { Cfg("DroneRadar Cloud", it.trimEnd('/'), BuildConfig.SUPABASE_ANON_KEY, null) }
        cached = c
        return c
    }

    fun isSet(ctx: Context) = current(ctx) != null

    fun save(ctx: Context, c: Cfg) {
        prefs(ctx).edit().putString("n", c.name).putString("u", c.url).putString("k", c.key)
            .putString("p", c.pin).apply()
        cached = c; version++
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        cached = null; version++
    }

    /** Parse QR / pasted text. Returns null if it is not a valid DroneRadar setup code. */
    fun parse(text: String): Cfg? = runCatching {
        var t = text.trim()
        if (t.startsWith("droneradar://")) {
            val d = android.net.Uri.parse(t).getQueryParameter("d") ?: return null
            t = String(Base64.decode(d, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        }
        val j = JSONObject(t)
        val url = j.getString("u").trim().trimEnd('/')
        val key = j.getString("k").trim()
        if (!url.startsWith("https://") || key.length < 20) return null
        Cfg(j.optString("n", "").trim().ifBlank { android.net.Uri.parse(url).host ?: url }, url, key,
            j.optString("p", "").trim().ifBlank { null })
    }.getOrNull()

    /** HTTP client for this server: normal TLS, or — when a pin is set — only the pinned key. */
    fun httpClient(c: Cfg?): OkHttpClient {
        val b = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        val pin = c?.pin ?: return b.build()
        val tm = PinnedTrustManager(pin)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        // The certificate is self-signed for an IP / LAN name: identity is proven by the pinned key.
        return b.sslSocketFactory(ssl.socketFactory, tm).hostnameVerifier { _, _ -> true }.build()
    }

    fun spkiPin(cert: X509Certificate): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded), Base64.NO_WRAP)

    private class PinnedTrustManager(private val pin: String) : X509TrustManager {
        private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.none { spkiPin(it) == pin }) throw CertificateException("server key does not match setup code")
        }
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }
}
