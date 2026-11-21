package app.jubal.otniel

import android.net.Uri

/**
 * What the "new content" check reads from otniel.org: the newest lessons (from the site's
 * own WordPress API) and the video-series and podcast pages. All calls block; run on Dispatchers.IO.
 */
object Site {
    private const val API = "https://otniel.org/wp-json/wp/v2/"
    private const val DAY = 24 * 3600_000L

    @Volatile private var rabbiList: List<Term>? = null

    private fun api(path: String, vararg params: Pair<String, String>): String {
        val b = Uri.parse(API + path).buildUpon()
        for ((k, v) in params) b.appendQueryParameter(k, v)
        return b.build().toString()
    }

    /** The newest few lessons, small and quick. */
    fun newestLessons(count: Int = 8): List<Lesson> =
        SiteParse.lessons(Net.text(api("shiurim", "per_page" to "$count", "_fields" to "id,date,link,slug,title,rabbis")))

    /** The rabbis' names, kept on the phone for a day. */
    fun rabbis(): List<Term> {
        rabbiList?.let { return it }
        val cached = Store.cached("rabbis")
        val fresh = cached?.takeIf { Store.cachedAge("rabbis") < DAY }?.let { runCatching { SiteParse.terms(it) }.getOrNull() }
        val list = fresh ?: try {
            val body = Net.text(api("rabbis", "per_page" to "100", "_fields" to "id,name,slug,count"))
            Store.putCache("rabbis", body)
            SiteParse.terms(body)
        } catch (e: Exception) {
            cached?.let { runCatching { SiteParse.terms(it) }.getOrNull() } ?: throw e
        }
        rabbiList = list
        return list
    }

    fun rabbiNames(ids: List<Long>): String {
        if (ids.isEmpty()) return ""
        val all = runCatching { rabbis() }.getOrNull() ?: return ""
        return ids.mapNotNull { id -> all.firstOrNull { it.id == id }?.name }.joinToString(", ")
    }

    fun series(): List<Series> = SiteParse.series(Net.text("https://otniel.org/all-vid-series/"))

    fun shows(): List<Show> = SiteParse.shows(Net.text("https://otniel.org/all-podcasts/"))
}
