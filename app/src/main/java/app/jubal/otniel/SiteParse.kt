package app.jubal.otniel

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * Reads what otniel.org sends: the WordPress / WooCommerce JSON, and the few pages
 * whose content exists only as HTML (video series, podcasts, a lesson's video, Q&A answers).
 * No Android here, so all of it is tested on a computer too.
 */
object SiteParse {
    const val BASE = "https://otniel.org/"

    /** "&quot;" → '"', "&#8211;" → '–' and so on; also drops tags. */
    fun clean(text: String): String {
        if (text.isEmpty()) return ""
        val unescaped = if (text.contains('<')) Jsoup.parse(text).text() else Parser.unescapeEntities(text, false)
        return unescaped.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun ids(j: J): List<Long> = j.list().map { it.long() }.filter { it > 0 }

    // ------------------------------------------------------------------ JSON (WordPress REST)

    fun lesson(o: J): Lesson = Lesson(
        id = o["id"].long(),
        date = o["date"].str(),
        title = clean(o["title"].rendered()),
        link = o["link"].str(),
        slug = o["slug"].str(),
        rabbis = ids(o["rabbis"]),
        topics = ids(o["topics"]),
        excerpt = clean(o["excerpt"].rendered()),
        html = o["content"].rendered(),
        imageId = o["featured_media"].long(),
    )

    fun lessons(json: String): List<Lesson> = J.parse(json).list().map { lesson(it) }.filter { it.id > 0 }

    fun terms(json: String): List<Term> = J.parse(json).list().map {
        Term(it["id"].long(), clean(it["name"].str()), it["slug"].str(), it["count"].int(), it["parent"].long())
    }.filter { it.id > 0 }

    fun faqs(json: String): List<Faq> = J.parse(json).list().map {
        Faq(it["id"].long(), it["date"].str(), clean(it["title"].rendered()), it["link"].str(), it["slug"].str(), ids(it["rabbis"]))
    }.filter { it.id > 0 }

    fun page(json: String): SitePage? {
        val o = J.parse(json).let { if (it.raw is List<*>) it[0] else it }
        if (o.isNull) return null
        return SitePage(clean(o["title"].rendered()), o["content"].rendered(), o["link"].str())
    }

    /** Best picture address of a media item: a medium size if there is one, else the original. */
    fun mediaUrl(json: String): String {
        val o = J.parse(json)
        val sizes = o["media_details"]["sizes"]
        for (size in listOf("medium_large", "large", "medium", "full")) {
            val url = sizes[size]["source_url"].str()
            if (url.isNotBlank()) return url
        }
        return o["source_url"].str()
    }

    fun books(json: String): List<Book> = J.parse(json).list().map { book(it) }.filter { it.id > 0 }

    fun book(o: J): Book {
        val prices = o["prices"]
        return Book(
            id = o["id"].long(),
            name = clean(o["name"].str()),
            link = o["permalink"].str(),
            price = prices["price"].long(),
            regularPrice = prices["regular_price"].long(),
            minorUnit = prices["currency_minor_unit"].int(2),
            symbol = prices["currency_symbol"].str("₪").ifBlank { "₪" },
            purchasable = o["is_purchasable"].bool(true),
            inStock = o["is_in_stock"].bool(true),
            images = o["images"].list().map { it["src"].str() }.filter { it.isNotBlank() },
            html = o["description"].str().ifBlank { o["short_description"].str() },
        )
    }

    // ------------------------------------------------------------------ HTML pages

    private fun doc(html: String): Document = Jsoup.parse(html, BASE)

    /** The main part of a single page, without the header, footer and the "more lessons" lists. */
    private fun main(doc: Document): Element {
        val single = doc.selectFirst("[data-elementor-type=single-post], [data-elementor-type=single-page], [data-elementor-type=single]")
            ?: doc.selectFirst("main") ?: doc.body()
        return single
    }

    private fun insideListing(el: Element): Boolean = el.parents().any { it.hasClass("jet-listing-grid") }

    /** The YouTube (or uploaded) video a lesson page shows, and the teacher's photo. */
    fun lessonMedia(html: String): LessonMedia {
        val doc = doc(html)
        val main = main(doc)
        val photo = main.select(".elementor-widget-image img").firstOrNull { !insideListing(it) }?.let { img ->
            val src = img.absUrl("src")
            if (src.isBlank() || src.startsWith("data:")) img.absUrl("data-src") else src
        } ?: ""
        return findVideo(main).copy(rabbiImage = photo)
    }

    private fun findVideo(main: Element): LessonMedia {
        for (el in main.select(".elementor-widget-video[data-settings]")) {
            if (insideListing(el)) continue
            val s = runCatching { J.parse(el.attr("data-settings")) }.getOrNull() ?: continue
            val type = s["video_type"].str()
            val yt = Links.youtubeId(s["youtube_url"].str())
            if (yt != null && (type.isEmpty() || type == "youtube")) return LessonMedia(youtubeId = yt)
            val hosted = s["hosted_url"]["url"].str().ifBlank { s["external_url"]["url"].str() }
            if (hosted.isNotBlank()) return LessonMedia(hostedUrl = hosted)
            if (yt != null) return LessonMedia(youtubeId = yt)
        }
        // Plain embeds, in case a lesson uses one
        for (f in main.select("iframe[src], iframe[data-src], iframe[data-lazy-src]")) {
            if (insideListing(f)) continue
            val src = f.attr("src").ifBlank { f.attr("data-src") }.ifBlank { f.attr("data-lazy-src") }
            Links.youtubeId(src)?.let { return LessonMedia(youtubeId = it) }
        }
        for (v in main.select("video[src], video source[src]")) {
            if (insideListing(v)) continue
            val src = v.absUrl("src")
            if (src.isNotBlank()) return LessonMedia(hostedUrl = src)
        }
        return LessonMedia()
    }

    private fun image(el: Element): String {
        val img = el.selectFirst("img") ?: return ""
        val lazy = img.absUrl("data-src").ifBlank { img.absUrl("data-lazy-src") }
        val src = img.absUrl("src")
        return if (src.isBlank() || src.startsWith("data:")) lazy else src
    }

    private val LESSON_COUNT = Regex("(\\d+)\\s*שיעור")

    /** The cards on /all-vid-series/. */
    fun series(html: String): List<Series> {
        val doc = doc(html)
        val out = ArrayList<Series>()
        for (item in doc.select(".jet-listing-grid__item")) {
            val title = clean(item.selectFirst(".elementor-icon-box-title, h3, h2")?.text() ?: "")
            if (title.isEmpty()) continue
            val subtitle = clean(item.selectFirst(".elementor-icon-box-description")?.text() ?: "")
            val link = item.select("a[href]").map { it.absUrl("href") }.firstOrNull { Links.youtubePlaylist(it) != null } ?: ""
            var count = 0
            var category = ""
            for (li in item.select(".elementor-icon-list-text")) {
                val t = clean(li.text())
                val m = LESSON_COUNT.find(t)
                if (m != null) count = m.groupValues[1].toInt() else if (t.isNotEmpty() && category.isEmpty()) category = t
            }
            out += Series(title, subtitle, count, category, link, image(item))
        }
        return out.distinctBy { it.title + "|" + it.playlistUrl }
    }

    /** The cards on /all-podcasts/. Some have no written title (it is only in the picture). */
    fun shows(html: String): List<Show> {
        val doc = doc(html)
        val out = ArrayList<Show>()
        for (item in doc.select(".jet-listing-grid__item")) {
            val title = clean(item.selectFirst(".elementor-heading-title, h2, h3")?.text() ?: "")
            val presenter = clean(item.selectFirst(".jet-listing-dynamic-field__content")?.text() ?: "")
            var spotify = ""
            var youtube = ""
            var apple = ""
            for (a in item.select("a[href]")) {
                val href = a.absUrl("href").ifBlank { a.attr("href") }
                when {
                    href.contains("spotify.com/") && spotify.isEmpty() -> spotify = href.substringBefore("?si=").substringBefore("&si=")
                    Links.youtubePlaylist(href) != null && youtube.isEmpty() -> youtube = href
                    href.contains("podcasts.apple.com/") && apple.isEmpty() -> apple = href
                }
            }
            if (spotify.isEmpty() && youtube.isEmpty() && apple.isEmpty()) continue
            out += Show(title, presenter, spotify, youtube, apple, image(item))
        }
        return out.distinctBy { it.key }
    }

    /** The question and answer of one Q&A page. */
    fun faqAnswer(html: String): FaqAnswer {
        val doc = doc(html)
        val main = main(doc)
        val headings = main.select(".elementor-widget-heading, .elementor-widget-text-editor, .elementor-widget-theme-post-content")
            .filter { !insideListing(it) }
        var question = ""
        val answer = StringBuilder()
        var mode = 0 // 1 = in question, 2 = in answer
        for (h in headings) {
            val text = clean(h.text())
            when {
                text == "שאלה:" || text == "שאלה" -> mode = 1
                text == "תשובה:" || text == "תשובה" -> mode = 2
                mode == 2 && answer.isNotEmpty() && (text.endsWith("נוספות:") || text.endsWith("נוספים:")) -> mode = 3
                mode == 1 && text.isNotEmpty() -> {
                    question = if (question.isEmpty()) text else "$question\n$text"
                }
                mode == 2 -> {
                    val inner = h.selectFirst(".elementor-heading-title, .elementor-widget-container") ?: h
                    answer.append("<p>").append(inner.html()).append("</p>")
                }
            }
        }
        return FaqAnswer(question, answer.toString())
    }
}
