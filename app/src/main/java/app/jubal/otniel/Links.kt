package app.jubal.otniel

import java.net.URLDecoder

/** Where a link leads inside the app. Anything that is not the yeshiva's content is [External]. */
sealed interface Target {
    data object Home : Target
    data class Tab(val tab: Int) : Target
    data class LessonSlug(val slug: String) : Target
    data class Topic(val slug: String) : Target
    data class Rabbi(val slug: String) : Target
    data class FaqSlug(val slug: String) : Target
    data object FaqList : Target
    data object Rabbis : Target
    data object Contact : Target
    data object About : Target
    data object Store : Target
    data class Product(val slug: String) : Target
    data class Page(val slug: String, val url: String) : Target

    /** Payment, cart, account and donations: only in the phone's own browser, outside the app. */
    data class Browser(val url: String) : Target
    data class Video(val id: String, val startSec: Long) : Target
    data class Playlist(val id: String, val url: String) : Target
    data class Spotify(val url: String) : Target
    data class Apple(val id: Long, val url: String) : Target
    data class Pdf(val url: String) : Target
    data class Media(val url: String) : Target
    data class Phone(val uri: String) : Target
    data class Mail(val uri: String) : Target
    data class External(val url: String) : Target
}

object Links {
    const val TAB_HOME = 0
    const val TAB_LESSONS = 1
    const val TAB_SERIES = 2
    const val TAB_PODCASTS = 3
    const val TAB_MORE = 4

    val SITE_HOSTS = setOf("otniel.org", "www.otniel.org")
    private val YT_HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtu.be", "www.youtube-nocookie.com", "youtube-nocookie.com",
    )

    private val YT_ID = Regex(
        "(?:youtube(?:-nocookie)?\\.com/(?:watch\\?(?:[^#\"']*&(?:amp;)?)?v=|embed/|shorts/|live/|v/)|youtu\\.be/)([\\w-]{11})"
    )
    private val YT_LIST = Regex("[?&](?:amp;)?list=([\\w-]+)")
    private val YT_TIME = Regex("[?&#](?:amp;)?(?:t|start)=(?:(\\d+)h)?(?:(\\d+)m)?(\\d+)?s?(?=$|[&#])")
    private val URL = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(?://([^/?#]*))?([^?#]*)(?:\\?([^#]*))?(?:#(.*))?$")

    fun youtubeId(url: String): String? {
        if (url.isBlank()) return null
        val u = url.replace("\\/", "/")
        return YT_ID.find(u)?.groupValues?.get(1)
    }

    fun youtubeStart(url: String): Long {
        val m = YT_TIME.find(url.replace("\\/", "/")) ?: return 0
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val min = m.groupValues[2].toLongOrNull() ?: 0
        val s = m.groupValues[3].toLongOrNull() ?: 0
        return h * 3600 + min * 60 + s
    }

    fun youtubePlaylist(url: String): String? {
        val host = host(url) ?: return null
        if (host !in YT_HOSTS) return null
        return YT_LIST.find(url)?.groupValues?.get(1)
    }

    fun host(url: String): String? = URL.find(url.trim())?.groupValues?.get(2)?.lowercase()?.substringAfter('@')?.substringBefore(':')

    fun isSite(url: String): Boolean = host(url) in SITE_HOSTS

    fun appleId(url: String): Long? = Regex("/id(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()

    private fun decode(segment: String): String = try {
        URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
        segment
    }

    private val MEDIA_EXT = Regex("\\.(mp3|m4a|aac|ogg|oga|opus|wav|webm|mp4|m4v|mov)$", RegexOption.IGNORE_CASE)

    /** Decides where [url] goes. Relative links are taken as links on otniel.org. */
    fun target(raw: String): Target {
        var url = raw.trim().replace("&amp;", "&")
        if (url.startsWith("//")) url = "https:$url"
        if (url.startsWith("/")) url = "https://otniel.org$url"
        val m = URL.find(url) ?: return Target.External(url)
        val scheme = m.groupValues[1].lowercase()
        val host = (m.groupValues[2]).lowercase().substringAfter('@').substringBefore(':')
        val path = m.groupValues[3]
        when (scheme) {
            "tel" -> return Target.Phone(url)
            "mailto" -> return Target.Mail(url)
            "http", "https" -> Unit
            else -> return Target.External(url)
        }
        val lowerPath = path.lowercase()
        if (lowerPath.endsWith(".pdf")) return Target.Pdf(url)

        if (host in YT_HOSTS) {
            youtubeId(url)?.let { return Target.Video(it, youtubeStart(url)) }
            youtubePlaylist(url)?.let { return Target.Playlist(it, "https://www.youtube.com/playlist?list=$it") }
            return Target.External(url)
        }
        if (host == "open.spotify.com" || host == "spotify.link") return Target.Spotify(url)
        if (host == "podcasts.apple.com") {
            appleId(url)?.let { return Target.Apple(it, url) }
            return Target.External(url)
        }
        if (MEDIA_EXT.containsMatchIn(lowerPath)) return Target.Media(url)
        if (host !in SITE_HOSTS) return Target.External(url)

        val segments = path.split('/').filter { it.isNotEmpty() }.map { decode(it) }
        if (segments.isEmpty()) {
            val query = m.groupValues[4]
            if (query.contains("add-to-cart") || query.contains("wc-")) return Target.Browser(url)
            return Target.Home
        }
        val first = segments[0].lowercase()
        val second = segments.getOrNull(1) ?: ""
        if (first == "wp-content") return Target.External(url)
        return when (first) {
            "shiurim" -> if (second.isNotEmpty()) Target.LessonSlug(second) else Target.Tab(TAB_LESSONS)
            "topics" -> if (second.isNotEmpty()) Target.Topic(segments.last()) else Target.Tab(TAB_LESSONS)
            "rabbis" -> if (second.isNotEmpty()) Target.Rabbi(second) else Target.Rabbis
            "faq" -> if (second.isNotEmpty()) Target.FaqSlug(second) else Target.FaqList
            "product" -> if (second.isNotEmpty()) Target.Product(second) else Target.Store
            "all-lessons", "lessons" -> Target.Tab(TAB_LESSONS)
            "all-vid-series", "vid-series" -> Target.Tab(TAB_SERIES)
            "all-podcasts", "podcasts" -> Target.Tab(TAB_PODCASTS)
            "all-rabbis" -> Target.Rabbis
            "שות", "שו\"ת", "faqs" -> Target.FaqList
            "contact" -> Target.Contact
            "about" -> Target.About
            "bookstore", "shop", "product-category" -> Target.Store
            "cart", "checkout", "my-account", "donations", "donate", "shvooshim", "wp-login.php", "wp-admin" -> Target.Browser(url)
            else -> Target.Page(segments.last(), url)
        }
    }
}
