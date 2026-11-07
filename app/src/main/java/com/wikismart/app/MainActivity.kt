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
    private lateinit var etNewPlug: EditText
    private lateinit var plugsBox: LinearLayout
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

    private fun makeButton(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun button(parent: LinearLayout, text: String, onClick: () -> Unit): MaterialButton {
        val b = makeButton(text, onClick)
        add(parent, b)
        return b
    }

    private fun smallButton(text: String, onClick: () -> Unit): MaterialButton =
        makeButton(text, onClick).apply {
            minimumWidth = 0
            minWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            insetTop = 0
            insetBottom = 0
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

        // 3) plugs
        section(root, getString(R.string.sec_plugs))
        note(root, getString(R.string.plugs_note))
        button(root, getString(R.string.btn_scan)) { scan() }
        plugsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(root, plugsBox, 10)
        etNewPlug = edit(root, getString(R.string.hint_new_plug))
        button(root, getString(R.string.btn_add_plug)) { addManualPlug() }
        val allRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        allRow.addView(
            makeButton(getString(R.string.btn_all_on)) { runPlugs("on", Prefs.plugs(this)) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        allRow.addView(
            makeButton(getString(R.string.btn_all_off)) { runPlugs("off", Prefs.plugs(this)) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        add(root, allRow, 10)
        etDevice = edit(root, getString(R.string.hint_device))
        etDevice.setText(Prefs.deviceName(this))
        button(root, getString(R.string.btn_test_on)) { runPlugs("on", emptyList()) }
        button(root, getString(R.string.btn_test_off)) { runPlugs("off", emptyList()) }

        // 4) AI
        section(root, getString(R.string.sec_ai))
        note(root, getString(R.string.ai_builtin))
        etKey = edit(root, getString(R.string.hint_key), secret = true)
        etKey.setText(Prefs.apiKey(this))
        etModel = edit(root, getString(R.string.hint_model))
        etModel.setText(Prefs.model(this))
        note(root, getString(R.string.ai_note))

        // 5) language
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

        // 6) run
        section(root, getString(R.string.sec_run))
        btnListen = button(root, getString(R.string.btn_start)) { toggleListening() }
        note(root, getString(R.string.examples))

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
        val scan = Bridge.pendingScan
        if (scan != null) {
            Bridge.pendingScan = null
            showScanDialog(scan)
        }
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
        rebuildPlugs()
    }

    private fun rebuildPlugs() {
        plugsBox.removeAllViews()
        val plugs = Prefs.plugs(this)
        if (plugs.isEmpty()) {
            plugsBox.addView(TextView(this).apply {
                text = getString(R.string.plugs_none)
                textSize = 14f
            })
            return
        }
        for (name in plugs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val icon = when (Prefs.plugState(this, name)) {
                "on" -> "🟢"
                "off" -> "⚪"
                else -> "❔"
            }
            row.addView(
                TextView(this).apply {
                    text = "$icon  $name"
                    textSize = 15f
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(smallButton(getString(R.string.plug_on)) { runPlugs("on", listOf(name)) })
            row.addView(smallButton(getString(R.string.plug_off)) { runPlugs("off", listOf(name)) })
            row.addView(smallButton("✕") {
                Prefs.setPlugs(this, Prefs.plugs(this).filter { it != name })
                refresh()
            })
            plugsBox.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(4) }
            )
        }
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

    // ------------------------------------------------------------------ plugs

    /** Opens the plug app, reads the device list and (when done) shows what it found. */
    private fun scan() {
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
        if (svc.isBusy) {
            toast(R.string.toast_busy)
            return
        }
        Bridge.log("🔎 " + getString(R.string.scan_started))
        toast(R.string.scan_started)
        svc.scanPlugs { names ->
            Bridge.log("🔎 " + names.size)
        }
    }

    private fun showScanDialog(names: List<String>) {
        if (names.isEmpty()) {
            toast(R.string.scan_none)
            return
        }
        val arr = names.toTypedArray()
        val checked = BooleanArray(arr.size) { true }
        AlertDialog.Builder(this)
            .setTitle(R.string.scan_result_title)
            .setMultiChoiceItems(arr, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.save) { _, _ ->
                val chosen = arr.filterIndexed { i, _ -> checked[i] }
                val merged = (Prefs.plugs(this) + chosen).distinctBy { Text.norm(it) }
                Prefs.setPlugs(this, merged)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addManualPlug() {
        val name = etNewPlug.text.toString().trim()
        if (name.isEmpty()) return
        val merged = (Prefs.plugs(this) + name).distinctBy { Text.norm(it) }
        Prefs.setPlugs(this, merged)
        etNewPlug.setText("")
        refresh()
    }

    /** Runs on/off for the given plugs (empty = the default plug / device name). */
    private fun runPlugs(action: String, targets: List<String>) {
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
        if (svc.isBusy) {
            toast(R.string.toast_busy)
            return
        }
        Bridge.log("🧪 $action ${targets.joinToString(",")}")
        svc.run(action, targets, Prefs.lang(this) == "ar") { ok, msg ->
            Bridge.log((if (ok) "✅ " else "⚠️ ") + msg)
        }
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
}
