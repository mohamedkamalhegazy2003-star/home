package com.wikismart.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Decision(val action: String, val reply: String)

/**
 * Turns what the user said into a decision: "on", "off" or "none" (+ a short spoken reply).
 * Uses Claude when an API key is set, otherwise (or if the call fails) a simple local parser.
 */
object Brain {

    private val onWords = listOf("شغل", "شغلي", "تشغيل", "وصل", "وصلي", "افتح", "فتح", "ولع", "اشتغل")
    private val offWords = listOf("اطفي", "طفي", "اقفل", "قفل", "افصل", "فصل", "ايقاف", "وقف", "اغلق")
    private val onPhrases = listOf("turn on", "switch on", "power on", "on", "start")
    private val offPhrases = listOf("turn off", "switch off", "power off", "off", "stop", "shut down")

    private fun wordHit(w: String, list: List<String>): Boolean =
        list.any { it == w || (it.length >= 3 && w.endsWith(it) && w.length <= it.length + 2) }

    fun local(text: String): Decision {
        val n = Text.norm(text)
        val words = n.split(" ").filter { it.isNotEmpty() }
        val padded = " $n "
        val off = words.any { wordHit(it, offWords) } || offPhrases.any { padded.contains(" $it ") }
        val on = words.any { wordHit(it, onWords) } || onPhrases.any { padded.contains(" $it ") }
        val ar = Text.hasArabic(text)
        return when {
            off && !on -> Decision("off", if (ar) "تمام، هطفي الفيشة" else "Okay, turning the plug off.")
            on && !off -> Decision("on", if (ar) "تمام، هشغل الفيشة" else "Okay, turning the plug on.")
            else -> Decision("none", if (ar) "ما فهمتش، قول شغل أو اطفي" else "Sorry, say turn on or turn off.")
        }
    }

    fun interpret(ctx: Context, text: String): Decision {
        val key = Prefs.apiKey(ctx)
        if (key.isBlank()) return local(text)
        return try {
            val d = remote(key, Prefs.model(ctx), text)
            if (d.reply.isBlank()) d.copy(reply = local(text).reply) else d
        } catch (e: Exception) {
            Bridge.log("AI: ${e.message} (استخدمت الفهم المحلي)")
            local(text)
        }
    }

    private const val SYSTEM_PROMPT =
        "You are Wiki, a short voice assistant inside the Wikismart app. The app can switch the user's " +
            "smart plug on or off. The user speaks Egyptian Arabic or English and the text comes from " +
            "speech recognition, so it may contain recognition mistakes. Decide whether the user wants " +
            "the plug turned ON, turned OFF, or neither. Reply with ONLY one JSON object and nothing " +
            "else: {\"action\":\"on\"|\"off\"|\"none\",\"reply\":\"...\"}. The reply is spoken aloud: " +
            "keep it to one short sentence in the same language as the user (Egyptian Arabic if they " +
            "spoke Arabic). For on/off, reply with a brief confirmation. If the user asks something " +
            "else, use action \"none\" and answer briefly in one or two sentences."

    private fun remote(key: String, model: String, text: String): Decision {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 300)
            .put("system", SYSTEM_PROMPT)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", text))
            )

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 25000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            val txt = JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text")
            val s = txt.indexOf('{')
            val e = txt.lastIndexOf('}')
            if (s < 0 || e <= s) throw RuntimeException("bad reply")
            val j = JSONObject(txt.substring(s, e + 1))
            val action = j.optString("action", "none").lowercase()
            return Decision(
                if (action == "on" || action == "off") action else "none",
                j.optString("reply", "")
            )
        } finally {
            conn.disconnect()
        }
    }
}
