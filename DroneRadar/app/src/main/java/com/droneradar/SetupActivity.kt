package com.droneradar

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * First run (or "change server"): connect this phone to the customer's own DroneRadar server
 * by scanning the setup QR printed by the installer, or by typing the address and key.
 */
class SetupActivity : AppCompatActivity() {

    private val done = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var preview: PreviewView
    private lateinit var msg: TextView

    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else msg.text = getString(R.string.setup_cam_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Backend.init(this)
        val pad = (20 * resources.displayMetrics.density).toInt()

        preview = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        msg = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setPadding(0, pad / 2, 0, pad / 2) }
        val current = ServerConfig.current(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply { text = getString(R.string.setup_title); textSize = 22f; gravity = Gravity.CENTER })
            addView(TextView(context).apply {
                text = getString(R.string.setup_sub); textSize = 14f; gravity = Gravity.CENTER
                setPadding(0, pad / 3, 0, pad / 2)
            })
            if (current != null) addView(TextView(context).apply {
                text = getString(R.string.setup_current, current.name); textSize = 13f; gravity = Gravity.CENTER
            })
            addView(FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                addView(preview)
            })
            addView(msg)
            addView(Button(context).apply { text = getString(R.string.setup_paste); setOnClickListener { fromClipboard() } })
            addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = getString(R.string.setup_manual); setOnClickListener { manualDialog() }
            })
            addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = getString(R.string.language); setOnClickListener { Lang.pick(this@SetupActivity) }
            })
            if (BuildConfig.SUPABASE_URL.isNotBlank()) addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = getString(R.string.setup_demo); setOnClickListener { apply(null) }
            })
        }
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else camPerm.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() { exec.shutdown(); super.onDestroy() }

    private fun startCamera() {
        msg.text = getString(R.string.setup_point)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            val reader = MultiFormatReader().apply {
                setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
            }
            analysis.setAnalyzer(exec) { img -> decode(reader, img) }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis)
            }.onFailure { msg.text = getString(R.string.setup_cam_denied) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun decode(reader: MultiFormatReader, img: ImageProxy) {
        try {
            if (done.get()) return
            val plane = img.planes[0]
            val buf = plane.buffer
            val data = ByteArray(buf.remaining()).also { buf.get(it) }
            val src = PlanarYUVLuminanceSource(data, plane.rowStride, img.height, 0, 0, img.width, img.height, false)
            val text = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text }.getOrNull()
                ?: return
            val cfg = ServerConfig.parse(text)
            runOnUiThread {
                if (cfg == null) msg.text = getString(R.string.setup_bad_code)
                else if (done.compareAndSet(false, true)) confirm(cfg)
            }
        } finally {
            reader.reset()
            img.close()
        }
    }

    private fun fromClipboard() {
        val t = getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        val cfg = t?.let { ServerConfig.parse(it) }
        if (cfg == null) msg.text = getString(R.string.setup_bad_code) else confirm(cfg)
    }

    private fun manualDialog() {
        val u = EditText(this).apply {
            hint = "https://…"; inputType = InputType.TYPE_TEXT_VARIATION_URI; textDirection = View.TEXT_DIRECTION_LTR
        }
        val k = EditText(this).apply { hint = getString(R.string.setup_key); textDirection = View.TEXT_DIRECTION_LTR }
        val p = EditText(this).apply { hint = getString(R.string.setup_pin); textDirection = View.TEXT_DIRECTION_LTR }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0)
            addView(u); addView(k); addView(p)
        }
        AlertDialog.Builder(this).setTitle(R.string.setup_manual).setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val json = org.json.JSONObject().put("u", u.text.toString()).put("k", k.text.toString())
                    .put("p", p.text.toString()).toString()
                val cfg = ServerConfig.parse(json)
                if (cfg == null) msg.text = getString(R.string.setup_bad_code) else confirm(cfg)
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun confirm(cfg: ServerConfig.Cfg) {
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_confirm_title)
            .setMessage(getString(R.string.setup_confirm, cfg.name, cfg.url) +
                if (cfg.pin != null) "\n" + getString(R.string.setup_pinned) else "")
            .setPositiveButton(R.string.ok) { _, _ -> apply(cfg) }
            .setNegativeButton(R.string.cancel) { _, _ -> done.set(false) }
            .setCancelable(false).show()
    }

    /** null = the demo/cloud server built into this APK. */
    private fun apply(cfg: ServerConfig.Cfg?) {
        Backend.switchServer(cfg)
        ManagerAlerts.reset(this)
        Whitelist.clear(this)
        getSharedPreferences("backend", MODE_PRIVATE).edit().putBoolean("offline_ok", false).apply()
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
