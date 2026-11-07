package com.wikismart.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var tvPerm: TextView
    private lateinit var tvApp: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnListen: MaterialButton
    private lateinit var etDevice: EditText
    private lateinit var etKey: EditText
    private lateinit var etModel: EditText
    private lateinit var rbAr: RadioButton
    private lateinit var rbEn: RadioButton

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun add(parent: LinearLayout, v: View, top: Int = 8) {
        parent.addView(
            v,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(top) }
        )
    }

    private fun section(parent: LinearLayout, title: String) {
        val tv = TextView(this).apply {
            text = title
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.teal))
        }
        add(parent, tv, 26)
    }

    private fun note(parent: LinearLayout, text: String): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13f
            alpha = 0.8f
        }
        add(parent, tv, 6)
        return tv
    }

    private fun button(parent: LinearLayout, text: String, onClick: () -> Unit): MaterialButton {
        val b = MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        add(parent, b)
        return b
    }

    private fun edit(parent: LinearLayout, hint: String, secret: Boolean = false): EditText {
        val e = EditText(this).apply {
            this.hint = hint
            setSingleLine(true)
            inputType = if (secret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
        }
        add(parent, e)
        return e
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) },
            LinearLayout.LayoutParams(dp(64), dp(64))
        )
        header.addView(TextView(this).apply {
            text = "Wikismart"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), 0, dp(14), 0)
        })
        root.addView(header)
        note(root, getString(R.string.subtitle))

        // 1) permissions
        section(root, getString(R.string.sec_perms))
        button(root, getString(R.string.btn_perms)) { requestPerms() }
        button(root, getString(R.string.btn_a11y)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button(root, getString(R.string.btn_overlay)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        button(root, getString(R.string.btn_appinfo)) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        note(root, getString(R.string.restricted_hint))
        tvPerm = TextView(this).apply { textSize = 15f }
        add(root, tvPerm, 10)

        // 2) plug app
        section(root, getString(R.string.sec_app))
        tvApp = TextView(this).apply { textSize = 15f }
        add(root, tvApp)
        button(root, getString(R.string.btn_pick)) { pickApp() }
        etDevice = edit(root, getString(R.string.hint_device))
        etDevice.setText(Prefs.deviceName(this))

        // 3) AI
        section(root, getString(R.string.sec_ai))
        etKey = edit(root, getString(R.string.hint_key), secret = true)
        etKey.setText(Prefs.apiKey(this))
        etModel = edit(root, getString(R.string.hint_model))
        etModel.setText(Prefs.model(this))
        note(root, getString(R.string.ai_note))

        // 4) language
        section(root, getString(R.string.sec_lang))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        rbAr = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.lang_ar)
        }
        rbEn = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.lang_en)
        }
        group.addView(rbAr)
        group.addView(rbEn)
        add(root, group)
        if (Prefs.lang(this) == "en") rbEn.isChecked = true else rbAr.isChecked = true

        // 5) run
        section(root, getString(R.string.sec_run))
        btnListen = button(root, getString(R.string.btn_start)) { toggleListening() }
        button(root, getString(R.string.btn_test_on)) { test("on") }
        button(root, getString(R.string.btn_test_off)) { test("off") }

        // log
        section(root, getString(R.string.sec_log))
        tvLog = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        add(root, tvLog)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        Bridge.listener = { refresh() }
        refresh()
    }

    override fun onPause() {
        Bridge.listener = null
        saveSettings()
        super.onPause()
    }

    private fun mark(b: Boolean) = if (b) "✔" else "✘"

    private fun refresh() {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val a11y = PlugAccessibilityService.instance != null
        val overlay = Settings.canDrawOverlays(this)
        tvPerm.text = getString(R.string.perm_status, mark(mic), mark(a11y), mark(overlay))

        val label = Prefs.targetLabel(this)
        tvApp.text = if (label.isEmpty()) getString(R.string.app_none) else getString(R.string.app_selected, label)

        btnListen.text = getString(if (Bridge.running) R.string.btn_stop else R.string.btn_start)
        tvLog.text = Bridge.snapshot()
    }

    private fun saveSettings() {
        Prefs.setDeviceName(this, etDevice.text.toString())
        Prefs.setApiKey(this, etKey.text.toString())
        Prefs.setModel(this, etModel.text.toString())
        Prefs.setLang(this, if (rbEn.isChecked) "en" else "ar")
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_LONG).show()
    }

    private fun requestPerms() {
        val p = ArrayList<String>()
        p.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, p.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun pickApp() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        val names = apps.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_pick)
            .setItems(names) { _, which ->
                Prefs.setTarget(this, apps[which].second, apps[which].first)
                refresh()
            }
            .show()
    }

    private fun toggleListening() {
        saveSettings()
        if (Bridge.running) {
            stopService(Intent(this, WakeService::class.java))
            return
        }
        if (Prefs.targetPackage(this).isEmpty()) {
            toast(R.string.toast_pick_app)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPerms()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, WakeService::class.java))
    }

    private fun test(action: String) {
        saveSettings()
        if (Prefs.targetPackage(this).isEmpty()) {
            toast(R.string.toast_pick_app)
            return
        }
        val svc = PlugAccessibilityService.instance
        if (svc == null) {
            toast(R.string.toast_enable_a11y)
            return
        }
        Bridge.log("🧪 test: $action")
        svc.run(action, Prefs.lang(this) == "ar") { ok, msg ->
            Bridge.log((if (ok) "✅ " else "⚠️ ") + msg)
        }
    }
}
