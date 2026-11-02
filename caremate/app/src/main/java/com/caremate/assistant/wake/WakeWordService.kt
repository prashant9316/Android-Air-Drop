package com.caremate.assistant.wake

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.caremate.assistant.AssistantEngine
import com.caremate.assistant.CareMateApp
import com.caremate.assistant.Phase
import com.caremate.assistant.R
import com.caremate.assistant.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The wake phrases CareMate ships with, plus one the family can train and import. */
object WakeWords {
    data class Option(val id: String, val label: String, val asset: String?)

    val BUILT_IN = listOf(
        Option("hey_jarvis", "Hey Jarvis", "wakeword/hey_jarvis_v0.1.onnx"),
        Option("alexa", "Alexa", "wakeword/alexa_v0.1.onnx"),
        Option("hey_mycroft", "Hey Mycroft", "wakeword/hey_mycroft_v0.1.onnx"),
    )
    const val CUSTOM = "custom"

    fun customFile(ctx: Context) = File(ctx.filesDir, "wakeword/custom.onnx")

    fun options(ctx: Context): List<Option> =
        BUILT_IN + if (customFile(ctx).exists()) listOf(Option(CUSTOM, CareMateApp.instance.prefs.customWakeLabel.ifBlank { "My custom phrase" }, null)) else emptyList()

    fun label(ctx: Context, id: String) = options(ctx).firstOrNull { it.id == id }?.label ?: "Hey Jarvis"

    fun loadDetector(ctx: Context, id: String): WakeWordDetector {
        val a = ctx.assets
        val wake = if (id == CUSTOM && customFile(ctx).exists()) customFile(ctx).readBytes()
        else a.open((BUILT_IN.firstOrNull { it.id == id } ?: BUILT_IN.first()).asset!!).use { it.readBytes() }
        return WakeWordDetector(
            a.open("wakeword/melspectrogram.onnx").use { it.readBytes() },
            a.open("wakeword/embedding_model.onnx").use { it.readBytes() },
            wake,
        )
    }

    /**
     * Starts or stops the listening service to match settings. Android may refuse to start
     * a microphone service from the background (e.g. right after a reboot on Android 14+);
     * then we post a notification the user can tap to switch it back on.
     */
    fun sync(ctx: Context) {
        val p = CareMateApp.instance.prefs
        val intent = Intent(ctx, WakeWordService::class.java)
        if (!p.wakeEnabled || ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ctx.stopService(intent); return
        }
        try {
            ContextCompat.startForegroundService(ctx, intent)
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_RESUME)
        } catch (e: Exception) {
            Log.w("CareMate", "Wake word service not started: $e")
            val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_RESUME,
                NotificationCompat.Builder(ctx, CareMateApp.CH_CARE).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Tap to switch on \"${label(ctx, p.wakeWord)}\"")
                    .setContentText("The phone restarted. Open CareMate once so it can listen for its name again.")
                    .setContentIntent(open).setAutoCancel(true).setOngoing(false).build())
        }
    }

    const val NOTIF_LISTENING = 21
    const val NOTIF_RESUME = 22
    const val NOTIF_WAKE = 23
}

/**
 * Foreground service that keeps the microphone open and runs [WakeWordDetector] on-device.
 * Audio never leaves the phone here. It releases the microphone whenever the assistant
 * itself is listening or speaking, during phone calls, and while paused.
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CareMateApp.instance.prefs.wakeEnabled = false
            stopSelf(); return START_NOT_STICKY
        }
        val prefs = CareMateApp.instance.prefs
        val label = WakeWords.label(this, prefs.wakeWord)
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CareMateApp.CH_WORKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Say \"$label\" to talk")
            .setContentText("Listening on this phone only. Nothing is recorded or sent.")
            .setOngoing(true).setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .build()
        ServiceCompat.startForeground(this, WakeWords.NOTIF_LISTENING, n,
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        AssistantEngine.init(this)
        // Restart the loop so a changed wake word or sensitivity takes effect.
        loop?.cancel()
        loop = scope.launch { listenLoop() }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun listenLoop() {
        val prefs = CareMateApp.instance.prefs
        val detector = try { WakeWords.loadDetector(this, prefs.wakeWord) } catch (e: Exception) {
            Log.e("CareMate", "Wake model failed to load", e); stopSelf(); return
        }
        val am = getSystemService(AudioManager::class.java)
        var rec: AudioRecord? = null
        val buf = ShortArray(WakeWordDetector.CHUNK)
        try {
            while (currentCoroutineContextActive()) {
                val st = AssistantEngine.state.value
                val busy = st.phase != Phase.IDLE || st.confirmQuestion != null || am.mode != AudioManager.MODE_NORMAL
                if (busy) {
                    rec?.let { it.stop(); it.release() }; rec = null
                    detector.reset()
                    delay(500); continue
                }
                if (rec == null) {
                    rec = openRecorder() ?: run { delay(2000); null } ?: continue
                    rec.startRecording()
                }
                var off = 0
                while (off < buf.size) {
                    val n = rec.read(buf, off, buf.size - off)
                    if (n <= 0) break
                    off += n
                }
                if (off < buf.size) { rec.release(); rec = null; delay(1000); continue }
                val score = detector.process(buf)
                if (score >= prefs.wakeThreshold) {
                    rec.stop(); rec.release(); rec = null
                    detector.reset()
                    withContext(Dispatchers.Main) { onWake() }
                    delay(2500) // let the assistant take over the microphone
                }
            }
        } finally {
            rec?.release()
            detector.close()
        }
    }

    private suspend fun currentCoroutineContextActive() = kotlin.coroutines.coroutineContext.isActive

    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? = try {
        val min = AudioRecord.getMinBufferSize(WakeWordDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWordDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, WakeWordDetector.CHUNK * 2 * 4))
            .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    } catch (e: Exception) { null }

    private fun onWake() {
        // A short beep and buzz so she knows she was heard.
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).startTone(ToneGenerator.TONE_PROP_ACK, 200) }
        runCatching {
            val v = getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        val listen = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_LISTEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivity(listen) }
        // If Android blocked opening the screen from the background, a full-screen
        // notification opens it instead (this also works on the lock screen).
        scope.launch {
            delay(1500)
            if (!MainActivity.visible) {
                val pi = PendingIntent.getActivity(this@WakeWordService, 4, listen, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                getSystemService(NotificationManager::class.java).notify(WakeWords.NOTIF_WAKE,
                    NotificationCompat.Builder(this@WakeWordService, CareMateApp.CH_CARE).setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("I'm listening, tap here").setPriority(NotificationCompat.PRIORITY_MAX)
                        .setCategory(NotificationCompat.CATEGORY_CALL).setContentIntent(pi).setFullScreenIntent(pi, true)
                        .setAutoCancel(true).setTimeoutAfter(15_000).build())
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.caremate.assistant.WAKE_STOP"
    }
}
