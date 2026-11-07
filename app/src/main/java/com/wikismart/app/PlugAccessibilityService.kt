package com.wikismart.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Opens the plug's own app and presses its on/off control.
 * Looks for a real switch first, then for buttons labelled On/Off (Arabic or English),
 * and finally for a generic "power" button.
 */
class PlugAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PlugAccessibilityService? = null
    }

    private val main = Handler(Looper.getMainLooper())

    private var wantOn = true
    private var ar = true
    private var pkg = ""
    private var device = ""
    private var callback: ((Boolean, String) -> Unit)? = null
    private var startedAt = 0L
    private var deviceClicked = true
    private var token = 0

    // Labels are already normalized (see Text.norm).
    private val onLabels = listOf("turn on", "power on", "switch on", "تشغيل", "شغل", "فتح")
    private val offLabels = listOf("turn off", "power off", "switch off", "ايقاف", "اطفاء", "اطفي", "اغلاق", "قفل")
    private val genericLabels = listOf("power", "toggle", "switch", "طاقه", "الطاقه")

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Bridge.log("✔ Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Runs on the main thread. The callback is called once with (success, message). */
    fun run(action: String, arabic: Boolean, cb: (Boolean, String) -> Unit) {
        val c = applicationContext
        pkg = Prefs.targetPackage(c)
        device = Text.norm(Prefs.deviceName(c))
        wantOn = action == "on"
        ar = arabic
        callback = cb
        deviceClicked = device.isEmpty()
        startedAt = System.currentTimeMillis()
        val t = ++token

        val launch = if (pkg.isEmpty()) null else packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            finish(t, false, Msgs.noApp(ar))
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            startActivity(launch)
        } catch (e: Exception) {
            finish(t, false, Msgs.noApp(ar))
            return
        }
        main.postDelayed({ tick(t) }, 2000)
    }

    private fun finish(t: Int, ok: Boolean, msg: String) {
        if (t != token) return
        val cb = callback ?: return
        callback = null
        cb(ok, msg)
        if (ok) {
            main.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 1500)
        }
    }

    private fun tick(t: Int) {
        if (t != token || callback == null) return
        val elapsed = System.currentTimeMillis() - startedAt
        if (elapsed > 30000) {
            finish(t, false, Msgs.notFound(ar))
            return
        }
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != pkg) {
            main.postDelayed({ tick(t) }, 800)
            return
        }

        if (!deviceClicked) {
            val n = findByLabel(root, device)
            if (n != null && clickNode(n)) {
                deviceClicked = true
                main.postDelayed({ tick(t) }, 2000)
                return
            }
            if (elapsed < 15000) {
                main.postDelayed({ tick(t) }, 800)
                return
            }
            deviceClicked = true // couldn't find the device name; try the controls anyway
        }

        if (tryControl(root, t, elapsed)) return
        main.postDelayed({ tick(t) }, 800)
    }

    /** Returns true when the attempt is finished (the callback will be / was called). */
    private fun tryControl(root: AccessibilityNodeInfo, t: Int, elapsed: Long): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        walk(root, 0) { nodes.add(it) }

        // 1) a real on/off switch
        val sw = nodes.firstOrNull { it.isVisibleToUser && isSwitch(it) }
        if (sw != null) {
            if (sw.isChecked == wantOn) {
                finish(t, true, Msgs.already(ar, wantOn))
                return true
            }
            if (clickNode(sw)) {
                main.postDelayed({ finish(t, true, Msgs.done(ar, wantOn)) }, 1200)
                return true
            }
        }

        // 2) a button labelled On / Off
        val wanted = if (wantOn) onLabels else offLabels
        val exact = if (wantOn) "on" else "off"
        val btn = nodes.firstOrNull { n ->
            n.isVisibleToUser && labelsOf(n).any { l -> l == exact || wanted.any { w -> l.contains(w) } }
        }
        if (btn != null && clickNode(btn)) {
            main.postDelayed({ finish(t, true, Msgs.done(ar, wantOn)) }, 1200)
            return true
        }

        // 3) a generic power / toggle button (state unknown) - only after giving the app time to load
        if (elapsed > 7000) {
            val gen = nodes.firstOrNull { n ->
                n.isVisibleToUser && labelsOf(n).any { l -> genericLabels.any { g -> l.contains(g) } }
            }
            if (gen != null && clickNode(gen)) {
                main.postDelayed({ finish(t, true, Msgs.tappedPower(ar)) }, 1200)
                return true
            }
        }
        return false
    }

    private fun isSwitch(n: AccessibilityNodeInfo): Boolean {
        val cls = n.className?.toString() ?: ""
        return cls.contains("Switch", true) || cls.contains("Toggle", true) ||
            (n.isCheckable && !cls.contains("CheckBox", true) && !cls.contains("RadioButton", true))
    }

    private fun labelsOf(n: AccessibilityNodeInfo): List<String> =
        listOfNotNull(n.text, n.contentDescription)
            .map { Text.norm(it.toString()) }
            .filter { it.isNotEmpty() }

    private fun findByLabel(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root, 0) { n ->
            if (found == null && n.isVisibleToUser && labelsOf(n).any { it.contains(label) }) found = n
        }
        return found
    }

    private fun walk(n: AccessibilityNodeInfo?, depth: Int, visit: (AccessibilityNodeInfo) -> Unit) {
        if (n == null || depth > 40) return
        visit(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, visit)
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 7) {
            if (cur.isClickable && cur.isEnabled && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            cur = cur.parent
            depth++
        }
        return tap(n)
    }

    private fun tap(n: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}
