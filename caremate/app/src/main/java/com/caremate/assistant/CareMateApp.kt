package com.caremate.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.caremate.assistant.memory.MemoryStore
import com.caremate.assistant.voice.Speaker

class CareMateApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var store: MemoryStore
        private set
    lateinit var speaker: Speaker
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        store = MemoryStore(this)
        speaker = Speaker(this, prefs)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Medicine and appointment reminders"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CARE, "Care alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WORKING, "Assistant activity", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CH_REMINDERS = "reminders"
        const val CH_CARE = "care"
        const val CH_WORKING = "working"
        lateinit var instance: CareMateApp
            private set
    }
}
