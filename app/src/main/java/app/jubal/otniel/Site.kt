package app.jubal.otniel

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the app reads from otniel.org. Nothing is copied into the app: every list
 * is asked from the site when it is opened, so what is added there shows up here at once.
 * The last copy of each list is kept on the phone only to show something while loading.
 * All calls block; run them on Dispatchers.IO.
 */
object Site {
    private const val API = "https://otniel.org/wp-json/wp/v2/"
    private const val STORE_API = "https://otniel.org/wp-json/wc/store/v1/"
    private const val LESSON_FIELDS = "id,date,link,slug,title,excerpt,rabbis,topics,featured_media"
    private const val DAY = 24 * 3600_000L

    private val media = ConcurrentHashMap<String, LessonMedia>()
    private val images = ConcurrentHashMap<Long, String>()
    @Volatile private var rabbiList: List<Term>? = null
    @Volatile private var topicList: List<Term>? = null

    data class Query(val rabbi: Long = 0, val topic: Long = 0, val search: String = "")

    class Page<T>(val items: List<T>, val totalPages: Int)

    private fun api(path: String, vararg params: Pair<String, String>): String {
        val b = Uri.parse(API + path).buildUpon()
        for ((k, v) in params) b.appendQueryParameter(k, v)
        return b.build().toString()
    }

    // ------------------------------------------------------------------ lessons

    fun lessons(query: Query, page: Int, perPage: Int = 20): Page<Lesson> {
        val params = arrayListOf("per_page" to "$perPage", "page" to "$page", "_fields" to LESSON_FIELDS)
        if (query.rabbi > 0) params += "rabbis" to "${query.rabbi}"
        if (query.topic > 0) {
            params += "topics[terms]" to "${query.topic}"
            params += "topics[include_children]" to "true"
        }
        if (query.search.isNotBlank()) params += "search" to query.search.trim()
        val url = api("shiurim", *params.toTypedArray())
        return try {
            val (body, pages) = Net.textWithHeader(url, "X-WP-TotalPages")
            if (page == 1 && query.search.isBlank()) Store.putCache("lessons_${query.rabbi}_${query.topic}", body)
            Page(SiteParse.lessons(body), pages?.toIntOrNull() ?: page)
        } catch (e: java.io.IOException) {
            // Asking past the last page answers 400; that just means "no more"
            if (page > 1 && e.message == "HTTP 400") Page(emptyList(), page - 1) else throw e
        }
    }

    fun cachedLessons(query: Query): List<Lesson> =
        Store.cached("lessons_${query.rabbi}_${query.topic}")?.let { runCatching { SiteParse.lessons(it) }.getOrNull() } ?: emptyList()

    /** The newest few lessons, small and quick (for the "new" check). */
    fun newestLessons(count: Int = 5): List<Lesson> =
        SiteParse.lessons(Net.text(api("shiurim", "per_page" to "$count", "_fields" to "id,date,link,slug,title,rabbis")))

    fun lesson(id: Long): Lesson =
        SiteParse.lesson(J.parse(Net.text(api("shiurim/$id", "_fields" to "$LESSON_FIELDS,content"))))

    fun lessonBySlug(slug: String): Lesson? =
        SiteParse.lessons(Net.text(api("shiurim", "slug" to slug, "_fields" to "$LESSON_FIELDS,content"))).firstOrNull()

    /** The lesson's video, which is on its page and not in the API. */
    fun lessonMedia(link: String): LessonMedia {
        media[link]?.let { return it }
        val found = SiteParse.lessonMedia(Net.text(link))
        media[link] = found
        return found
    }

    fun imageUrl(mediaId: Long): String {
        if (mediaId <= 0) return ""
        images[mediaId]?.let { return it }
        val url = runCatching { SiteParse.mediaUrl(Net.text(api("media/$mediaId", "_fields" to "source_url,media_details"))) }.getOrDefault("")
        images[mediaId] = url
        return url
    }

    // ------------------------------------------------------------------ rabbis and topics

    private fun termsFrom(key: String, url: String): List<Term> {
        val cached = Store.cached(key)
        if (cached != null && Store.cachedAge(key) < DAY) {
            runCatching { return SiteParse.terms(cached) }
        }
        return try {
            val body = Net.text(url)
            Store.putCache(key, body)
            SiteParse.terms(body)
        } catch (e: Exception) {
            cached?.let { runCatching { SiteParse.terms(it) }.getOrNull() } ?: throw e
        }
    }

    fun rabbis(): List<Term> {
        rabbiList?.let { return it }
        val list = termsFrom("rabbis", api("rabbis", "per_page" to "100", "_fields" to "id,name,slug,count"))
        rabbiList = list
        return list
    }

    /** Names for [ids], from what is already loaded (never goes to the network, safe on the main thread). */
    fun rabbiNames(ids: List<Long>): String {
        if (ids.isEmpty()) return ""
        val all = rabbiList ?: Store.cached("rabbis")?.let { runCatching { SiteParse.terms(it) }.getOrNull() }?.also { rabbiList = it } ?: return ""
        return ids.mapNotNull { id -> all.firstOrNull { it.id == id }?.name }.joinToString(", ")
    }

    fun rabbiById(id: Long): Term? = rabbiList?.firstOrNull { it.id == id }

    fun topicName(id: Long): String? = topicList?.firstOrNull { it.id == id }?.name

    fun rabbiBySlug(slug: String): Term? = rabbis().firstOrNull { Uri.decode(it.slug) == slug || it.slug == slug }

    /** The main topics (תנ"ך, הלכה, מחשבה...), as in the site's menu. */
    fun topics(): List<Term> {
        topicList?.let { return it }
        val list = termsFrom("topics", api("topics", "parent" to "0", "per_page" to "100", "_fields" to "id,name,slug,count,parent"))
            .filter { it.count > 0 }
            .sortedByDescending { it.count }
        topicList = list
        return list
    }

    fun topicBySlug(slug: String): Term? {
        topics().firstOrNull { Uri.decode(it.slug) == slug || it.slug == slug }?.let { return it }
        return SiteParse.terms(Net.text(api("topics", "slug" to slug, "_fields" to "id,name,slug,count,parent"))).firstOrNull()
    }

    // ------------------------------------------------------------------ video series and podcasts (only on their pages)

    fun series(): List<Series> {
        val body = Net.text("https://otniel.org/all-vid-series/")
        val list = SiteParse.series(body)
        if (list.isNotEmpty()) Store.putCache("series", body)
        return list
    }

    fun cachedSeries(): List<Series> = Store.cached("series")?.let { runCatching { SiteParse.series(it) }.getOrNull() } ?: emptyList()

    fun shows(): List<Show> {
        val body = Net.text("https://otniel.org/all-podcasts/")
        val list = SiteParse.shows(body)
        if (list.isNotEmpty()) Store.putCache("shows", body)
        return list
    }

    fun cachedShows(): List<Show> = Store.cached("shows")?.let { runCatching { SiteParse.shows(it) }.getOrNull() } ?: emptyList()

    // ------------------------------------------------------------------ Q&A

    fun faqs(page: Int, perPage: Int = 30): Page<Faq> {
        val (body, pages) = Net.textWithHeader(
            api("faq", "per_page" to "$perPage", "page" to "$page", "_fields" to "id,date,title,link,slug,rabbis"),
            "X-WP-TotalPages",
        )
        return Page(SiteParse.faqs(body), pages?.toIntOrNull() ?: page)
    }

    fun faqBySlug(slug: String): Faq? =
        SiteParse.faqs(Net.text(api("faq", "slug" to slug, "_fields" to "id,date,title,link,slug,rabbis"))).firstOrNull()

    fun faqAnswer(link: String): FaqAnswer = SiteParse.faqAnswer(Net.text(link))

    // ------------------------------------------------------------------ pages and books

    fun page(slug: String): SitePage? = SiteParse.page(Net.text(api("pages", "slug" to slug, "_fields" to "title,content,link")))

    fun books(): List<Book> {
        val list = ArrayList<Book>()
        var page = 1
        while (page <= 5) {
            val b = Uri.parse(STORE_API + "products").buildUpon()
                .appendQueryParameter("per_page", "100")
                .appendQueryParameter("page", "$page")
                .build().toString()
            val items = SiteParse.books(Net.text(b))
            list += items
            if (items.size < 100) break
            page++
        }
        return list
    }

    fun bookBySlug(slug: String): Book? {
        val url = Uri.parse(STORE_API + "products").buildUpon().appendQueryParameter("slug", slug).build().toString()
        return SiteParse.books(Net.text(url)).firstOrNull()
    }

    /** Buying happens on the site, in the phone's browser: this link puts the book in the site's cart. */
    fun buyLink(book: Book): String = "https://otniel.org/cart/?add-to-cart=${book.id}"
}
