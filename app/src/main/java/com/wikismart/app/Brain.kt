package com.wikismart.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * action: "on", "off", "status" or "none".
 * targets: plug names (exactly as saved); empty = no specific plug.
 * delayMinutes: act after this many minutes (0 = now).
 */
data class Decision(
    val action: String,
    val reply: String,
    val targets: List<String> = emptyList(),
    val delayMinutes: Int = 0
)

/**
 * Turns what the user said into a [Decision].
 * The built-in engine (always available, works offline) understands Arabic/English on/off/status commands,
 * plug names (fuzzy), "all plugs" and delays. When a Claude API key is set, Claude is used on top of it
 * (free conversation, memory of the last turns) and the built-in engine is the fallback.
 */
object Brain {

    private val onWords = listOf("شغل", "شغلي", "تشغيل", "وصل", "وصلي", "افتح", "فتح", "ولع", "اشتغل")
    private val offWords = listOf("اطفي", "طفي", "اقفل", "قفل", "افصل", "فصل", "ايقاف", "وقف", "اغلق")
    private val statusWords = listOf("حاله", "شغاله", "مطفيه", "مفتوحه", "مقفوله")
    private val onPhrases = listOf("turn on", "switch on", "power on", "on", "start")
    private val offPhrases = listOf("turn off", "switch off", "power off", "off", "stop", "shut down")
    private val statusPhrases = listOf("status", "state")
    private val allWords = setOf("كل", "الكل", "كلهم", "جميع", "all", "every", "everything")
    private val fillerWords = setOf("فيشه", "الفيشه", "فيش", "الفيش", "plug", "plugs", "socket", "the", "ال")

    private val history = ArrayList<Pair<String, String>>()

    // ------------------------------------------------------------------ helpers

    private fun wordHit(w: String, list: List<String>): Boolean =
        list.any { it == w || (it.length >= 3 && w.endsWith(it) && w.length <= it.length + 2) }

    private fun stripAl(w: String): String = if (w.length > 3 && w.startsWith("ال")) w.substring(2) else w

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun sameWord(a: String, b: String): Boolean {
        val x = stripAl(a)
        val y = stripAl(b)
        if (x == y) return true
        if (x.length >= 4 && y.length >= 4 && Math.abs(x.length - y.length) <= 1 && lev(x, y) <= 1) return true
        // Arabic one-letter prefixes: و (and), ل (for), ب (with)
        if (y.length > 3 && (y.startsWith("و") || y.startsWith("ل") || y.startsWith("ب")) &&
            stripAl(y.substring(1)) == x
        ) return true
        return false
    }

    private fun askWhich(plugs: List<String>, ar: Boolean): String =
        if (ar) "أنهي فيشة؟ " + plugs.joinToString("، ") else "Which plug? " + plugs.joinToString(", ")

    /** Which of the known plugs did the user mean? (fuzzy, several allowed, "all" supported) */
    fun resolveTargets(text: String, plugs: List<String>): List<String> {
        if (plugs.isEmpty()) return emptyList()
        val words = Text.norm(text).split(" ").filter { it.isNotEmpty() }
        if (words.any { it in allWords }) return plugs

        val scored = plugs.map { p ->
            val toks = Text.norm(p).split(" ").filter { it.isNotEmpty() && it !in fillerWords }
            if (toks.isEmpty()) {
                Pair(p, 0.0)
            } else {
                val hit = toks.count { t -> words.any { w -> sameWord(t, w) } }
                Pair(p, hit.toDouble() / toks.size)
            }
        }
        val full = scored.filter { it.second >= 0.99 }.map { it.first }
        if (full.isNotEmpty()) return full
        val best = scored.maxByOrNull { it.second }
        return if (best != null && best.second >= 0.5) listOf(best.first) else emptyList()
    }

    /** "بعد 10 دقايق" / "in 2 hours" / "بعد نص ساعة" ... -> minutes (0 if none). */
    fun parseDelay(text: String): Int {
        var n = Text.norm(text)
        n = n.map { if (it in '٠'..'٩') '0' + (it - '٠') else it }.joinToString("")
        val v: Int = when {
            Regex("بعد (نص|نصف) ساعه").containsMatchIn(n) -> 30
            Regex("بعد ربع ساعه").containsMatchIn(n) -> 15
            Regex("بعد ساعتين").containsMatchIn(n) -> 120
            Regex("بعد دقيقتين").containsMatchIn(n) -> 2
            else -> {
                val m = Regex("(?:بعد|in|after) (\\d+) ?(دقيقه|دقايق|دقائق|minutes|minute|mins|min|ساعه|ساعات|hours|hour|hrs|hr)")
                    .find(n)
                if (m != null) {
                    val num = m.groupValues[1].toIntOrNull() ?: 0
                    val u = m.groupValues[2]
                    if (u.startsWith("ساع") || u.startsWith("hour") || u.startsWith("hr")) num * 60 else num
                } else if (Regex("بعد ساعه").containsMatchIn(n) || Regex("(?:in|after) an? hour").containsMatchIn(n)) {
                    60
                } else if (Regex("بعد دقيقه").containsMatchIn(n)) {
                    1
                } else {
                    0
                }
            }
        }
        return v.coerceIn(0, 1440)
    }

    // ------------------------------------------------------------------ built-in engine

    private fun localReply(action: String, targets: List<String>, all: List<String>, delay: Int, ar: Boolean): String {
        val who = when {
            targets.isEmpty() -> if (ar) "الفيشة" else "the plug"
            targets.size == all.size && all.size > 1 -> if (ar) "كل الفيش" else "all plugs"
            else -> targets.joinToString(if (ar) " و" else " and ")
        }
        val on = action == "on"
        val tail = if (delay <= 0) "" else if (ar) " بعد $delay دقيقة" else " in $delay minutes"
        return if (ar) {
            "تمام، " + (if (on) "هشغل " else "هطفي ") + who + tail
        } else {
            "Okay, turning $who " + (if (on) "on" else "off") + tail + "."
        }
    }

    fun local(text: String, plugs: List<String>): Decision {
        val n = Text.norm(text)
        val words = n.split(" ").filter { it.isNotEmpty() }
        val padded = " $n "
        val ar = Text.hasArabic(text)

        val off = words.any { wordHit(it, offWords) } || offPhrases.any { padded.contains(" $it ") }
        val on = words.any { wordHit(it, onWords) } || onPhrases.any { padded.contains(" $it ") }
        val question = padded.contains(" is it ") || padded.contains(" is the ") || padded.contains(" status ")
        val statusHit = words.any { wordHit(it, statusWords) } || statusPhrases.any { padded.contains(" $it ") }

        val action = when {
            question -> "status"
            off && !on -> "off"
            on && !off -> "on"
            statusHit -> "status"
            else -> "none"
        }
        if (action == "none") {
            return Decision("none", if (ar) "ما فهمتش، قول شغل أو اطفي" else "Sorry, say turn on or turn off.")
        }

        var targets = resolveTargets(text, plugs)
        if (targets.isEmpty() && plugs.size == 1) targets = plugs
        if (action == "status") {
            return Decision("status", "", if (targets.isEmpty()) plugs else targets)
        }
        if (targets.isEmpty() && plugs.size > 1) {
            return Decision("none", askWhich(plugs, ar))
        }
        val delay = parseDelay(text)
        return Decision(action, localReply(action, targets, plugs, delay, ar), targets, delay)
    }

    // ------------------------------------------------------------------ entry point

    fun interpret(ctx: Context, text: String): Decision {
        val plugs = Prefs.plugs(ctx)
        val key = Prefs.apiKey(ctx)
        if (key.isBlank()) return local(text, plugs)
        return try {
            var d = remote(key, Prefs.model(ctx), text, plugs)
            if ((d.action == "on" || d.action == "off") && d.targets.isEmpty() && plugs.isNotEmpty()) {
                var t = resolveTargets(text, plugs)
                if (t.isEmpty() && plugs.size == 1) t = plugs
                d = if (t.isEmpty()) Decision("none", askWhich(plugs, Text.hasArabic(text))) else d.copy(targets = t)
            }
            if (d.action == "status" && d.targets.isEmpty()) d = d.copy(targets = plugs)
            if (d.action != "status" && d.reply.isBlank()) {
                d = d.copy(reply = local(text, plugs).reply)
            }
            d
        } catch (e: Exception) {
            Bridge.log("AI: ${e.message} (استخدمت الفهم المحلي)")
            local(text, plugs)
        }
    }

    // ------------------------------------------------------------------ Claude

    private fun systemPrompt(plugs: List<String>): String {
        val list = if (plugs.isEmpty()) "(none known yet)" else plugs.joinToString(" | ")
        return "You are Wiki, a short voice assistant inside the Wikismart app, which controls the user's " +
            "smart plugs through the plugs' own phone app. Known plugs: $list. " +
            "The user speaks Egyptian Arabic or English; the text comes from speech recognition and may " +
            "contain mistakes, including in plug names. Decide what the user wants and reply with ONLY one " +
            "JSON object and nothing else: " +
            "{\"action\":\"on\"|\"off\"|\"status\"|\"none\",\"targets\":[...],\"delay_minutes\":0,\"reply\":\"...\"}. " +
            "targets: exact names from the known plug list, or [\"all\"] for every plug, or [] if there is no list. " +
            "If several plugs exist and it is unclear which one is meant, use action \"none\" and ask which one. " +
            "delay_minutes: minutes to wait before acting (0 = now). " +
            "action \"status\" means the user asks whether a plug is on or off (leave reply empty; the app answers). " +
            "The reply is spoken aloud: one short sentence in the user's language (Egyptian Arabic if they spoke " +
            "Arabic); for on/off confirm briefly and mention the delay if any. " +
            "For anything else use action \"none\" and answer briefly in one or two sentences."
    }

    private fun mapTargets(raw: List<String>, plugs: List<String>): List<String> {
        if (plugs.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for (r in raw) {
            val k = Text.norm(r)
            if (k == "all" || k == "الكل") return plugs
            val exact = plugs.firstOrNull { Text.norm(it) == k }
            if (exact != null) out.add(exact) else out.addAll(resolveTargets(r, plugs))
        }
        return out.toList()
    }

    private fun remote(key: String, model: String, text: String, plugs: List<String>): Decision {
        val msgs = JSONArray()
        synchronized(history) {
            for (h in history) msgs.put(JSONObject().put("role", h.first).put("content", h.second))
        }
        msgs.put(JSONObject().put("role", "user").put("content", text))

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 400)
            .put("system", systemPrompt(plugs))
            .put("messages", msgs)

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
            val ta = j.optJSONArray("targets")
            val raw = ArrayList<String>()
            if (ta != null) for (i in 0 until ta.length()) raw.add(ta.optString(i))

            synchronized(history) {
                history.add(Pair("user", text))
                history.add(Pair("assistant", txt.substring(s, e + 1)))
                while (history.size > 8) history.removeAt(0)
            }

            return Decision(
                if (action == "on" || action == "off" || action == "status") action else "none",
                j.optString("reply", ""),
                mapTargets(raw, plugs),
                j.optInt("delay_minutes", 0).coerceIn(0, 1440)
            )
        } finally {
            conn.disconnect()
        }
    }
}
