package com.droneradar

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Company login: each guard signs in with the account his security manager created. */
class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Backend.init(this)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply {
            text = getString(R.string.login_title); textSize = 24f; gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply {
            text = getString(R.string.login_sub); textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, pad / 3, 0, pad)
        }
        val email = EditText(this).apply {
            hint = getString(R.string.email)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            textDirection = View.TEXT_DIRECTION_LTR
            setText(Backend.email ?: "")
        }
        val pass = EditText(this).apply {
            hint = getString(R.string.password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val msg = TextView(this).apply { setTextColor(0xFFC62828.toInt()); setPadding(0, pad / 3, 0, 0) }
        val progress = ProgressBar(this).apply { visibility = View.GONE }
        val btn = Button(this).apply { text = getString(R.string.login) }
        val offline = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.offline_mode)
        }

        btn.setOnClickListener {
            val e = email.text.toString(); val p = pass.text.toString()
            if (e.isBlank() || p.isBlank()) { msg.text = getString(R.string.fill_fields); return@setOnClickListener }
            btn.isEnabled = false; progress.visibility = View.VISIBLE; msg.text = ""
            lifecycleScope.launch {
                val err = withContext(Dispatchers.IO) { Backend.login(e, p) }
                progress.visibility = View.GONE; btn.isEnabled = true
                if (err == null) openMain() else msg.text = err
            }
        }
        offline.setOnClickListener {
            getSharedPreferences("backend", MODE_PRIVATE).edit().putBoolean("offline_ok", true).apply()
            openMain()
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(pad, pad, pad, pad)
            addView(title); addView(sub); addView(email); addView(pass)
            addView(btn); addView(progress); addView(msg); addView(offline)
        })
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
