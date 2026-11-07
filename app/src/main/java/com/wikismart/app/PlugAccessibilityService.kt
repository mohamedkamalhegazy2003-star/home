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
 * Works inside the plug's own app:
 *  - [scanPlugs] reads the device list and learns the plug names,
 *  - [run] opens the app, picks each requested plug by name and presses its on/off control.
 * For the control it looks for a real switch first, then for buttons labelled On/Off
 * (Arabic or English), and finally for a generic "power" button.
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
    private var goHome = true
    private var token = 0

    @Volatile
    private var scanning = false

    val isBusy: Boolean
        get() = callback != null || scanning

    // Labels are already normalized (see Text.norm).
    private val onLabels = listOf("turn on", "power on", "switch on", "تشغيل", "شغل", "فتح")
    private val offLabels = listOf("turn off", "power off", "switch off", "ايقاف", "اطفاء", "اطفي", "اغلاق", "قفل")
    private val genericLabels = listOf("power", "toggle", "switch", "طاقه", "الطاقه")

    // Words that are part of an app's chrome, not plug names (normalized).
    private val stopWords = setOf(
        "home", "الرئيسيه", "settings", "الاعدادات", "اعدادات", "add", "add device", "اضافه", "اضافه جهاز",
        "devices", "device", "اجهزه", "الاجهزه", "جهاز", "menu", "more", "المزيد", "profile", "الملف الشخصي",
        "notifications", "notification", "الاشعارات", "اشعارات", "on", "off", "تشغيل", "ايقاف", "connected",
        "متصل", "غير متصل", "offline", "online", "back", "cancel", "ok", "done", "search", "بحث", "scenes",
        "automation", "discover", "explore", "login", "sign in", "help", "مساعده", "support", "account",
        "حسابي", "my home", "منزلي", "all", "الكل", "favorites", "المفضله", "rooms", "الغرف", "navigate up",
        "open navigation drawer", "more options", "close", "اغلاق", "التالي", "next", "skip", "تخطي", "edit", "تعديل",
        "save", "حفظ", "yes", "no", "نعم", "لا", "loading", "جاري التحميل", "smart", "thinq", "lg thinq"
    )

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

    // ------------------------------------------------------------------ scan

    /**
     * Opens the plug app, scrolls through its device list and reports the names it found.
     * The result is also stored in [Bridge.pendingScan] and the main screen is brought back.
     */
    fun scanPlugs(cb: (List<String>) -> Unit) {
        val c = applicationContext
        pkg = Prefs.targetPackage(c)
        val t = ++token
        callback = null
        scanning = true

        val launch = if (pkg.isEmpty()) null else packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            scanning = false
            cb(emptyList())
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            startActivity(launch)
        } catch (e: Exception) {
            scanning = false
            cb(emptyList())
            return
        }

        val found = LinkedHashMap<String, String>()
        val started = System.currentTimeMillis()
        var idle = 0
        var rounds = 0

        fun done() {
            scanning = false
            if (t != token) return
            val result = found.values.toList()
            Bridge.pendingScan = result
            cb(result)
            try {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            } catch (e: Exception) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }

        fun round() {
            if (t != token) {
                scanning = false
                return
            }
            if (System.currentTimeMillis() - started > 60000) {
                done()
                return
            }
            val root = rootInActiveWindow
            if (root == null || root.packageName?.toString() != pkg) {
                main.postDelayed({ round() }, 1000)
                return
            }
            val before = found.size
            collectNames(root, found, true)
            if (found.size > before) idle = 0 else idle++
            rounds++
            if (idle >= 2 || rounds >= 10) {
                if (found.isEmpty()) collectNames(root, found, false)
                done()
                return
            }
            val scroller = findScrollable(root)
            if (scroller != null) scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            main.postDelayed({ round() }, 1500)
        }

        main.postDelayed({ round() }, 4000)
    }

    private fun collectNames(root: AccessibilityNodeInfo, out: MutableMap<String, String>, strict: Boolean) {
        walk(root, 0) { n ->
            if (n.isVisibleToUser) {
                for (raw in listOfNotNull(n.text, n.contentDescription)) {
                    val s = raw.toString().trim()
                    val k = Text.norm(s)
                    if (s.length in 2..40 && k.any { it.isLetter() } && k !in stopWords &&
                        !out.containsKey(k) && (!strict || inClickable(n))
                    ) {
                        out[k] = s
                    }
                }
            }
        }
    }

    private fun inClickable(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var d = 0
        while (cur != null && d < 6) {
            if (cur.isClickable) return true
            cur = cur.parent
            d++
        }
        return false
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var res: AccessibilityNodeInfo? = null
        walk(root, 0) { n ->
            if (res == null && n.isScrollable && n.isVisibleToUser) res = n
        }
        return res
    }

    // ------------------------------------------------------------------ run

    /**
     * Turns the given plugs on/off (empty list = the single default plug / device name from settings).
     * Runs on the main thread. The callback is called once with (success, message).
     */
    fun run(action: String, targets: List<String>, arabic: Boolean, cb: (Boolean, String) -> Unit) {
        val c = applicationContext
        val list = if (targets.isNotEmpty()) targets else listOf(Prefs.deviceName(c))
        val on = action == "on"
        var okCount = 0

        fun step(i: Int) {
            val name = list[i]
            val last = i == list.size - 1
            runOne(name, on, arabic, last) { ok, msg ->
                if (ok) {
                    okCount++
                    Prefs.setPlugState(c, name, action)
                }
                if (list.size > 1) Bridge.log((if (ok) "✅ " else "⚠️ ") + name + ": " + msg)
                when {
                    list.size == 1 -> cb(ok, msg)
                    last -> cb(okCount == list.size, Msgs.many(arabic, on, okCount, list.size))
                    else -> step(i + 1)
                }
            }
        }
        step(0)
    }

    private fun runOne(
        name: String,
        on: Boolean,
        arabic: Boolean,
        goHomeAfter: Boolean,
        cb: (Boolean, String) -> Unit
    ) {
        val c = applicationContext
        pkg = Prefs.targetPackage(c)
        device = Text.norm(name)
        wantOn = on
        ar = arabic
        callback = cb
        goHome = goHomeAfter
        scanning = false
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
        if (ok && goHome) {
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
                // the plug may be further down the list
                val scroller = findScrollable(root)
                if (scroller != null && elapsed > 4000) scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                main.postDelayed({ tick(t) }, 900)
                return
            }
            deviceClicked = true // couldn't find the plug name; try the controls anyway
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
