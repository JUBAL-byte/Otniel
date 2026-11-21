package app.jubal.otniel

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

fun lessonRow(act: MainActivity, l: Lesson): Row {
    val p = Store.recent().firstOrNull { it.item.page == l.link }
    return EntryRow(
        title = l.title,
        subtitle = Site.rabbiNames(l.rabbis),
        meta = Dates.short(l.date),
        icon = R.drawable.ic_lesson,
        progress = p?.percent ?: -1,
        key = "l:${l.id}",
    ) { act.push(LessonScreen(act, lesson = l)) }
}

/** The opening page: what is new on the site and on the channel, and where you stopped. */
class HomeScreen(act: MainActivity) : ListScreen(act) {
    override val title = "ישיבת עתניאל"
    private var lessons: List<Lesson> = emptyList()
    private var videos: List<YtVideo> = emptyList()
    private var lessonsError: Throwable? = null
    private var videosError: Throwable? = null
    private var loading = false
    private var loadedAt = 0L

    override fun actions() = listOf(
        TopAction(R.drawable.ic_search, "חיפוש שיעורים") {
            act.push(LessonsScreen(act, Site.Query(), "חיפוש שיעורים", isRoot = false, searchBox = true))
        },
    )

    override fun load(force: Boolean) {
        if (lessons.isEmpty()) lessons = Site.cachedLessons(Site.Query()).take(10)
        render()
        if (loading) return
        loading = true
        var pending = 2
        fun finished() {
            if (--pending == 0) {
                loading = false
                loadedAt = System.currentTimeMillis()
                doneRefreshing()
            }
            render()
        }
        io({
            runCatching { Site.rabbis() }
            Site.lessons(Site.Query(), 1, 10).items
        }) { r ->
            r.onSuccess { lessons = it; lessonsError = null }.onFailure { lessonsError = it }
            finished()
        }
        io({ YouTube.channelLatest().take(8) }) { r ->
            r.onSuccess { videos = it; videosError = null }.onFailure { videosError = it }
            finished()
        }
    }

    fun refreshSoon() {
        if (isBuilt) load(force = true)
    }

    override fun onShow() {
        if (!isBuilt) return
        if (System.currentTimeMillis() - loadedAt > 2 * 60_000L) load(force = true) else render()
    }

    private fun render() {
        val rows = ArrayList<Row>()

        if (!Store.linksTipDone() && act.linksEnabled() != true) {
            val modern = android.os.Build.VERSION.SDK_INT >= 31
            rows += NoticeRow(
                if (modern) "כדי שקישורים לאתר הישיבה (otniel.org) ייפתחו ישר ביישומון, צריך לאשר זאת פעם אחת: במסך שייפתח לחץ על \"הוספת קישור\" וסמן את otniel.org."
                else "כשתלחץ בפעם הראשונה על קישור לאתר הישיבה (otniel.org), הטלפון ישאל איך לפתוח אותו: בחר \"עתניאל\" ואז \"תמיד\".",
                if (modern) "לאישור" else "הבנתי",
                onButton = {
                    Store.setLinksTipDone()
                    if (modern) act.openLinkSettings()
                    render()
                },
                onClose = {
                    Store.setLinksTipDone()
                    render()
                },
            )
        }

        val recent = Store.recent().filter { it.percent in 1..97 }.take(4)
        if (recent.isNotEmpty()) {
            rows += HeaderRow("להמשיך מאיפה שהפסקת")
            for (r in recent) {
                rows += EntryRow(
                    r.item.title, r.item.subtitle,
                    icon = if (r.item.video) R.drawable.ic_smart_display else R.drawable.ic_graphic_eq,
                    image = if (r.item.isYouTube) YouTube.thumbnail(r.item.videoId) else null,
                    progress = r.percent,
                ) { act.playNow(listOf(r.item)) }
            }
        }

        rows += HeaderRow("שיעורים חדשים באתר", "לכל השיעורים") { act.selectTab(Links.TAB_LESSONS) }
        when {
            lessons.isNotEmpty() -> lessons.forEach { rows += lessonRow(act, it) }
            lessonsError != null -> rows += errorRow(lessonsError) { load(true) }
            else -> rows += LoadingRow
        }

        rows += HeaderRow("חדש בערוץ היוטיוב של הישיבה")
        when {
            videos.isNotEmpty() -> {
                val items = videos.map { Playable.youtube(it.id, it.title, "ישיבת עתניאל") }
                videos.forEachIndexed { i, v ->
                    rows += EntryRow(
                        v.title,
                        meta = listOf(v.date, Dates.duration(v.durationSec)).filter { it.isNotBlank() }.joinToString(" · "),
                        icon = R.drawable.ic_smart_display,
                        image = YouTube.thumbnail(v.id),
                    ) { act.playNow(items, i) }
                }
            }
            videosError != null -> rows += MessageRow("לא הצלחנו לטעון את הסרטונים מיוטיוב כרגע.", "לנסות שוב") { load(true) }
            else -> rows += LoadingRow
        }

        rows += HeaderRow("עוד בישיבה")
        rows += EntryRow("סדרות שיעורים בווידאו", icon = R.drawable.ic_smart_display) { act.selectTab(Links.TAB_SERIES) }
        rows += EntryRow("הסכתים (פודקאסטים)", icon = R.drawable.ic_podcasts) { act.selectTab(Links.TAB_PODCASTS) }
        rows += EntryRow("שו\"ת", icon = R.drawable.ic_description) { act.push(FaqListScreen(act)) }
        rows += EntryRow("חנות הספרים", icon = R.drawable.ic_book) { act.push(StoreScreen(act)) }
        show(rows)
    }
}

/** A list of lessons: all of them, by rabbi, by topic, or a search. Loads more as you scroll. */
class LessonsScreen(
    act: MainActivity,
    private var query: Site.Query,
    private var name: String,
    private val isRoot: Boolean,
    private val searchBox: Boolean = isRoot,
    private val resolve: (() -> Pair<Site.Query, String>?)? = null,
) : ListScreen(act) {
    override val title: String get() = name
    private val items = ArrayList<Lesson>()
    private var page = 0
    private var totalPages = 1
    private var loading = false
    private var error: Throwable? = null
    private var topics: List<Term> = emptyList()
    private var resolved = resolve == null
    private var generation = 0

    companion object {
        fun forTopicSlug(act: MainActivity, slug: String) = LessonsScreen(act, Site.Query(), "נושא", isRoot = false, searchBox = false) {
            Site.topicBySlug(slug)?.let { Site.Query(topic = it.id) to it.name }
        }

        fun forRabbiSlug(act: MainActivity, slug: String) = LessonsScreen(act, Site.Query(), "שיעורים", isRoot = false, searchBox = false) {
            Site.rabbiBySlug(slug)?.let { Site.Query(rabbi = it.id) to it.name }
        }
    }

    override fun actions(): List<TopAction> = if (isRoot) {
        listOf(TopAction(R.drawable.ic_person, "לפי רב") { act.push(RabbisScreen(act)) })
    } else emptyList()

    override fun load(force: Boolean) {
        if (items.isEmpty() && query.search.isBlank() && resolved) {
            items += Site.cachedLessons(query)
        }
        page = 0
        totalPages = 1
        error = null
        loadPage(1, replace = true)
    }

    override fun onNearEnd() {
        if (!loading && error == null && page in 1 until totalPages) loadPage(page + 1, replace = false)
    }

    private fun loadPage(number: Int, replace: Boolean) {
        loading = true
        val gen = ++generation
        render()
        io({
            if (!resolved) {
                val r = resolve?.invoke() ?: throw IllegalStateException("not found")
                query = r.first
                name = r.second
                resolved = true
            }
            runCatching { Site.rabbis() }
            if (isRoot && topics.isEmpty()) topics = runCatching { Site.topics() }.getOrDefault(emptyList())
            Site.lessons(query, number)
        }) { r ->
            if (gen != generation) return@io
            loading = false
            doneRefreshing()
            act.refreshBar()
            r.onSuccess {
                if (replace) items.clear()
                items += it.items.filter { l -> items.none { it.id == l.id } }
                page = number
                totalPages = it.totalPages
                error = null
            }.onFailure { error = it }
            render()
        }
    }

    private fun render() {
        val rows = ArrayList<Row>()
        if (searchBox) {
            rows += SearchRow("חיפוש בשיעורים", query.search) { text ->
                query = query.copy(search = text)
                items.clear()
                load(force = true)
            }
        }
        if (isRoot && topics.isNotEmpty()) {
            val options = listOf("הכול") + topics.map { it.name }
            val selected = topics.indexOfFirst { it.id == query.topic }.let { if (it < 0) 0 else it + 1 }
            rows += ChipsRow(options, selected) { i ->
                query = query.copy(topic = if (i == 0) 0 else topics[i - 1].id)
                items.clear()
                load(force = true)
            }
        }
        if (query.search.isNotBlank() && items.isNotEmpty()) rows += HeaderRow("תוצאות עבור \"${query.search}\"")
        items.forEach { rows += lessonRow(act, it) }
        when {
            loading -> rows += LoadingRow
            error != null && items.isEmpty() ->
                rows += if (!resolved) MessageRow("לא מצאנו את הדף הזה באתר.") else errorRow(error) { load(true) }
            error != null -> rows += MessageRow("לא הצלחנו לטעון עוד שיעורים.", "לנסות שוב") { loadPage(page + 1, false) }
            items.isEmpty() -> rows += MessageRow(if (query.search.isNotBlank()) "לא נמצאו שיעורים לחיפוש הזה." else "אין כאן שיעורים עדיין.")
        }
        show(rows)
    }
}

/** One lesson: its video (played inside the app), text, files and more lessons by the same rabbi. */
class LessonScreen(
    act: MainActivity,
    private var lesson: Lesson? = null,
    private val slug: String? = null,
) : Screen(act), PlayerAware {
    override val title: String get() = "שיעור"
    private lateinit var column: LinearLayout
    private var panel: PlayerPanel? = null
    private var media = LessonMedia()
    private var image = ""
    private var more: List<Lesson> = emptyList()

    override fun actions(): List<TopAction> = listOf(
        TopAction(R.drawable.ic_share, "שיתוף") {
            val l = lesson ?: return@TopAction
            Outside.share(act, l.title, l.link)
        },
    )

    override fun showsPlayerFor(mediaId: String): Boolean = panel?.playable?.id == mediaId

    override fun build(): View {
        val (scroll, col) = BlockViews.page(act)
        column = col
        load()
        return scroll
    }

    private fun load() {
        column.removeAllViews()
        lesson?.let { header(it) }
        column.add(LoadingRow.create(column).also { LoadingRow.bind(it) })
        io({
            runCatching { Site.rabbis() }
            runCatching { Site.topics() }
            val base = lesson
            val full = when {
                base != null && base.html.isNotEmpty() -> base
                base != null -> runCatching { Site.lesson(base.id) }.getOrDefault(base)
                slug != null -> Site.lessonBySlug(slug) ?: throw IllegalStateException("not found")
                else -> throw IllegalStateException("no lesson")
            }
            val m = runCatching { Site.lessonMedia(full.link) }.getOrDefault(LessonMedia())
            val img = if (!m.hasVideo) Site.imageUrl(full.imageId) else ""
            val others = full.rabbis.firstOrNull()?.let { r ->
                runCatching { Site.lessons(Site.Query(rabbi = r), 1, 7).items.filter { it.id != full.id }.take(6) }.getOrNull()
            } ?: emptyList()
            Triple(full, m, img) to others
        }) { r ->
            r.onSuccess { (data, others) ->
                lesson = data.first
                media = data.second
                image = data.third
                more = others
                render()
            }.onFailure { e ->
                column.removeAllViews()
                val msg = if (lesson == null) "לא מצאנו את השיעור הזה באתר." else errorText(e)
                column.add(MessageRow(msg, "לנסות שוב") { load() }.let { row -> row.create(column).also { row.bind(it) } })
            }
        }
    }

    private fun header(l: Lesson) {
        val c = act
        column.add(BlockViews.title(c, l.title))
        val who = Site.rabbiNames(l.rabbis)
        val meta = listOf(who, Dates.full(l.date)).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) column.add(c.text(14f, R.color.o_on_surface_variant).apply { text = meta }, top = c.dp(4))
    }

    private fun render() {
        val l = lesson ?: return
        val c = act
        panel?.release()
        panel = null
        column.removeAllViews()

        val pageLink = l.link
        val who = Site.rabbiNames(l.rabbis)
        val playable = when {
            media.youtubeId.isNotEmpty() -> Playable.youtube(media.youtubeId, l.title, who, pageLink)
            media.hostedUrl.isNotEmpty() -> Playable.file(media.hostedUrl, l.title, who, pageLink, video = true)
            else -> null
        }
        if (playable != null) {
            val poster = if (playable.isYouTube) YouTube.thumbnail(playable.videoId) else null
            val p = PlayerPanel(act, playable, poster)
            panel = p
            // Edge to edge: undo the column's side padding for the video
            column.add(p.root, top = -c.dp(12)).also {
                (p.root.layoutParams as LinearLayout.LayoutParams).apply {
                    marginStart = -c.dp(16)
                    marginEnd = -c.dp(16)
                }
            }
        } else if (image.isNotBlank() && Store.images()) {
            val img = android.widget.ImageView(c).apply { adjustViewBounds = true }
            column.add(img, bottom = c.dp(8))
            img.show(image)
        }

        column.add(BlockViews.title(c, l.title), top = c.dp(12))

        // Rabbi(s) and topics, each opening its own list
        val chips = ChipGroup(c)
        for (id in l.rabbis) {
            val r = Site.rabbiById(id) ?: continue
            chips.addView(Chip(c).apply {
                text = r.name
                setChipIconResource(R.drawable.ic_person)
                setOnClickListener { act.push(LessonsScreen(act, Site.Query(rabbi = r.id), r.name, isRoot = false, searchBox = false)) }
            })
        }
        for (id in l.topics) {
            val name = Site.topicName(id) ?: continue
            chips.addView(Chip(c).apply {
                text = name
                setOnClickListener { act.push(LessonsScreen(act, Site.Query(topic = id), name, isRoot = false, searchBox = false)) }
            })
        }
        if (chips.childCount > 0) column.add(chips, top = c.dp(6))
        val date = Dates.full(l.date)
        if (date.isNotBlank()) column.add(c.text(13.5f, R.color.o_on_surface_variant).apply { text = date }, top = c.dp(4))

        if (playable != null && playable.isYouTube) {
            val row = c.horizontal()
            row.add(c.button("האזנה בלבד", R.drawable.ic_graphic_eq) {
                panel?.play(audioOnly = true)
                Toast.makeText(c, "מתנגן כשמע בלבד, גם כשהמסך כבוי", Toast.LENGTH_SHORT).show()
            }, ViewGroup.LayoutParams.WRAP_CONTENT)
            column.add(row, top = c.dp(8))
        }

        val blocks = Blocks.parse(l.html)
        if (blocks.isNotEmpty()) {
            BlockViews.render(act, column, blocks, l.title, pageLink)
        } else if (playable == null) {
            column.add(c.text(15f, R.color.o_on_surface_variant).apply {
                text = l.excerpt.ifBlank { "לשיעור הזה אין באתר טקסט או סרטון." }
            }, top = c.dp(12))
        }

        if (more.isNotEmpty()) {
            column.add(c.text(17f, bold = true).apply { text = if (who.isNotBlank()) "עוד מאת $who" else "שיעורים נוספים" }, top = c.dp(24), bottom = c.dp(4))
            for (o in more) {
                val row = lessonRow(act, o)
                val v = row.create(column)
                row.bind(v)
                column.addView(v)
            }
        }
        act.updateMini()
    }

    override fun onShow() {
        panel?.refresh()
    }

    override fun onMediaChanged() {
        panel?.refresh()
    }

    override fun destroy() {
        panel?.release()
        super.destroy()
    }
}
