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
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Company login. New staff: "new account" + the one-time invite code their admin created
 * (the code puts them straight into the right company, site and role).
 */
class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Backend.init(this)
        if (!ServerConfig.isSet(this)) { startActivity(Intent(this, SetupActivity::class.java)); finish(); return }
        val pad = (24 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply {
            text = getString(R.string.login_title); textSize = 24f; gravity = Gravity.CENTER
        }
        val server = TextView(this).apply {
            text = getString(R.string.login_server, Backend.serverName ?: ""); textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, pad / 3, 0, pad / 2)
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
        val invite = EditText(this).apply {
            hint = getString(R.string.invite_code)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            textDirection = View.TEXT_DIRECTION_LTR
            visibility = View.GONE
        }
        val fullName = EditText(this).apply { hint = getString(R.string.full_name); visibility = View.GONE }
        val msg = TextView(this).apply { setTextColor(0xFFC62828.toInt()); setPadding(0, pad / 3, 0, 0) }
        val progress = ProgressBar(this).apply { visibility = View.GONE }
        val btn = Button(this).apply { text = getString(R.string.login) }
        val signup = Button(this, null, android.R.attr.borderlessButtonStyle).apply { text = getString(R.string.signup) }
        val change = Button(this, null, android.R.attr.borderlessButtonStyle).apply { text = getString(R.string.change_server) }
        val offline = Button(this, null, android.R.attr.borderlessButtonStyle).apply { text = getString(R.string.offline_mode) }
        val lang = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.language); setOnClickListener { Lang.pick(this@LoginActivity) }
        }
        var newAccount = false

        fun busy(b: Boolean) { btn.isEnabled = !b; signup.isEnabled = !b; progress.visibility = if (b) View.VISIBLE else View.GONE }

        btn.setOnClickListener {
            val e = email.text.toString(); val p = pass.text.toString()
            if (e.isBlank() || p.isBlank()) { msg.text = getString(R.string.fill_fields); return@setOnClickListener }
            busy(true); msg.text = ""
            lifecycleScope.launch {
                val err = withContext(Dispatchers.IO) {
                    if (newAccount) Backend.signUp(e, p, invite.text.toString(), fullName.text.toString())
                    else Backend.login(e, p)
                }.also { if (it == null) withContext(Dispatchers.IO) { Backend.refreshWho() } }
                busy(false)
                if (err == null) openMain() else msg.text = err
            }
        }
        signup.setOnClickListener {
            newAccount = !newAccount
            invite.visibility = if (newAccount) View.VISIBLE else View.GONE
            fullName.visibility = invite.visibility
            btn.text = getString(if (newAccount) R.string.create_account else R.string.login)
            signup.text = getString(if (newAccount) R.string.have_account else R.string.signup)
            msg.text = if (newAccount) getString(R.string.invite_hint) else ""
        }
        change.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        offline.setOnClickListener {
            getSharedPreferences("backend", MODE_PRIVATE).edit().putBoolean("offline_ok", true).apply()
            openMain()
        }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                setPadding(pad, pad, pad, pad)
                addView(title); addView(server); addView(email); addView(pass); addView(invite); addView(fullName)
                addView(btn); addView(progress); addView(msg); addView(signup); addView(change); addView(offline); addView(lang)
            })
        })
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
