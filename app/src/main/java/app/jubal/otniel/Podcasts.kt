package app.jubal.otniel

import android.util.Xml
import okhttp3.HttpUrl
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

data class Episode(
    val title: String,
    val date: Long,
    val durationSec: Long,
    val audioUrl: String,
    val mime: String,
    val rawDescription: String,
    val page: String = "",
) {
    val isVideo: Boolean get() = mime.startsWith("video/")
}

data class Feed(
    val title: String,
    val author: String,
    val rawDescription: String,
    val episodes: List<Episode>,
)

/** What a podcast offers in the app: its RSS episodes, or else its YouTube playlist. */
class ShowContent(val title: String, val feed: Feed?, val videos: List<YtVideo>, val spotifyOnly: Boolean)

/**
 * The yeshiva's podcasts. The site links each one to Spotify, Apple Podcasts and/or YouTube;
 * the app plays the episodes itself, from the podcast's public RSS feed (found through
 * Apple's free directory) or from its YouTube playlist.
 */
object Podcasts {
    private fun itunes(path: String, params: Map<String, String>): J {
        val b = HttpUrl.Builder().scheme("https").host("itunes.apple.com")
        path.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        val request = Request.Builder().url(b.build()).header("User-Agent", Net.USER_AGENT).build()
        Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return J.parse(response.body?.string() ?: "{}")
        }
    }

    private class Found(val name: String, val feed: String)

    private fun lookup(id: Long): Found? {
        val r = itunes("lookup", mapOf("id" to "$id", "entity" to "podcast", "country" to "IL"))["results"].list().firstOrNull()
            ?: return null
        val feed = r["feedUrl"].str()
        return Found(r["collectionName"].str().ifBlank { r["trackName"].str() }, feed)
    }

    private fun searchByName(name: String): Found? {
        if (name.isBlank()) return null
        val results = itunes(
            "search",
            mapOf("media" to "podcast", "entity" to "podcast", "limit" to "25", "country" to "IL", "term" to name),
        )["results"].list()
        val n = norm(name)
        val best = results.firstOrNull { norm(it["collectionName"].str()) == n }
            ?: results.firstOrNull { val c = norm(it["collectionName"].str()); c.isNotEmpty() && (c.contains(n) || n.contains(c)) }
            ?: return null
        val feed = best["feedUrl"].str()
        if (feed.isBlank()) return null
        return Found(best["collectionName"].str(), feed)
    }

    /** A Spotify show's name, from Spotify's public oEmbed (no account needed). */
    private fun spotifyName(url: String): String {
        val b = HttpUrl.Builder().scheme("https").host("open.spotify.com").addPathSegment("oembed")
            .addQueryParameter("url", url).build()
        return runCatching {
            Net.client.newCall(Request.Builder().url(b).header("User-Agent", Net.USER_AGENT).build()).execute().use { r ->
                if (!r.isSuccessful) "" else J.parse(r.body?.string() ?: "{}")["title"].str()
            }
        }.getOrDefault("")
    }

    /** The podcast's name, also for the cards on the site that show it only inside the picture. */
    fun title(show: Show): String {
        if (show.title.isNotBlank()) return show.title
        Store.showTitle(show.key).takeIf { it.isNotBlank() }?.let { return it }
        var name = ""
        Links.appleId(show.apple)?.let { id -> name = runCatching { lookup(id)?.name }.getOrNull().orEmpty() }
        if (name.isBlank() && show.spotify.isNotBlank()) name = spotifyName(show.spotify)
        if (name.isBlank() && show.youtube.isNotBlank()) name = runCatching { YouTube.playlist(show.youtube).first }.getOrDefault("")
        if (name.isNotBlank()) Store.setShowTitle(show.key, name)
        return name
    }

    private fun feedUrl(show: Show): String {
        Store.showFeed(show.key).takeIf { it.isNotBlank() }?.let { return it }
        var feed = ""
        Links.appleId(show.apple)?.let { id -> feed = runCatching { lookup(id)?.feed }.getOrNull().orEmpty() }
        if (feed.isBlank() && show.youtube.isBlank()) {
            val name = title(show)
            feed = runCatching { searchByName(name)?.feed }.getOrNull().orEmpty()
        }
        if (feed.isNotBlank()) Store.setShowFeed(show.key, feed)
        return feed
    }

    fun content(show: Show): ShowContent {
        val name = title(show)
        val feedUrl = feedUrl(show)
        if (feedUrl.isNotBlank()) {
            val feed = runCatching { loadFeed(feedUrl) }.getOrNull()
            if (feed != null && feed.episodes.isNotEmpty()) return ShowContent(name.ifBlank { feed.title }, feed, emptyList(), false)
        }
        if (show.youtube.isNotBlank()) {
            val (listName, videos) = YouTube.playlist(show.youtube)
            return ShowContent(name.ifBlank { listName }, null, videos, false)
        }
        return ShowContent(name, null, emptyList(), show.spotify.isNotBlank())
    }

    fun norm(text: String): String = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun loadFeed(url: String): Feed {
        Net.get(url).use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty")
            return parse(body.byteStream())
        }
    }

    private fun parse(input: InputStream): Feed {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)

        var title = ""
        var author = ""
        var description = ""
        val episodes = ArrayList<Episode>()

        var inItem = false
        var iTitle = ""
        var iDate = ""
        var iDuration = ""
        var iUrl = ""
        var iType = ""
        var iDescription = ""
        var iSummary = ""
        var iContent = ""
        var iLink = ""

        val path = ArrayList<String>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val name = p.name.lowercase(Locale.ROOT)
                val parent = path.lastOrNull()
                if (name == "item" || name == "entry") {
                    inItem = true
                    iTitle = ""; iDate = ""; iDuration = ""; iUrl = ""; iType = ""
                    iDescription = ""; iSummary = ""; iContent = ""; iLink = ""
                    path.add(name)
                } else if (inItem && (parent == "item" || parent == "entry")) {
                    when (name) {
                        "title" -> iTitle = readText(p)
                        "pubdate", "published", "dc:date" -> iDate = readText(p)
                        "itunes:duration" -> iDuration = readText(p)
                        "description" -> iDescription = readText(p)
                        "itunes:summary" -> iSummary = readText(p)
                        "content:encoded" -> iContent = readText(p)
                        "enclosure" -> {
                            iUrl = p.getAttributeValue(null, "url") ?: ""
                            iType = p.getAttributeValue(null, "type") ?: ""
                            path.add(name)
                        }
                        "link" -> {
                            val rel = p.getAttributeValue(null, "rel")
                            val href = p.getAttributeValue(null, "href")
                            if (rel == "enclosure") {
                                if (iUrl.isEmpty()) {
                                    iUrl = href ?: ""
                                    iType = p.getAttributeValue(null, "type") ?: ""
                                }
                                path.add(name)
                            } else if (href != null) {
                                if (iLink.isEmpty()) iLink = href
                                path.add(name)
                            } else {
                                iLink = readText(p)
                            }
                        }
                        "media:content" -> {
                            if (iUrl.isEmpty()) {
                                iUrl = p.getAttributeValue(null, "url") ?: ""
                                iType = p.getAttributeValue(null, "type") ?: ""
                            }
                            path.add(name)
                        }
                        else -> path.add(name)
                    }
                } else if (!inItem && (parent == "channel" || parent == "feed")) {
                    when (name) {
                        "title" -> title = readText(p)
                        "itunes:author", "author" -> author = readText(p)
                        "description", "subtitle" -> description = readText(p)
                        else -> path.add(name)
                    }
                } else {
                    path.add(name)
                }
            } else if (event == XmlPullParser.END_TAG) {
                val name = p.name.lowercase(Locale.ROOT)
                if (path.isNotEmpty()) path.removeAt(path.size - 1)
                if ((name == "item" || name == "entry") && inItem) {
                    inItem = false
                    if (iUrl.isNotBlank()) {
                        episodes += Episode(
                            title = iTitle.ifBlank { "פרק ללא שם" },
                            date = parseDate(iDate),
                            durationSec = parseDuration(iDuration),
                            audioUrl = iUrl.trim(),
                            mime = iType.lowercase(Locale.ROOT),
                            rawDescription = iContent.ifBlank { iDescription.ifBlank { iSummary } },
                            page = iLink.trim(),
                        )
                    }
                }
            }
            event = p.next()
        }
        return Feed(title.ifBlank { "פודקאסט" }, author, description, episodes)
    }

    /** Reads all the text inside the current element, skipping any nested tags. */
    private fun readText(p: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT -> sb.append(p.text)
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
            }
        }
        return sb.toString().trim()
    }

    /** Turns HTML descriptions into plain text, keeping the paragraph breaks. */
    fun plainText(html: String): String {
        if (html.isBlank()) return ""
        val marked = html.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("(?i)</p>"), "\n\n")
        return org.jsoup.Jsoup.parse(marked).wholeText()
            .replace('\u00A0', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\n *"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

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
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd",
    )

    fun parseDate(text: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return 0
        for (format in DATE_FORMATS) {
            try {
                val parsed = SimpleDateFormat(format, Locale.US).parse(t)
                if (parsed != null) return parsed.time
            } catch (e: Exception) {
                // try the next format
            }
        }
        return 0
    }
}
