package com.wikismart.app

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny in-process hub: service state + a rolling log shown in the main screen. */
object Bridge {
    @Volatile
    var running = false

    @Volatile
    var listener: (() -> Unit)? = null

    /** Plug names found by the last scan, waiting for the user to confirm them. */
    @Volatile
    var pendingScan: List<String>? = null

    private val lines = ArrayList<String>()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(msg: String) {
        val stamped = fmt.format(Date()) + "  " + msg
        synchronized(lines) {
            lines.add(0, stamped)
            if (lines.size > 40) lines.removeAt(lines.size - 1)
        }
        main.post { listener?.invoke() }
    }

    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }
}
