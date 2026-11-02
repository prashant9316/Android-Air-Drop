package com.caremate.assistant.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Speech-to-text. When CareMate is the default assistant, Android also makes our
 * [RecognitionProxyService] the system recogniser, so we always bind to a *different*
 * recognition engine here (normally Google's) to avoid talking to ourselves.
 */
object Listener {

    fun recognizerComponent(context: Context): ComponentName? {
        val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != context.packageName }
        return services.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: services.firstOrNull { it.packageName.startsWith("com.google") }
            ?: services.firstOrNull()
    }

    fun create(context: Context): SpeechRecognizer {
        val comp = recognizerComponent(context)
        return if (comp != null) SpeechRecognizer.createSpeechRecognizer(context, comp)
        else if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
    }

    fun intent(language: String, preferOffline: Boolean = false) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Older people often pause mid-sentence: give them more time before we stop listening.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    /**
     * Listens once and returns the recognised text, or null if nothing was heard.
     * [onPartial] receives live partial transcripts; [onLevel] mic level 0..1 for animation.
     */
    suspend fun listen(
        context: Context,
        language: String,
        onPartial: (String) -> Unit = {},
        onLevel: (Float) -> Unit = {},
    ): String? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext null
        val sr = create(context)
        try {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { runCatching { sr.cancel() } }
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) { if (cont.isActive) cont.resume(null) }
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (cont.isActive) cont.resume(text?.takeIf { it.isNotBlank() })
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            ?.let(onPartial)
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                sr.startListening(intent(language))
            }
        } finally {
            sr.destroy()
        }
    }
}
