package app.jubal.otniel

import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** The video series of /all-vid-series/, by category, with a quick filter. */
class SeriesScreen(act: MainActivity) : ListScreen(act) {
    override val title = "סדרות שיעורים"
    private var all: List<Series> = emptyList()
    private var error: Throwable? = null
    private var loading = false
    private var category = ""
    private var filter = ""

    override fun load(force: Boolean) {
        if (all.isEmpty()) all = Site.cachedSeries()
        loading = true
        render()
        io({ Site.series() }) { r ->
            loading = false
            doneRefreshing()
            r.onSuccess { if (it.isNotEmpty()) all = it; error = null }.onFailure { error = it }
            render()
        }
    }

    private fun render() {
        val rows = ArrayList<Row>()
        rows += PageTitleRow("סדרות שיעורים מוסרטים")
        rows += SearchRow("חפש סדרה...", filter) {
            filter = it
            render()
        }
        val categories = all.map { it.category }.filter { it.isNotBlank() }.distinct()
        if (categories.isNotEmpty()) {
            val options = listOf("הכול") + categories
            rows += ChipsRow(options, categories.indexOf(category) + 1) { i ->
                category = if (i == 0) "" else categories[i - 1]
                render()
            }
        }
        val shown = all.filter {
            (category.isEmpty() || it.category == category) &&
                (filter.isEmpty() || it.title.contains(filter) || it.subtitle.contains(filter))
        }
        for (s in shown) {
            rows += PictureCardRow(
                s.title,
                meta = listOf(s.subtitle, if (s.count > 0) "${s.count} שיעורים" else "", s.category).filter { it.isNotBlank() }.joinToString(" · "),
                image = s.image,
                key = s.key,
            ) {
                if (s.playlistUrl.isBlank()) {
                    Toast.makeText(act, "לסדרה הזו עדיין אין קישור באתר", Toast.LENGTH_SHORT).show()
                } else {
                    act.push(PlaylistScreen(act, s.playlistUrl, s.title, subtitle = s.subtitle))
                }
            }
        }
        when {
            shown.isEmpty() && loading -> rows += LoadingRow
            shown.isEmpty() && error != null -> rows += errorRow(error) { load(true) }
            shown.isEmpty() -> rows += MessageRow("לא נמצאו סדרות.")
        }
        show(rows)
    }
}

/** The videos of a YouTube playlist, played inside the app. */
class PlaylistScreen(
    act: MainActivity,
    private val url: String,
    private var name: String,
    private val subtitle: String = "",
    private val audioOnly: Boolean = false,
) : ListScreen(act) {
    override val title: String get() = name.ifBlank { "סדרה" }
    private var videos: List<YtVideo> = emptyList()
    private var error: Throwable? = null
    private var loading = true

    override fun actions() = listOf(TopAction(R.drawable.ic_share, "שיתוף") { Outside.share(act, name, url) })

    override fun load(force: Boolean) {
        loading = true
        render()
        io({ YouTube.playlist(url) }) { r ->
            loading = false
            doneRefreshing()
            r.onSuccess { (listName, list) ->
                videos = list
                if (name.isBlank()) {
                    name = listName
                    act.refreshBar()
                }
                error = null
            }.onFailure { error = it }
            render()
        }
    }

    private fun playables(): List<Playable> =
        videos.map { Playable.youtube(it.id, it.title, subtitle.ifBlank { name }, url).copy(video = !audioOnly) }

    private fun render() {
        val rows = ArrayList<Row>()
        rows += PageTitleRow(name.ifBlank { "סדרה" }, if (videos.isNotEmpty()) "${videos.size} ${if (audioOnly) "פרקים" else "שיעורים"}" else "")
        if (videos.isNotEmpty()) {
            rows += PillRow("▶  נגן הכול") { act.playNow(playables(), 0) }
            val items = playables()
            videos.forEachIndexed { i, v ->
                val p = Store.recent().firstOrNull { it.item.id == "yt:${v.id}" }
                rows += if (audioOnly) {
                    LessonCardRow(v.title, "", Dates.duration(v.durationSec), icon = R.drawable.ic_graphic_eq, progress = p?.percent ?: -1) { act.playNow(items, i) }
                } else {
                    PictureCardRow(v.title, Dates.duration(v.durationSec), YouTube.thumbnail(v.id), progress = p?.percent ?: -1) { act.playNow(items, i) }
                }
            }
        }
        when {
            loading && videos.isEmpty() -> rows += LoadingRow
            error != null && videos.isEmpty() -> rows += errorRow(error) { load(true) }
            videos.isEmpty() -> rows += MessageRow("אין סרטונים ברשימה הזו.")
        }
        show(rows)
    }
}

/** The podcasts of /all-podcasts/. Names that the site shows only inside a picture are looked up. */
class PodcastsScreen(act: MainActivity) : ListScreen(act) {
    override val title = "הסכתים"
    private var shows: List<Show> = emptyList()
    private val names = HashMap<String, String>()
    private var error: Throwable? = null
    private var loading = false

    override fun load(force: Boolean) {
        if (shows.isEmpty()) shows = Site.cachedShows()
        loading = true
        render()
        io({ Site.shows() }) { r ->
            loading = false
            doneRefreshing()
            r.onSuccess { if (it.isNotEmpty()) shows = it; error = null }.onFailure { error = it }
            render()
            findNames()
        }
    }

    private fun nameOf(s: Show): String = s.title.ifBlank { names[s.key] ?: Store.showTitle(s.key) }

    private fun findNames() {
        val missing = shows.filter { nameOf(it).isBlank() }
        if (missing.isEmpty()) return
        val gate = Semaphore(3)
        for (s in missing) {
            scope.launch {
                val n = gate.withPermit { withContext(Dispatchers.IO) { runCatching { Podcasts.title(s) }.getOrDefault("") } }
                if (n.isNotBlank()) {
                    names[s.key] = n
                    render()
                }
            }
        }
    }

    private fun render() {
        val rows = ArrayList<Row>()
        rows += PageTitleRow("פודקאסטים")
        for (s in shows) {
            val n = nameOf(s)
            rows += CoverCardRow(n.ifBlank { "פודקאסט" }, s.presenter, s.image, key = s.key) { act.push(ShowScreen(act, s, n)) }
        }
        when {
            shows.isEmpty() && loading -> rows += LoadingRow
            shows.isEmpty() && error != null -> rows += errorRow(error) { load(true) }
            shows.isEmpty() -> rows += MessageRow("לא נמצאו הסכתים.")
        }
        show(rows)
    }
}

/** One podcast's episodes, played inside the app (from its RSS feed, or its YouTube playlist as sound). */
class ShowScreen(act: MainActivity, private val show: Show, private var name: String = show.title) : ListScreen(act) {
    override val title: String get() = name.ifBlank { "הסכת" }
    private var content: ShowContent? = null
    private var error: Throwable? = null
    private var loading = true

    override fun actions(): List<TopAction> {
        val link = show.apple.ifBlank { show.spotify }.ifBlank { show.youtube }
        return if (link.isBlank()) emptyList() else listOf(TopAction(R.drawable.ic_share, "שיתוף") { Outside.share(act, name, link) })
    }

    override fun load(force: Boolean) {
        loading = true
        render()
        io({ Podcasts.content(show) }) { r ->
            loading = false
            doneRefreshing()
            r.onSuccess {
                content = it
                if (it.title.isNotBlank() && it.title != name) {
                    name = it.title
                    act.refreshBar()
                }
                error = null
            }.onFailure { error = it }
            render()
        }
    }

    private fun render() {
        val rows = ArrayList<Row>()
        val c = content
        val feed = c?.feed
        rows += PageTitleRow(name.ifBlank { "פודקאסט" }, show.presenter)
        if (feed != null) {
            val items = feed.episodes.map { Playable.file(it.audioUrl, it.title, name, "", video = it.isVideo) }
            rows += PillRow("▶  נגן מהפרק החדש") { act.playNow(items, 0) }
            feed.episodes.forEachIndexed { i, e ->
                val p = Store.recent().firstOrNull { it.item.id == items[i].id }
                rows += LessonCardRow(
                    e.title,
                    Dates.hebrew(e.date),
                    Dates.duration(e.durationSec),
                    icon = R.drawable.ic_graphic_eq,
                    progress = p?.percent ?: -1,
                ) { act.playNow(items, i) }
            }
        } else if (c != null && c.videos.isNotEmpty()) {
            val items = c.videos.map { Playable.youtube(it.id, it.title, name, show.youtube).copy(video = false) }
            rows += PillRow("▶  נגן הכול") { act.playNow(items, 0) }
            c.videos.forEachIndexed { i, v ->
                rows += LessonCardRow(
                    v.title,
                    "",
                    Dates.duration(v.durationSec),
                    icon = R.drawable.ic_graphic_eq,
                    progress = Store.recent().firstOrNull { it.item.id == items[i].id }?.percent ?: -1,
                ) { act.playNow(items, i) }
            }
        } else if (c != null && c.spotifyOnly) {
            val hasApp = Outside.hasSpotify(act)
            rows += MessageRow(
                "את הפרקים של ההסכת הזה הישיבה מפרסמת רק בספוטיפיי, ולכן אי אפשר לשמוע אותם כאן.",
                if (hasApp) "פתח באפליקציית ספוטיפיי" else null,
            ) { Outside.spotify(act, show.spotify) }
        }
        when {
            loading && c == null -> rows += LoadingRow
            error != null && c == null -> rows += errorRow(error) { load(true) }
            c != null && feed == null && c.videos.isEmpty() && !c.spotifyOnly -> rows += MessageRow("לא מצאנו פרקים להסכת הזה.")
        }
        show(rows)
    }
}
