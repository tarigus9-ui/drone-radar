package com.droneradar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
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
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Compass helpers shared with alerts. */
object Compass {
    fun dirName(ctx: Context, deg: Float): String {
        val names = ctx.resources.getStringArray(R.array.compass_dirs)
        val i = (((deg % 360) + 360) % 360 / 45f).roundToInt() % 8
        return names[i]
    }
}

/**
 * "I saw a drone": for drones that do NOT broadcast Remote ID.
 * The guard points the camera at the drone; we record true-north bearing, elevation angle,
 * GPS position and a photo. Two guards reporting the same drone → the server triangulates it.
 */
class SightingActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var preview: PreviewView
    private lateinit var info: TextView
    private lateinit var calib: TextView
    private lateinit var note: EditText
    private lateinit var send: Button
    private var imageCapture: ImageCapture? = null
    private lateinit var sm: SensorManager

    // smoothed orientation
    private var sinA = 0.0; private var cosA = 1.0
    private var bearing = 0f; private var elevation = 0f
    private var haveReading = false

    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else info.text = getString(R.string.need_camera)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Backend.init(this)
        sm = getSystemService(SensorManager::class.java)
        val pad = (12 * resources.displayMetrics.density).toInt()

        preview = PreviewView(this)
        val cross = TextView(this).apply {
            text = "＋"; textSize = 64f; setTextColor(Color.RED); gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }
        info = TextView(this).apply {
            textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt()); setPadding(pad, pad, pad, pad)
            text = getString(R.string.aim_hint)
        }
        calib = TextView(this).apply {
            textSize = 13f; setTextColor(0xFFFFD54F.toInt()); gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt()); setPadding(pad, 0, pad, pad / 2)
            text = getString(R.string.calib_hint)
        }
        note = EditText(this).apply {
            hint = getString(R.string.sighting_note); setTextColor(Color.WHITE); setHintTextColor(0xFFBBBBBB.toInt())
            setBackgroundColor(0x99000000.toInt()); setPadding(pad, pad, pad, pad)
        }
        send = Button(this).apply {
            text = getString(R.string.sighting_send); textSize = 20f
            setBackgroundColor(0xFFD32F2F.toInt()); setTextColor(Color.WHITE)
            setOnClickListener { submit() }
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(info); addView(calib) }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad * 2)
            addView(note); addView(send, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (64 * resources.displayMetrics.density).toInt()).apply { topMargin = pad })
        }
        setContentView(FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.BLACK)
            addView(preview)
            addView(cross, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(top, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else camPerm.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            ?: run { info.text = getString(R.string.no_compass) }
    }

    override fun onPause() { sm.unregisterListener(this); super.onPause() }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val provider = f.get()
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, imageCapture)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------- compass ----------
    private val rot = FloatArray(9); private val remap = FloatArray(9); private val ori = FloatArray(3)

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // camera looks along the device's -Z axis: remap so azimuth/pitch describe where the camera points
        SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remap)
        SensorManager.getOrientation(remap, ori)
        val az = ori[0].toDouble()
        // circular low-pass filter
        val k = if (haveReading) 0.15 else 1.0
        sinA += k * (sin(az) - sinA); cosA += k * (cos(az) - cosA)
        haveReading = true
        val loc = Backend.myLocation
        val decl = loc?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination } ?: 0f
        bearing = ((Math.toDegrees(atan2(sinA, cosA)).toFloat() + decl) % 360 + 360) % 360
        elevation = -Math.toDegrees(ori[1].toDouble()).toFloat()
        info.text = getString(R.string.aim_reading, bearing.roundToInt(), Compass.dirName(this, bearing), elevation.roundToInt())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        calib.visibility = if (accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) View.VISIBLE else View.GONE
    }

    // ---------- send ----------
    private fun submit() {
        val loc = Backend.myLocation
        if (!haveReading) { toast(getString(R.string.no_compass)); return }
        if (loc == null) { toast(getString(R.string.no_gps)); return }
        if (!Backend.isLoggedIn) { toast(getString(R.string.acc_offline)); return }
        val b = bearing; val el = elevation           // freeze the aim at the moment of pressing
        send.isEnabled = false; send.text = getString(R.string.sending)

        val cap = imageCapture
        if (cap == null) { upload(null, b, el, loc); return }
        cap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val jpg = runCatching { toJpeg(image) }.getOrNull()
                image.close()
                upload(jpg, b, el, loc)
            }
            override fun onError(exception: ImageCaptureException) { upload(null, b, el, loc) }
        })
    }

    private fun toJpeg(image: ImageProxy): ByteArray {
        var bmp = image.toBitmap()
        val rotDeg = image.imageInfo.rotationDegrees
        if (rotDeg != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotDeg.toFloat()) }, true)
        val scale = 1280f / maxOf(bmp.width, bmp.height)
        if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        return ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 75, it); it.toByteArray() }
    }

    private fun upload(jpg: ByteArray?, b: Float, el: Float, loc: android.location.Location): kotlinx.coroutines.Job = lifecycleScope.launch {
        val noteText = note.text.toString().trim()
        val result = withContext(Dispatchers.IO) {
            var photoPath: String? = null
            if (jpg != null) {
                val uid = Backend.userId()
                val cid = Backend.who?.companyId ?: Backend.refreshWho()?.companyId
                if (uid != null && cid != null) {
                    val path = "$cid/$uid/${System.currentTimeMillis()}.jpg"
                    if (Backend.upload("sightings/$path", jpg, "image/jpeg")) photoPath = path
                }
            }
            val row = JSONObject()
                .put("lat", loc.latitude).put("lon", loc.longitude).put("accuracy", loc.accuracy.toDouble())
                .put("bearing", b.toDouble()).put("elevation", el.toDouble())
                .put("photo_path", photoPath ?: JSONObject.NULL)
            row.put("note", if (noteText.isEmpty()) JSONObject.NULL else noteText)
            Backend.call("POST", "/rest/v1/sightings", row.toString(), "return=representation")
        }
        val (code, body) = result
        if (code !in 200..299) {
            send.isEnabled = true; send.text = getString(R.string.sighting_send)
            toast(Backend.errorText(code, body)); return@launch
        }
        val est = runCatching { JSONArray(body).getJSONObject(0) }.getOrNull()?.takeIf { !it.isNull("est_lat") }
        AlertDialog.Builder(this@SightingActivity)
            .setTitle(R.string.sighting_sent)
            .setMessage(if (est != null) getString(R.string.sighting_fixed) else getString(R.string.sighting_sent_msg))
            .setPositiveButton(R.string.ok) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()
}
