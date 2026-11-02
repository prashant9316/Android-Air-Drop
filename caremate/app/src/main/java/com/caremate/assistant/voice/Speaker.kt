package com.caremate.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.caremate.assistant.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Text-to-speech wrapper. [speak] suspends until the phrase has finished playing. */
class Speaker(context: Context, private val prefs: Prefs) {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = HashMap<String, (Boolean) -> Unit>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }.apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) = finish(id, true)
            @Deprecated("Deprecated in Java") override fun onError(id: String) = finish(id, false)
            override fun onStop(id: String, interrupted: Boolean) = finish(id, false)
        })
    }

    private fun finish(id: String, ok: Boolean) {
        synchronized(pending) { pending.remove(id) }?.invoke(ok)
    }

    suspend fun speak(text: String, alarmStream: Boolean = false): Boolean {
        if (text.isBlank()) return true
        if (withTimeoutOrNull(5000) { ready.await() } != true) return false
        tts.language = Locale.forLanguageTag(prefs.language)
        tts.setSpeechRate(prefs.speechRate)
        // Long answers are split so TTS engines with input limits still read everything.
        for (chunk in chunk(clean(text))) {
            val ok = suspendCancellableCoroutine { cont ->
                val id = UUID.randomUUID().toString()
                synchronized(pending) { pending[id] = { r -> if (cont.isActive) cont.resume(r) } }
                cont.invokeOnCancellation { tts.stop() }
                val params = android.os.Bundle()
                if (alarmStream) params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_ALARM)
                tts.speak(chunk, TextToSpeech.QUEUE_ADD, params, id)
            }
            if (!ok) return false
        }
        return true
    }

    fun stop() {
        tts.stop()
        val all = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        all.forEach { it(false) }
    }

    /** Strip markdown that sounds odd when read aloud. */
    private fun clean(t: String) = t.replace(Regex("[*_#`>]+"), "").replace(Regex("\\[(.+?)]\\((.+?)\\)"), "$1")

    private fun chunk(t: String): List<String> {
        val max = TextToSpeech.getMaxSpeechInputLength() - 50
        if (t.length <= max) return listOf(t)
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (s in t.split(Regex("(?<=[.!?\\n])\\s+"))) {
            if (sb.length + s.length > max && sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
            sb.append(s).append(' ')
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out
    }
}
