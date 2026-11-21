package app.jubal.otniel

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** Something that can be played: a YouTube video, a podcast episode or a file from the site. */
data class Playable(
    val id: String,
    val title: String,
    val subtitle: String,
    val uri: String,
    /** Where it came from (a lesson link, a playlist...), to go back to it. */
    val page: String = "",
    val video: Boolean = true,
) {
    val isYouTube: Boolean get() = id.startsWith("yt:")
    val videoId: String get() = id.removePrefix("yt:")

    companion object {
        fun youtube(videoId: String, title: String, subtitle: String, page: String = ""): Playable =
            Playable("yt:$videoId", title, subtitle, "otniel://yt?v=$videoId", page, true)

        fun file(url: String, title: String, subtitle: String, page: String = "", video: Boolean): Playable =
            Playable("u:$url", title, subtitle, url, page, video)
    }
}

class Recent(val item: Playable, val position: Long, val duration: Long, val time: Long) {
    val percent: Int get() = if (duration > 0) ((position * 100) / duration).toInt().coerceIn(0, 100) else 0
}

/** Settings, listening positions and what the app has already seen, kept on the phone. */
object Store {
    private lateinit var prefs: SharedPreferences
    private lateinit var cache: File

    fun init(context: Context) {
        prefs = context.getSharedPreferences("otniel", Context.MODE_PRIVATE)
        cache = File(context.filesDir, "cache").apply { mkdirs() }
    }

    // ------------------------------------------------------------------ settings

    fun images(): Boolean = prefs.getBoolean("images", true)
    fun setImages(on: Boolean) = prefs.edit().putBoolean("images", on).apply()

    fun notifications(): Boolean = prefs.getBoolean("notify", true)
    fun setNotifications(on: Boolean) = prefs.edit().putBoolean("notify", on).apply()

    /** Play videos as sound only (saves data, keeps playing with the screen off). */
    fun audioOnly(): Boolean = prefs.getBoolean("audio_only", false)
    fun setAudioOnly(on: Boolean) = prefs.edit().putBoolean("audio_only", on).apply()

    fun speed(): Float = prefs.getFloat("speed", 1f)
    fun setSpeed(speed: Float) = prefs.edit().putFloat("speed", speed).apply()

    fun askedNotifications(): Boolean = prefs.getBoolean("asked_notifications", false)
    fun setAskedNotifications() = prefs.edit().putBoolean("asked_notifications", true).apply()

    fun linksTipDone(): Boolean = prefs.getBoolean("links_tip", false)
    fun setLinksTipDone() = prefs.edit().putBoolean("links_tip", true).apply()

    fun lastUpdateCheck(): Long = prefs.getLong("update_check", 0)
    fun setLastUpdateCheck(time: Long) = prefs.edit().putLong("update_check", time).apply()

    // ------------------------------------------------------------------ positions

    fun savePosition(id: String, position: Long, duration: Long) {
        prefs.edit().putLong("pos:$id", position).putLong("dur:$id", duration).apply()
        updateRecent(id, position, duration)
    }

    fun position(id: String): Long = prefs.getLong("pos:$id", 0)

    fun markDone(id: String) {
        prefs.edit().remove("pos:$id").apply()
        val list = recent().filterNot { it.item.id == id }
        writeRecent(list)
    }

    fun recent(): List<Recent> = runCatching {
        J.parse(prefs.getString("recent", "[]") ?: "[]").list().map {
            Recent(
                Playable(it["id"].str(), it["t"].str(), it["s"].str(), it["u"].str(), it["p"].str(), it["v"].bool(true)),
                it["pos"].long(), it["dur"].long(), it["time"].long(),
            )
        }.filter { it.item.id.isNotEmpty() }
    }.getOrDefault(emptyList())

    fun addRecent(item: Playable) {
        val old = recent().firstOrNull { it.item.id == item.id }
        val list = listOf(Recent(item, old?.position ?: position(item.id), old?.duration ?: 0, System.currentTimeMillis())) +
            recent().filterNot { it.item.id == item.id }
        writeRecent(list.take(30))
    }

    private fun updateRecent(id: String, position: Long, duration: Long) {
        val list = recent()
        if (list.none { it.item.id == id }) return
        writeRecent(list.map { if (it.item.id == id) Recent(it.item, position, duration, System.currentTimeMillis()) else it })
    }

    private fun writeRecent(list: List<Recent>) {
        val sb = StringBuilder("[")
        list.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(q(r.item.id))
                .append(",\"t\":").append(q(r.item.title))
                .append(",\"s\":").append(q(r.item.subtitle))
                .append(",\"u\":").append(q(r.item.uri))
                .append(",\"p\":").append(q(r.item.page))
                .append(",\"v\":").append(r.item.video)
                .append(",\"pos\":").append(r.position)
                .append(",\"dur\":").append(r.duration)
                .append(",\"time\":").append(r.time)
                .append('}')
        }
        sb.append(']')
        prefs.edit().putString("recent", sb.toString()).apply()
    }

    /** A JSON string literal. */
    fun q(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    // ------------------------------------------------------------------ what was already seen (for "new" notifications)

    fun lastLessonId(): Long = prefs.getLong("last_lesson", 0)
    fun setLastLessonId(id: Long) = prefs.edit().putLong("last_lesson", id).apply()

    fun seen(kind: String): Set<String> = prefs.getStringSet("seen:$kind", null) ?: emptySet()
    fun hasSeen(kind: String): Boolean = prefs.contains("seen:$kind")
    fun setSeen(kind: String, keys: Set<String>) = prefs.edit().putStringSet("seen:$kind", keys.toSet()).apply()

    fun lastPagesCheck(): Long = prefs.getLong("pages_check", 0)
    fun setLastPagesCheck(time: Long) = prefs.edit().putLong("pages_check", time).apply()

    fun channelId(): String = prefs.getString("channel_id", "") ?: ""
    fun setChannelId(id: String) = prefs.edit().putString("channel_id", id).apply()

    // ------------------------------------------------------------------ names found for untitled podcasts

    fun showTitle(key: String): String = prefs.getString("show:$key", "") ?: ""
    fun setShowTitle(key: String, title: String) = prefs.edit().putString("show:$key", title).apply()

    fun showFeed(key: String): String = prefs.getString("feed:$key", "") ?: ""
    fun setShowFeed(key: String, feed: String) = prefs.edit().putString("feed:$key", feed).apply()

    // ------------------------------------------------------------------ last copy of each list, to show at once while refreshing

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
