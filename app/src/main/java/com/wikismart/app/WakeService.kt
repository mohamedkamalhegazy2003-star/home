package com.wikismart.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Foreground service that keeps listening for the wake word "wiki" (ويكي),
 * then listens for one command, asks [Brain] what it means and runs it through
 * [PlugAccessibilityService].
 */
class WakeService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    private enum class State { WAKE, COMMAND, BUSY }

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var state = State.WAKE
    private var alive = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val speechCallbacks = HashMap<String, () -> Unit>()
    private var utteranceCounter = 0
    private var cycleToken = 0

    // Already normalized (see Text.norm): Latin variants + Arabic spellings of "wiki".
    private val wakeTokens = listOf("wiki", "wikki", "weeki", "wicky", "wiky", "ويكي", "ويكيي", "وايكي", "ويكا")

    private val listenRunnable = Runnable { if (alive && state != State.BUSY) listen() }
    private val busyGuard = Runnable {
        if (state == State.BUSY) {
            cycleToken++
            finishCycle()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        Bridge.running = true
        tts = TextToSpeech(this, this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wikismart:listen")
            .also { it.acquire(12 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Bridge.log("⚠️ Microphone permission is missing")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Bridge.log("⚠️ Speech recognition is not available on this phone")
            stopSelf()
            return START_NOT_STICKY
        }
        Bridge.log("🎧 الاستماع شغال — قل «ويكي»")
        state = State.WAKE
        listenSoon(100)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("wikismart", getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "wikismart")
            .setSmallIcon(R.drawable.ic_stat_home)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }

    // ---------------------------------------------------------------- listening

    private fun ensureRecognizer() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        }
    }

    private fun recreateRecognizer() {
        recognizer?.destroy()
        recognizer = null
        ensureRecognizer()
    }

    private fun listenSoon(delay: Long = 300) {
        main.removeCallbacks(listenRunnable)
        main.postDelayed(listenRunnable, delay)
    }

    private fun listen() {
        if (!alive) return
        ensureRecognizer()
        val lang = if (Prefs.lang(this) == "en") "en-US" else "ar-EG"
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        try {
            recognizer?.startListening(i)
        } catch (e: Exception) {
            Bridge.log("⚠️ ${e.message}")
            main.postDelayed({
                if (alive) {
                    recreateRecognizer()
                    listenSoon(500)
                }
            }, 1000)
        }
    }

    private fun findWake(n: String): String? {
        var best = -1
        var bestLen = 0
        for (t in wakeTokens) {
            val idx = n.indexOf(t)
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx
                bestLen = t.length
            }
        }
        return if (best < 0) null else n.substring(best + bestLen)
    }

    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            main.postDelayed({ tg.release() }, 400)
        } catch (e: Exception) {
            // no beep, no problem
        }
    }

    override fun onResults(results: Bundle?) {
        if (!alive || state == State.BUSY) return
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
        if (list.isEmpty()) {
            state = State.WAKE
            listenSoon()
            return
        }
        if (state == State.WAKE) {
            for (cand in list) {
                val rest = findWake(Text.norm(cand)) ?: continue
                Bridge.log("🎙 $cand")
                val cmd = rest.trim()
                if (cmd.isEmpty()) {
                    beep()
                    state = State.COMMAND
                    listenSoon(250)
                } else {
                    process(cmd)
                }
                return
            }
            listenSoon(150)
        } else {
            val text = list[0]
            Bridge.log("🎙 $text")
            val cmd = (findWake(Text.norm(text)) ?: text).trim()
            if (cmd.isEmpty()) {
                state = State.WAKE
                listenSoon()
            } else {
                process(cmd)
            }
        }
    }

    override fun onError(error: Int) {
        if (!alive || state == State.BUSY) return
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                Bridge.log("⚠️ Microphone permission is missing")
                stopSelf()
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                recreateRecognizer()
                state = State.WAKE
                listenSoon(1000)
            }
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (state == State.COMMAND) {
                    Bridge.log("… " + Msgs.didntHear(Prefs.lang(this) == "ar"))
                }
                state = State.WAKE
                listenSoon(250)
            }
            else -> {
                state = State.WAKE
                listenSoon(1500)
            }
        }
    }

    // ---------------------------------------------------------------- acting

    private fun process(text: String) {
        state = State.BUSY
        val token = ++cycleToken
        main.removeCallbacks(listenRunnable)
        main.removeCallbacks(busyGuard)
        main.postDelayed(busyGuard, 45000)
        exec.execute {
            val d = Brain.interpret(this, text)
            main.post { if (alive && token == cycleToken) act(d, text) }
        }
    }

    private fun act(d: Decision, heard: String) {
        val ar = if (heard.any { it.isLetter() }) Text.hasArabic(heard) else Prefs.lang(this) == "ar"
        Bridge.log("🤖 ${d.reply}" + if (d.action != "none") "  [${d.action}]" else "")

        if (d.action == "none") {
            speak(d.reply) { finishCycle() }
            return
        }

        speak(d.reply, null)
        val svc = PlugAccessibilityService.instance
        if (svc == null) {
            val m = Msgs.noA11y(ar)
            Bridge.log("⚠️ $m")
            speak(m) { finishCycle() }
            return
        }
        val token = cycleToken
        svc.run(d.action, ar) { ok, msg ->
            main.post {
                if (alive && token == cycleToken) {
                    Bridge.log((if (ok) "✅ " else "⚠️ ") + msg)
                    speak(msg) { finishCycle() }
                }
            }
        }
    }

    private fun finishCycle() {
        main.removeCallbacks(busyGuard)
        state = State.WAKE
        listenSoon(400)
    }

    // ---------------------------------------------------------------- speech output

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    fire(utteranceId)
                }

                override fun onError(utteranceId: String?) {
                    fire(utteranceId)
                }
            })
        } else {
            Bridge.log("⚠️ Text-to-speech is not available")
        }
    }

    private fun fire(id: String?) {
        main.post { if (id != null) speechCallbacks.remove(id)?.invoke() }
    }

    private fun speak(text: String, onDone: (() -> Unit)?) {
        if (text.isBlank() || !ttsReady) {
            if (onDone != null) main.post { onDone() }
            return
        }
        val id = "u${++utteranceCounter}"
        if (onDone != null) speechCallbacks[id] = onDone
        tts?.setLanguage(if (Text.hasArabic(text)) Locale("ar") else Locale.US)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    // ---------------------------------------------------------------- unused callbacks

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        alive = false
        Bridge.running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        wakeLock?.let { if (it.isHeld) it.release() }
        exec.shutdownNow()
        Bridge.log("⏹ تم إيقاف الاستماع")
        super.onDestroy()
    }
}
