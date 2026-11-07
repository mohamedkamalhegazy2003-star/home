package com.wikismart.app

import android.content.Context

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("wikismart", Context.MODE_PRIVATE)

    fun targetPackage(c: Context): String = p(c).getString("pkg", "") ?: ""
    fun targetLabel(c: Context): String = p(c).getString("pkg_label", "") ?: ""
    fun setTarget(c: Context, pkg: String, label: String) {
        p(c).edit().putString("pkg", pkg).putString("pkg_label", label).apply()
    }

    fun deviceName(c: Context): String = p(c).getString("device", "") ?: ""
    fun setDeviceName(c: Context, v: String) {
        p(c).edit().putString("device", v.trim()).apply()
    }

    fun apiKey(c: Context): String = p(c).getString("api_key", "") ?: ""
    fun setApiKey(c: Context, v: String) {
        p(c).edit().putString("api_key", v.trim()).apply()
    }

    fun model(c: Context): String =
        (p(c).getString("model", "") ?: "").ifBlank { "claude-haiku-5-5" }

    fun setModel(c: Context, v: String) {
        p(c).edit().putString("model", v.trim()).apply()
    }

    fun lang(c: Context): String = p(c).getString("lang", "ar") ?: "ar"
    fun setLang(c: Context, v: String) {
        p(c).edit().putString("lang", v).apply()
    }
}
