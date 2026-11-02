package com.caremate.assistant.voice

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.caremate.assistant.ui.MainActivity

/**
 * Lets the user pick CareMate as the phone's "Default digital assistant app"
 * (Settings > Apps > Default apps). Long-pressing home / power, or swiping up from a
 * bottom corner, then opens CareMate listening, even from the lock screen.
 */
class AssistVoiceService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

class AssistSession(service: VoiceInteractionSessionService) : VoiceInteractionSession(service) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_LISTEN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        hide()
    }
}

/**
 * Android requires an assistant to also provide a speech recogniser, and makes it the
 * system default. Rather than break voice typing in other apps, this forwards every
 * request to the phone's real recogniser (usually Google's).
 */
class RecognitionProxyService : RecognitionService() {
    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        delegate?.destroy()
        val comp = Listener.recognizerComponent(this)
        if (comp == null) { listener.error(SpeechRecognizer.ERROR_CLIENT); return }
        val sr = SpeechRecognizer.createSpeechRecognizer(this, comp)
        delegate = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = runCatching { listener.readyForSpeech(params ?: Bundle()) }.let {}
            override fun onBeginningOfSpeech() = runCatching { listener.beginningOfSpeech() }.let {}
            override fun onRmsChanged(rmsdB: Float) = runCatching { listener.rmsChanged(rmsdB) }.let {}
            override fun onBufferReceived(buffer: ByteArray?) = runCatching { listener.bufferReceived(buffer ?: ByteArray(0)) }.let {}
            override fun onEndOfSpeech() = runCatching { listener.endOfSpeech() }.let {}
            override fun onError(error: Int) = runCatching { listener.error(error) }.let {}
            override fun onResults(results: Bundle?) = runCatching { listener.results(results ?: Bundle()) }.let {}
            override fun onPartialResults(partialResults: Bundle?) = runCatching { listener.partialResults(partialResults ?: Bundle()) }.let {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        sr.startListening(recognizerIntent)
    }

    override fun onStopListening(listener: Callback) { delegate?.stopListening() }
    override fun onCancel(listener: Callback) { delegate?.cancel() }
    override fun onDestroy() { delegate?.destroy(); super.onDestroy() }
}
