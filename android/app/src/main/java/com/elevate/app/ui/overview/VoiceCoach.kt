package com.elevate.app.ui.overview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Reads a script aloud with the phone's own text to speech: offline, no new dependency. The engine
 * starts on the first tap (not when the screen opens) and is released when the screen leaves.
 */
internal class VoiceCoach(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var released = false
    private var pending: String? = null

    var speaking by mutableStateOf(false)
        private set

    /** True once the engine is missing, failed to start or has no English voice. */
    var unavailable by mutableStateOf(false)
        private set

    /** Tap to start; tap again to stop. */
    fun toggle(script: String) {
        if (released) return
        if (speaking) {
            stop()
            return
        }
        val engine = tts
        if (engine == null) {
            pending = script
            speaking = true
            tts = TextToSpeech(app) { status -> main.post { onInit(status) } }
        } else if (ready) {
            speak(engine, script)
        }
    }

    private fun onInit(status: Int) {
        val engine = tts
        if (released || engine == null) return
        if (status != TextToSpeech.SUCCESS || !pickEnglish(engine)) {
            speaking = false
            pending = null
            unavailable = true
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { main.post { speaking = false } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { speaking = false } }
        })
        ready = true
        val script = pending
        pending = null
        if (script != null && speaking) speak(engine, script) else speaking = false
    }

    private fun pickEnglish(engine: TextToSpeech): Boolean {
        for (locale in listOf(Locale.UK, Locale.ENGLISH)) {
            val r = engine.setLanguage(locale)
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) return true
        }
        return false
    }

    private fun speak(engine: TextToSpeech, script: String) {
        speaking = true
        if (engine.speak(script, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID) == TextToSpeech.ERROR) {
            speaking = false
            unavailable = true
        }
    }

    fun stop() {
        pending = null
        tts?.takeIf { ready }?.stop()
        speaking = false
    }

    fun release() {
        released = true
        pending = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        speaking = false
    }

    private companion object {
        const val UTTERANCE_ID = "coach_report"
    }
}

/** One [VoiceCoach] per screen, shut down when the composable leaves. */
@Composable
internal fun rememberVoiceCoach(): VoiceCoach {
    val context = LocalContext.current
    val coach = remember { VoiceCoach(context) }
    DisposableEffect(coach) { onDispose { coach.release() } }
    return coach
}
