package app.jubal.otniel

/** A rabbi or a topic on the site. */
data class Term(
    val id: Long,
    val name: String,
    val slug: String,
    val count: Int,
    val parent: Long = 0,
)

/** A lesson ("שיעור") from otniel.org/shiurim. */
data class Lesson(
    val id: Long,
    /** Local site time, e.g. 2026-11-19T08:50:12 */
    val date: String,
    val title: String,
    val link: String,
    val slug: String,
    val rabbis: List<Long>,
    val topics: List<Long>,
    val excerpt: String,
    val html: String,
    val imageId: Long,
)

/** The video a lesson page plays (the site keeps it outside the lesson text). */
data class LessonMedia(val youtubeId: String = "", val hostedUrl: String = "") {
    val hasVideo: Boolean get() = youtubeId.isNotEmpty() || hostedUrl.isNotEmpty()
}

/** A video series from otniel.org/all-vid-series (a YouTube playlist). */
data class Series(
    val title: String,
    val subtitle: String,
    val count: Int,
    val category: String,
    val playlistUrl: String,
    val image: String,
) {
    val key: String get() = playlistUrl.ifBlank { "t:$title" }
}

/** A podcast from otniel.org/all-podcasts, with its Spotify / YouTube / Apple addresses. */
data class Show(
    val title: String,
    val presenter: String,
    val spotify: String,
    val youtube: String,
    val apple: String,
    val image: String,
) {
    val key: String get() = apple.ifBlank { spotify }.ifBlank { youtube }.ifBlank { "t:$title" }
}

/** A question in "שו"ת". */
data class Faq(
    val id: Long,
    val date: String,
    val title: String,
    val link: String,
    val slug: String,
    val rabbis: List<Long>,
)

data class FaqAnswer(val question: String, val answerHtml: String)

/** A book or recording from the site's store. Prices are in agorot (minor units). */
data class Book(
    val id: Long,
    val name: String,
    val link: String,
    val price: Long,
    val regularPrice: Long,
    val minorUnit: Int,
    val symbol: String,
    val purchasable: Boolean,
    val inStock: Boolean,
    val images: List<String>,
    val html: String,
) {
    val onSale: Boolean get() = regularPrice > price

    fun money(value: Long): String {
        var div = 1L
        repeat(minorUnit) { div *= 10 }
        val whole = value / div
        val rest = value % div
        return if (rest == 0L) "$whole $symbol" else "$whole.${rest.toString().padStart(minorUnit, '0')} $symbol"
    }
}

/** A plain page of the site (about, contact...). */
data class SitePage(val title: String, val html: String, val link: String)
