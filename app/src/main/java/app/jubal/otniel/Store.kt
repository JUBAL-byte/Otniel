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

    // ------------------------------------------------------------------ podcasts: feeds, listening speed and where each episode stopped

    fun showFeed(key: String): String = prefs.getString("feed:$key", "") ?: ""
    fun setShowFeed(key: String, feed: String) = prefs.edit().putString("feed:$key", feed).apply()

    fun speed(): Float = prefs.getFloat("speed", 1f)
    fun setSpeed(speed: Float) = prefs.edit().putFloat("speed", speed).apply()

    fun position(id: String): Long = prefs.getLong("pos:$id", 0)

    /** How far an episode was heard, 0..1 (1 when finished). */
    fun heard(id: String): Float {
        if (prefs.getBoolean("done:$id", false)) return 1f
        val duration = prefs.getLong("dur:$id", 0)
        return if (duration > 0) (position(id).toFloat() / duration).coerceIn(0f, 1f) else 0f
    }

    fun savePosition(id: String, position: Long, duration: Long) {
        prefs.edit().putLong("pos:$id", position).putLong("dur:$id", duration).remove("done:$id").apply()
    }

    fun markDone(id: String) {
        prefs.edit().remove("pos:$id").putBoolean("done:$id", true).apply()
    }

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
