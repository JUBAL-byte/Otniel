package app.jubal.otniel

import okhttp3.HttpUrl
import okhttp3.Request
import java.io.IOException

/** A podcast of the yeshiva that the app can play itself: the card on the site plus its RSS feed. */
data class PodcastRef(
    val feedUrl: String,
    val title: String,
    val presenter: String,
    val image: String,
    val spotify: String,
    val apple: String,
    val youtube: String,
)

/**
 * The yeshiva's podcasts, played in the app from their public RSS feeds (the same feeds
 * Spotify and Apple Podcasts read). A podcast's feed is found through Apple's free podcast
 * directory. YouTube is never played here: its rules allow only its own player, on screen.
 * All calls block; run on Dispatchers.IO.
 */
object Podcasts {
    private const val DAY = 24 * 3600_000L

    private fun itunes(path: String, params: Map<String, String>): J {
        val b = HttpUrl.Builder().scheme("https").host("itunes.apple.com").addPathSegment(path)
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        Net.client.newCall(Request.Builder().url(b.build()).header("User-Agent", Net.USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return J.parse(r.body?.string() ?: "{}")
        }
    }

    private fun lookupFeed(appleId: Long): String =
        itunes("lookup", mapOf("id" to "$appleId", "entity" to "podcast", "country" to "IL"))["results"]
            .list().firstOrNull()?.get("feedUrl")?.str().orEmpty()

    /** Only an exact name match, so a different podcast with a similar name is never played. */
    private fun searchFeed(name: String): String {
        if (name.isBlank()) return ""
        val wanted = Feeds.normalize(name)
        return itunes("search", mapOf("media" to "podcast", "entity" to "podcast", "limit" to "25", "country" to "IL", "term" to name))["results"]
            .list().firstOrNull { Feeds.normalize(it["collectionName"].str()) == wanted }
            ?.get("feedUrl")?.str().orEmpty()
    }

    /** A Spotify show's name, from Spotify's public oEmbed (no account needed). */
    private fun spotifyName(url: String): String {
        val u = HttpUrl.Builder().scheme("https").host("open.spotify.com").addPathSegment("oembed").addQueryParameter("url", url).build()
        return runCatching {
            Net.client.newCall(Request.Builder().url(u).header("User-Agent", Net.USER_AGENT).build()).execute().use { r ->
                if (!r.isSuccessful) "" else J.parse(r.body?.string() ?: "{}")["title"].str()
            }
        }.getOrDefault("")
    }

    /** The podcast's name, from the page of one of its episodes on Spotify. */
    private fun spotifyEpisodeShowName(url: String): String = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).header("Accept-Language", "en").build()
        Net.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) "" else Feeds.showNameInSpotifyPage(r.body?.string() ?: "")
        }
    }.getOrDefault("")

    /** The cards on otniel.org/all-podcasts, kept on the phone for a day. */
    fun shows(): List<Show> {
        val cached = Store.cached("shows")
        if (cached != null && Store.cachedAge("shows") < DAY) {
            runCatching { SiteParse.shows(cached) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return try {
            val body = Net.text("https://otniel.org/all-podcasts/")
            val list = SiteParse.shows(body)
            if (list.isNotEmpty()) Store.putCache("shows", body)
            list
        } catch (e: Exception) {
            cached?.let { runCatching { SiteParse.shows(it) }.getOrNull() } ?: throw e
        }
    }

    private fun feedUrl(show: Show): String {
        Store.showFeed(show.key).takeIf { it.isNotBlank() }?.let { return it }
        var feed = Feeds.appleId(show.apple)?.let { runCatching { lookupFeed(it) }.getOrNull() }.orEmpty()
        if (feed.isBlank()) {
            // Most cards link to one episode on Spotify; its page names the podcast
            val names = LinkedHashSet<String>()
            if (show.title.isNotBlank()) names += show.title
            if (Feeds.isSpotifyShow(show.spotify)) names += spotifyName(show.spotify)
            if (Feeds.isSpotifyEpisode(show.spotify)) names += spotifyEpisodeShowName(show.spotify)
            for (name in names.filter { it.isNotBlank() }) {
                feed = runCatching { searchFeed(name) }.getOrNull().orEmpty()
                if (feed.isNotBlank()) break
            }
        }
        if (feed.isNotBlank()) Store.setShowFeed(show.key, feed)
        return feed
    }

    /** The yeshiva's podcast that [url] (a Spotify, Apple or YouTube link on the site) belongs to, if the app can play it. */
    fun find(url: String): PodcastRef? {
        val show = Feeds.showFor(url, shows()) ?: return null
        val feed = feedUrl(show).takeIf { it.isNotBlank() } ?: return null
        return PodcastRef(feed, show.title, show.presenter, show.image, show.spotify, show.apple, show.youtube)
    }

    /** The feed, fresh from the internet, or the last copy kept on the phone when there is no connection. */
    fun load(feedUrl: String): Feed {
        val key = "feed_" + feedUrl.hashCode().toUInt()
        return try {
            val body = Net.text(Feeds.secure(feedUrl))
            val feed = Feeds.parse(body)
            if (feed.episodes.isNotEmpty()) Store.putCache(key, body)
            feed
        } catch (e: Exception) {
            Store.cached(key)?.let { runCatching { Feeds.parse(it) }.getOrNull() } ?: throw e
        }
    }

    fun cached(feedUrl: String): Feed? =
        Store.cached("feed_" + feedUrl.hashCode().toUInt())?.let { runCatching { Feeds.parse(it) }.getOrNull() }
}
