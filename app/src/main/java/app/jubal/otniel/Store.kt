package app.jubal.otniel

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** What the app keeps on the phone: what it has already announced, and the last copy of each list. */
object Store {
    private lateinit var prefs: SharedPreferences
    private lateinit var cache: File

    fun init(context: Context) {
        prefs = context.getSharedPreferences("otniel", Context.MODE_PRIVATE)
        cache = File(context.filesDir, "cache").apply { mkdirs() }
    }

    fun askedNotifications(): Boolean = prefs.getBoolean("asked_notifications", false)
    fun setAskedNotifications() = prefs.edit().putBoolean("asked_notifications", true).apply()

    fun lastUpdateCheck(): Long = prefs.getLong("update_check", 0)
    fun setLastUpdateCheck(time: Long) = prefs.edit().putLong("update_check", time).apply()

    // ------------------------------------------------------------------ what was already seen (for "new" notifications)

    fun lastLessonId(): Long = prefs.getLong("last_lesson", 0)
    fun setLastLessonId(id: Long) = prefs.edit().putLong("last_lesson", id).apply()

    fun seen(kind: String): Set<String> = prefs.getStringSet("seen:$kind", null) ?: emptySet()
    fun hasSeen(kind: String): Boolean = prefs.contains("seen:$kind")
    fun setSeen(kind: String, keys: Set<String>) = prefs.edit().putStringSet("seen:$kind", keys.toSet()).apply()

    fun lastPagesCheck(): Long = prefs.getLong("pages_check", 0)
    fun setLastPagesCheck(time: Long) = prefs.edit().putLong("pages_check", time).apply()

    // ------------------------------------------------------------------ last copy of a list

    private fun file(key: String) = File(cache, key.replace(Regex("[^A-Za-z0-9_.-]"), "_"))

    fun cached(key: String): String? = runCatching { file(key).takeIf { it.exists() }?.readText() }.getOrNull()

    fun cachedAge(key: String): Long {
        val f = file(key)
        return if (f.exists()) System.currentTimeMillis() - f.lastModified() else Long.MAX_VALUE
    }

    fun putCache(key: String, text: String) {
        runCatching { file(key).writeText(text) }
    }
}
