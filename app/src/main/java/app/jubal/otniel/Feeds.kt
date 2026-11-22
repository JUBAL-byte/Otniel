package app.jubal.otniel

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale

/** One episode of a podcast, from its public RSS feed. */
data class Episode(
    val title: String,
    val date: Long,
    val durationSec: Long,
    val audioUrl: String,
    val description: String,
) {
    /** The audio address is what stays the same between readings of the feed. */
    val id: String get() = audioUrl
}

data class Feed(val title: String, val author: String, val image: String, val episodes: List<Episode>)

/**
 * Reads podcast RSS feeds, and tells which links on the site's podcasts page belong to
 * which podcast (each card links to Spotify, Apple Podcasts and/or YouTube).
 */
object Feeds {
    fun parse(xml: String): Feed {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val channel = doc.selectFirst("channel") ?: doc
        fun own(parent: Element, tag: String): Element? = parent.children().firstOrNull { it.tagName().equals(tag, true) }

        val title = own(channel, "title")?.text()?.trim().orEmpty()
        val author = own(channel, "itunes:author")?.text()?.trim().orEmpty()
        val image = own(channel, "itunes:image")?.attr("href")?.trim()?.takeIf { it.isNotEmpty() }
            ?: own(channel, "image")?.let { own(it, "url") }?.text()?.trim().orEmpty()

        val episodes = channel.select("item").mapNotNull { item ->
            val enclosure = own(item, "enclosure")
            val url = enclosure?.attr("url")?.trim().orEmpty()
            if (url.isEmpty()) return@mapNotNull null
            val type = enclosure?.attr("type")?.lowercase(Locale.ROOT).orEmpty()
            if (type.startsWith("video/")) return@mapNotNull null
            Episode(
                title = own(item, "title")?.text()?.trim().orEmpty().ifBlank { "פרק ללא שם" },
                date = parseDate(own(item, "pubDate")?.text().orEmpty()),
                durationSec = parseDuration(own(item, "itunes:duration")?.text().orEmpty()),
                audioUrl = secure(url),
                description = plainText(
                    own(item, "content:encoded")?.text()?.takeIf { it.isNotBlank() }
                        ?: own(item, "description")?.text()?.takeIf { it.isNotBlank() }
                        ?: own(item, "itunes:summary")?.text().orEmpty()
                ),
            )
        }
        return Feed(title, author, image, episodes)
    }

    /** Android lets the app use only secure (https) addresses; podcast hosts serve both. */
    fun secure(url: String): String = if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    /** HTML descriptions as plain text, keeping the paragraph breaks. */
    fun plainText(html: String): String {
        if (html.isBlank()) return ""
        val marked = html.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("(?i)</p>"), "\n\n")
        return Jsoup.parse(marked).wholeText()
            .replace(' ', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\n *"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /** "1:02:03", "62:03" or "3723" → seconds. */
    fun parseDuration(text: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return 0
        return try {
            t.split(":").fold(0L) { acc, part -> acc * 60 + part.trim().toDouble().toLong() }
        } catch (e: Exception) {
            0
        }
    }

    private val DATE_FORMATS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm Z",
        "dd MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd",
    )

    fun parseDate(text: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return 0
        for (format in DATE_FORMATS) {
            runCatching { SimpleDateFormat(format, Locale.US).parse(t)?.time }.getOrNull()?.let { return it }
        }
        return 0
    }

    // ------------------------------------------------------------------ which podcast a link belongs to

    private val SPOTIFY_SHOW = Regex("open\\.spotify\\.com/(?:[a-z-]+/)?show/([A-Za-z0-9]+)")
    private val SPOTIFY_EPISODE = Regex("open\\.spotify\\.com/(?:[a-z-]+/)?episode/([A-Za-z0-9]+)")
    private val APPLE_ID = Regex("podcasts\\.apple\\.com/.*?/id(\\d+)")
    private val YOUTUBE_LIST = Regex("youtube\\.com/.*[?&]list=([\\w-]+)")

    private fun keys(url: String): Set<String> {
        val u = url.replace("&amp;", "&")
        val out = HashSet<String>()
        SPOTIFY_SHOW.find(u)?.let { out += "spotify:" + it.groupValues[1] }
        SPOTIFY_EPISODE.find(u)?.let { out += "spotify-episode:" + it.groupValues[1] }
        APPLE_ID.find(u)?.let { out += "apple:" + it.groupValues[1] }
        YOUTUBE_LIST.find(u)?.let { out += "youtube:" + it.groupValues[1] }
        return out
    }

    /** A link to a podcast or one of its episodes (on Spotify, Apple Podcasts or a YouTube playlist). */
    fun isPodcastLink(url: String): Boolean = keys(url).isNotEmpty()

    /** The podcast on the site's podcasts page that [url] points to, if any. */
    fun showFor(url: String, shows: List<Show>): Show? {
        val wanted = keys(url)
        if (wanted.isEmpty()) return null
        return shows.firstOrNull { show ->
            listOf(show.spotify, show.apple, show.youtube).any { link -> link.isNotBlank() && keys(link).any { it in wanted } }
        }
    }

    fun isSpotifyShow(url: String): Boolean = SPOTIFY_SHOW.containsMatchIn(url)

    fun isSpotifyEpisode(url: String): Boolean = SPOTIFY_EPISODE.containsMatchIn(url)

    /** The podcast's name on a Spotify episode page ("Listen to this episode from <name> on Spotify"). */
    fun showNameInSpotifyPage(html: String): String {
        val doc = Jsoup.parse(html)
        val text = listOf("meta[property=og:description]", "meta[name=description]", "meta[name=twitter:description]")
            .mapNotNull { doc.selectFirst(it)?.attr("content") }
            .firstOrNull { it.contains(" on Spotify") } ?: return ""
        return Regex("(?s)episode from (.+?) on Spotify").find(text)?.groupValues?.get(1)?.trim().orEmpty()
    }

    fun appleId(url: String): Long? = APPLE_ID.find(url)?.groupValues?.get(1)?.toLongOrNull()

    fun normalize(text: String): String = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** "1:02:03" / "12:05" */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    /** "שעה ו-12 דק׳" / "45 דק׳" */
    fun length(seconds: Long): String {
        if (seconds <= 0) return ""
        val minutes = ((seconds + 30) / 60).toInt()
        if (minutes < 60) return "$minutes דק׳"
        val h = minutes / 60
        val m = minutes % 60
        val hours = if (h == 1) "שעה" else if (h == 2) "שעתיים" else "$h שעות"
        return if (m == 0) hours else "$hours ו-$m דק׳"
    }
}
