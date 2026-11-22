package app.jubal.otniel

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the real otniel.org (only when LIVE=1, i.e. in the GitHub build), so a change
 * in the site's markup shows up as a failed check instead of an empty screen on the phone.
 */
class LiveSiteTest {
    private fun get(url: String, language: String = ""): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36")
        if (language.isNotEmpty()) c.setRequestProperty("Accept-Language", language)
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun live() = assumeTrue(System.getenv("LIVE") == "1")

    @Test
    fun lessonsAndVideos() {
        live()
        val lessons = SiteParse.lessons(get("https://otniel.org/wp-json/wp/v2/shiurim?per_page=15&_fields=id,date,link,slug,title,excerpt,rabbis,topics,featured_media"))
        println("lessons: ${lessons.size}; newest: ${lessons.firstOrNull()?.title}")
        assertTrue(lessons.size >= 10)
        val media = lessons.take(10).map { SiteParse.lessonMedia(get(it.link)) }
        val withVideo = media.count { it.hasVideo }
        println("lessons with a video among the newest 10: $withVideo; with a teacher photo: ${media.count { it.rabbiImage.isNotBlank() }}; e.g. ${media.firstOrNull { it.rabbiImage.isNotBlank() }?.rabbiImage}")
        assertTrue(withVideo >= 1)
    }

    @Test
    fun seriesPage() {
        live()
        val series = SiteParse.series(get("https://otniel.org/all-vid-series/"))
        println("series: ${series.size}; first: ${series.firstOrNull()}")
        assertTrue(series.size >= 20)
        assertTrue(series.count { it.playlistUrl.contains("list=") } >= 20)
        assertTrue(series.count { it.count > 0 } >= 20)
    }

    @Test
    fun podcastsPage() {
        live()
        val shows = SiteParse.shows(get("https://otniel.org/all-podcasts/"))
        println("podcasts: ${shows.size}; with title: ${shows.count { it.title.isNotBlank() }}; with Apple: ${shows.count { it.apple.isNotBlank() }}")
        assertTrue(shows.size >= 10)
    }

    /** How many of the site's podcasts the app can play itself (from their RSS feed, found through Apple's directory). */
    @Test
    fun podcastFeeds() {
        live()
        val shows = SiteParse.shows(get("https://otniel.org/all-podcasts/"))
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        var byApple = 0
        var byName = 0
        var sample = ""
        val missing = ArrayList<String>()
        val feeds = ArrayList<String>()
        for (show in shows) {
            var feed = Feeds.appleId(show.apple)?.let { id ->
                runCatching { J.parse(get("https://itunes.apple.com/lookup?id=$id&entity=podcast&country=IL"))["results"][0]["feedUrl"].str() }.getOrDefault("")
            }.orEmpty()
            if (feed.isNotBlank()) byApple++ else {
                val name = show.title.ifBlank {
                    when {
                        Feeds.isSpotifyShow(show.spotify) -> runCatching {
                            J.parse(get("https://open.spotify.com/oembed?url=" + enc(show.spotify)))["title"].str()
                        }.getOrDefault("")
                        Feeds.isSpotifyEpisode(show.spotify) -> runCatching {
                            Feeds.showNameInSpotifyPage(get(show.spotify, "en"))
                        }.getOrDefault("")
                        else -> ""
                    }
                }
                if (name.isNotBlank()) {
                    feed = runCatching {
                        J.parse(get("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=25&country=IL&term=" + enc(name)))["results"]
                            .list().firstOrNull { Feeds.normalize(it["collectionName"].str()) == Feeds.normalize(name) }?.get("feedUrl")?.str()
                    }.getOrNull().orEmpty()
                }
                if (feed.isNotBlank()) byName++ else missing += name.ifBlank { show.spotify.ifBlank { show.youtube } }
            }
            if (sample.isEmpty() && feed.isNotBlank()) sample = feed
            if (feed.isNotBlank()) feeds += feed
        }
        println("podcast feeds: ${byApple + byName} of ${shows.size} (by Apple link $byApple, by name $byName); without: ${missing.take(25)}")
        println("podcast feed addresses: ${feeds.take(12)}")
        if (sample.isNotEmpty()) {
            val f = Feeds.parse(get(Feeds.secure(sample)))
            println("podcast feed e.g.: ${f.title} | ${f.episodes.size} episodes | newest: ${f.episodes.firstOrNull()?.title} | ${Feeds.length(f.episodes.firstOrNull()?.durationSec ?: 0)} | ${f.episodes.firstOrNull()?.audioUrl}")
            assertTrue(f.episodes.isNotEmpty())
        }
        assertTrue(byApple + byName >= 5)
    }

    @Test
    fun faqAnswer() {
        live()
        val faqs = SiteParse.faqs(get("https://otniel.org/wp-json/wp/v2/faq?per_page=3&_fields=id,date,title,link,slug,rabbis"))
        assertTrue(faqs.isNotEmpty())
        val a = SiteParse.faqAnswer(get(faqs.first().link))
        println("faq: ${faqs.first().title} | question: ${a.question.take(60)} | answer: ${a.answerHtml.length} chars")
        assertTrue(a.answerHtml.length > 20)
    }

    @Test
    fun terms() {
        live()
        val rabbis = SiteParse.terms(get("https://otniel.org/wp-json/wp/v2/rabbis?per_page=100&_fields=id,name,slug,count"))
        val topics = SiteParse.terms(get("https://otniel.org/wp-json/wp/v2/topics?parent=0&per_page=100&_fields=id,name,slug,count,parent"))
        println("rabbis: ${rabbis.size}, topics: ${topics.size}")
        assertTrue(rabbis.size >= 10)
        assertTrue(topics.size >= 5)
    }

    @Test
    fun books() {
        live()
        val books = SiteParse.books(get("https://otniel.org/wp-json/wc/store/v1/products?per_page=100"))
        println("books: ${books.size}; first: ${books.firstOrNull()?.let { it.name + " " + it.money(it.price) }}")
        assertTrue(books.isNotEmpty())
    }

    @Test
    fun aboutPage() {
        live()
        val page = SiteParse.page(get("https://otniel.org/wp-json/wp/v2/pages?slug=about&_fields=title,content,link"))
        println("about: ${page?.title}; ${page?.html?.length} characters")
        assertTrue((page?.html ?: "").length > 200)
    }
}
