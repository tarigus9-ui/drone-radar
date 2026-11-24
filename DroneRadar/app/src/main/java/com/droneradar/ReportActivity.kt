package com.droneradar

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Choose a period → build the PDF on the phone → share it (WhatsApp, e-mail…) or open it. */
class ReportActivity : AppCompatActivity() {

    private lateinit var info: TextView
    private lateinit var progress: ProgressBar
    private lateinit var shareBtn: Button
    private lateinit var openBtn: Button
    private var lastFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Backend.init(this)
        title = getString(R.string.rp_screen)
        val pad = (16 * resources.displayMetrics.density).toInt()

        info = TextView(this).apply { textSize = 15f; text = getString(R.string.rp_choose); setPadding(0, pad, 0, pad) }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        shareBtn = Button(this).apply { text = getString(R.string.rp_share); visibility = View.GONE; setOnClickListener { share() } }
        openBtn = Button(this).apply { text = getString(R.string.rp_open); visibility = View.GONE; setOnClickListener { open() } }

        fun periodButton(label: Int, range: () -> Pair<Calendar, Calendar>) = Button(this).apply {
            text = getString(label)
            setOnClickListener { val (a, b) = range(); generate(a, b, getString(label)) }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply { text = getString(R.string.rp_screen); textSize = 22f })
            addView(info)
            addView(periodButton(R.string.rp_this_month) { monthRange(0) })
            addView(periodButton(R.string.rp_last_month) { monthRange(-1) })
            addView(periodButton(R.string.rp_last_7) { lastDays(7) })
            addView(periodButton(R.string.rp_last_30) { lastDays(30) })
            addView(progress)
            addView(shareBtn)
            addView(openBtn)
        })
    }

    private fun monthRange(offset: Int): Pair<Calendar, Calendar> {
        val a = Calendar.getInstance().apply {
            add(Calendar.MONTH, offset); set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val b = (a.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        if (b.after(Calendar.getInstance())) return a to Calendar.getInstance()
        return a to b
    }

    private fun lastDays(n: Int): Pair<Calendar, Calendar> =
        Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -n) } to Calendar.getInstance()

    private fun iso(c: Calendar) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(c.time)

    private fun generate(from: Calendar, to: Calendar, label: String): kotlinx.coroutines.Job = lifecycleScope.launch {
        progress.visibility = View.VISIBLE; shareBtn.visibility = View.GONE; openBtn.visibility = View.GONE
        info.text = getString(R.string.rp_working)
        val df = SimpleDateFormat("d/M/yyyy", Locale.US)
        val toShow = (to.clone() as Calendar).apply { add(Calendar.SECOND, -1) }
        val period = "$label (${df.format(from.time)} – ${df.format(toShow.time)})"
        val args = JSONObject().put("p_from", iso(from)).put("p_to", iso(to)).put("p_tz", TimeZone.getDefault().id)
        val (code, body) = withContext(Dispatchers.IO) { Backend.call("POST", "/rest/v1/rpc/report_summary", args.toString()) }
        if (code !in 200..299) {
            progress.visibility = View.GONE
            info.text = Backend.errorText(code, body); return@launch
        }
        val file = withContext(Dispatchers.Default) {
            val j = JSONObject(body)
            val name = "DroneRadar_" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(from.time) + ".pdf"
            ReportPdf(this@ReportActivity, j, period).write(File(File(cacheDir, "reports"), name))
        }
        lastFile = file
        progress.visibility = View.GONE
        val j = JSONObject(body)
        info.text = getString(R.string.rp_done, j.optInt("incidents"), j.optInt("suspicious"), j.optInt("sightings"))
        shareBtn.visibility = View.VISIBLE; openBtn.visibility = View.VISIBLE
    }

    private fun uri(f: File) = FileProvider.getUriForFile(this, "$packageName.files", f)

    private fun share() {
        val f = lastFile ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri(f))
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.rp_title))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, getString(R.string.rp_share)))
    }

    private fun open() {
        val f = lastFile ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri(f), "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { share() }
    }
}
