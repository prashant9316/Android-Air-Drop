package com.caremate.assistant.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

enum class MemoryCategory(val label: String, val hint: String) {
    PERSON("People", "family, friends, neighbours, doctors: relation, phone, birthday"),
    HEALTH("Health", "medicines, doses, conditions, allergies, doctor visits"),
    ROUTINE("Routines", "daily habits, meal times, walks, prayer times"),
    PREFERENCE("Likes & preferences", "favourite music, TV shows, foods, how they like things done"),
    PLACE("Places", "home address, hospital, pharmacy, temple, shops"),
    EVENT("Events", "upcoming appointments, birthdays, trips"),
    HOWTO("How-tos", "steps to do things on the phone that worked before"),
    NOTE("Notes", "anything else worth remembering");

    companion object {
        fun parse(s: String?): MemoryCategory =
            entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: NOTE
    }
}

data class Memory(
    val id: Long,
    val category: MemoryCategory,
    val subject: String,
    val details: String,
    val updated: Long,
)

data class Reminder(
    val id: Long,
    val text: String,
    val triggerAt: Long,
    val repeat: String, // none | daily | weekly
    val active: Boolean,
)

data class LogLine(val role: String, val text: String, val ts: Long)

/** Local, on-device store for long-term memory, reminders and a conversation log. */
class MemoryStore(context: Context) : SQLiteOpenHelper(context, "caremate.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE memories(id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, " +
                "subject TEXT NOT NULL, details TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE reminders(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, " +
                "trigger_at INTEGER NOT NULL, repeat TEXT NOT NULL DEFAULT 'none', active INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL("CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, text TEXT, ts INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---- memories ----

    /** Inserts a memory, or updates the existing one with the same category + subject. */
    fun remember(category: MemoryCategory, subject: String, details: String): Long {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.rawQuery(
            "SELECT id FROM memories WHERE category=? AND lower(subject)=lower(?)",
            arrayOf(category.name, subject.trim())
        ).use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                db.update("memories", ContentValues().apply {
                    put("details", details.trim()); put("updated", now)
                }, "id=?", arrayOf(id.toString()))
                return id
            }
        }
        return db.insert("memories", null, ContentValues().apply {
            put("category", category.name); put("subject", subject.trim()); put("details", details.trim())
            put("created", now); put("updated", now)
        })
    }

    fun update(id: Long, subject: String?, details: String?): Boolean {
        val cv = ContentValues().apply {
            subject?.let { put("subject", it) }; details?.let { put("details", it) }
            put("updated", System.currentTimeMillis())
        }
        return writableDatabase.update("memories", cv, "id=?", arrayOf(id.toString())) > 0
    }

    fun forget(id: Long): Boolean = writableDatabase.delete("memories", "id=?", arrayOf(id.toString())) > 0

    fun allMemories(): List<Memory> = queryMemories("SELECT * FROM memories ORDER BY category, subject", emptyArray())

    fun search(query: String): List<Memory> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        if (words.isEmpty()) return allMemories()
        return allMemories()
            .map { m -> m to words.count { w -> "${m.subject} ${m.details} ${m.category.label}".lowercase().contains(w) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun queryMemories(sql: String, args: Array<String>): List<Memory> =
        readableDatabase.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Memory(
                        c.getLong(c.getColumnIndexOrThrow("id")),
                        MemoryCategory.parse(c.getString(c.getColumnIndexOrThrow("category"))),
                        c.getString(c.getColumnIndexOrThrow("subject")),
                        c.getString(c.getColumnIndexOrThrow("details")),
                        c.getLong(c.getColumnIndexOrThrow("updated")),
                    )
                )
            }
        }

    // ---- reminders ----

    fun addReminder(text: String, triggerAt: Long, repeat: String): Long =
        writableDatabase.insert("reminders", null, ContentValues().apply {
            put("text", text); put("trigger_at", triggerAt); put("repeat", repeat); put("active", 1)
        })

    fun reminder(id: Long): Reminder? = reminders("id=?", arrayOf(id.toString())).firstOrNull()

    fun activeReminders(): List<Reminder> = reminders("active=1", emptyArray())

    fun setReminderTime(id: Long, triggerAt: Long) {
        writableDatabase.update("reminders", ContentValues().apply { put("trigger_at", triggerAt) }, "id=?", arrayOf(id.toString()))
    }

    fun deactivateReminder(id: Long): Boolean =
        writableDatabase.update("reminders", ContentValues().apply { put("active", 0) }, "id=?", arrayOf(id.toString())) > 0

    private fun reminders(where: String, args: Array<String>): List<Reminder> =
        readableDatabase.query("reminders", null, where, args, null, null, "trigger_at").use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Reminder(
                        c.getLong(c.getColumnIndexOrThrow("id")),
                        c.getString(c.getColumnIndexOrThrow("text")),
                        c.getLong(c.getColumnIndexOrThrow("trigger_at")),
                        c.getString(c.getColumnIndexOrThrow("repeat")),
                        c.getInt(c.getColumnIndexOrThrow("active")) == 1,
                    )
                )
            }
        }

    // ---- conversation log (so a caregiver can see what was asked) ----

    fun log(role: String, text: String) {
        val db = writableDatabase
        db.insert("log", null, ContentValues().apply {
            put("role", role); put("text", text); put("ts", System.currentTimeMillis())
        })
        db.execSQL("DELETE FROM log WHERE id NOT IN (SELECT id FROM log ORDER BY id DESC LIMIT 500)")
    }

    fun recentLog(limit: Int = 100): List<LogLine> =
        readableDatabase.rawQuery("SELECT role, text, ts FROM log ORDER BY id DESC LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(LogLine(c.getString(0), c.getString(1), c.getLong(2))) }
        }
}
