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
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36")
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
        assertTrue(series.count { Links.youtubePlaylist(it.playlistUrl) != null } >= 20)
        assertTrue(series.count { it.count > 0 } >= 20)
    }

    @Test
    fun podcastsPage() {
        live()
        val shows = SiteParse.shows(get("https://otniel.org/all-podcasts/"))
        println("podcasts: ${shows.size}; with title: ${shows.count { it.title.isNotBlank() }}; with Apple: ${shows.count { it.apple.isNotBlank() }}")
        assertTrue(shows.size >= 10)
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
        val blocks = Blocks.parse(page?.html ?: "")
        println("about: ${page?.title}; blocks: ${blocks.size}")
        assertTrue(blocks.size >= 3)
    }
}
