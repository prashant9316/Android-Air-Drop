package com.caremate.assistant.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.caremate.assistant.AssistantEngine
import com.caremate.assistant.CareMateApp
import com.caremate.assistant.care.FindPhone
import com.caremate.assistant.wake.WakeWords

enum class Screen { HOME, SETUP, SETTINGS, MEMORY }

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AssistantEngine.init(this)
        // Work on top of the lock screen when opened as the assistant or by a reminder.
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        val prefs = CareMateApp.instance.prefs
        if (!prefs.onboarded) screen = Screen.SETUP
        setContent {
            CareMateTheme(large = prefs.largeText) {
                BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
                when (screen) {
                    Screen.HOME -> HomeScreen(onNavigate = { screen = it })
                    Screen.SETUP -> SetupScreen(onDone = { prefs.onboarded = true; screen = Screen.HOME }, onSettings = { screen = Screen.SETTINGS })
                    Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME })
                    Screen.MEMORY -> MemoryScreen(onBack = { screen = Screen.HOME })
                }
            }
        }
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        getSystemService(android.app.NotificationManager::class.java).cancel(WakeWords.NOTIF_WAKE)
        WakeWords.sync(this) // (re)start wake-word listening if it's switched on
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        FindPhone.stop()
        intent.getStringExtra(EXTRA_REMINDER)?.let { screen = Screen.HOME; return }
        val listen = intent.getBooleanExtra(EXTRA_LISTEN, false) ||
            intent.action == Intent.ACTION_ASSIST || intent.action == Intent.ACTION_VOICE_COMMAND ||
            intent.action == ACTION_TALK
        if (listen && CareMateApp.instance.prefs.onboarded) { screen = Screen.HOME; AssistantEngine.listen() }
        if (intent.action == ACTION_SOS) { screen = Screen.HOME; AssistantEngine.sos() }
    }

    companion object {
        @Volatile var visible = false
        const val EXTRA_LISTEN = "listen"
        const val EXTRA_REMINDER = "reminder"
        const val ACTION_TALK = "com.caremate.assistant.TALK"
        const val ACTION_SOS = "com.caremate.assistant.SOS"
    }
}
