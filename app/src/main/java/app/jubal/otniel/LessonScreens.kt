package app.jubal.otniel

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast

/** A lesson as the site's card: a play mark for a video, a page mark for a written lesson. */
fun lessonRow(act: MainActivity, l: Lesson): Row {
    val p = Store.recent().firstOrNull { it.item.page == l.link }
    val written = l.html.isNotBlank() && Blocks.parse(l.html).none { it is Block.YouTube || it is Block.Video }
    return LessonCardRow(
        title = l.title,
        by = Site.rabbiNames(l.rabbis),
        date = Dates.site(l.date),
        icon = if (written) R.drawable.ic_description else R.drawable.ic_play_arrow_fill,
        progress = p?.percent ?: -1,
        key = "l:${l.id}",
    ) { act.push(LessonScreen(act, lesson = l)) }
}

/** The site's home page: the opening picture with the name and search, then what is new. */
class HomeScreen(act: MainActivity) : ListScreen(act) {
    override val title = "ישיבת עתניאל"
    private var lessons: List<Lesson> = emptyList()
    private var videos: List<YtVideo> = emptyList()
    private var lessonsError: Throwable? = null
    private var videosError: Throwable? = null
    private var loading = false
    private var loadedAt = 0L

    override fun load(force: Boolean) {
        if (lessons.isEmpty()) lessons = Site.cachedLessons(Site.Query()).take(8)
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
            Site.lessons(Site.Query(), 1, 8).items
        }) { r ->
            r.onSuccess { lessons = it; lessonsError = null }.onFailure { lessonsError = it }
            finished()
        }
        io({ YouTube.channelLatest().take(6) }) { r ->
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

    private fun hero(): Row = ViewRow(100, { parent ->
        val c = parent.context
        val box = BackdropFrame(c)
        box.layoutParams = matchWrap()
        box.setBackgroundColor(c.color(R.color.o_teal))
        val img = ImageView(c).apply {
            id = R.id.row_image
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        box.addBackdrop(View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x22000000, 0x66000000))
        })
        box.addBackdrop(img)
        val col = c.vertical()
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(c.dp(18), c.dp(46), c.dp(18), c.dp(40))
        col.add(c.display(36f, bold = false).apply {
            text = "בית ועד לתורה"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        })
        col.add(c.display(84f).apply {
            text = "ישיבת\nעתניאל"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setLineSpacing(0f, 0.8f)
            setShadowLayer(c.dp(6).toFloat(), 0f, c.dp(1).toFloat(), 0x55000000)
        }, top = c.dp(4))

        // The site's search: a dark see-through field and a light cyan button, in a thin frame
        val frame = c.horizontal()
        frame.background = GradientDrawable().apply {
            cornerRadius = c.dp(32).toFloat()
            setColor(0x22FFFFFF)
            setStroke(c.dp(1), 0x88FFFFFF.toInt())
        }
        frame.setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8))
        val field = EditText(c).apply {
            id = R.id.row_search
            hint = "חפש שיעור..."
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            typeface = Fonts.body(c)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
            setHintTextColor(0xDDFFFFFF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = c.dp(24).toFloat()
                setColor(0x99232F31.toInt())
            }
            setPadding(c.dp(18), c.dp(11), c.dp(18), c.dp(11))
            withoutWebActions()
        }
        frame.add(field, 0, weight = 1f)
        val go = c.button("חיפוש") {}.apply {
            id = R.id.row_action
            cornerRadius = c.dp(22)
        }
        frame.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = c.dp(8)
        })
        col.add(frame, top = c.dp(28))
        box.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box
    }, { view ->
        view.findViewById<ImageView>(R.id.row_image).show(Site.HERO_IMAGE)
        val field = view.findViewById<EditText>(R.id.row_search)
        fun search() {
            val q = field.text.toString().trim()
            field.setText("")
            act.push(LessonsScreen(act, Site.Query(search = q), "חיפוש שיעורים", isRoot = false, searchBox = true))
        }
        field.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                search()
                true
            } else {
                false
            }
        }
        view.findViewById<View>(R.id.row_action).setOnClickListener { search() }
    })

    private fun render() {
        val rows = ArrayList<Row>()
        rows += hero()

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

        val recent = Store.recent().filter { it.percent in 1..97 }.take(3)
        if (recent.isNotEmpty()) {
            rows += HeaderRow("להמשיך מאיפה שהפסקת")
            for (r in recent) {
                rows += LessonCardRow(
                    r.item.title, r.item.subtitle, "",
                    icon = if (r.item.video) R.drawable.ic_play_arrow_fill else R.drawable.ic_graphic_eq,
                    progress = r.percent,
                ) { act.playNow(listOf(r.item)) }
            }
        }

        rows += HeaderRow("שיעורים אחרונים")
        when {
            lessons.isNotEmpty() -> lessons.forEach { rows += lessonRow(act, it) }
            lessonsError != null -> rows += errorRow(lessonsError) { load(true) }
            else -> rows += LoadingRow
        }
        rows += PillRow("לכל השיעורים ←") { act.selectTab(Links.TAB_LESSONS) }

        rows += IntroRow(
            "סדרות שיעורים",
            "סדרות שיעורים מצולמות של רבני הישיבה, בנושאים רבים, לצפייה כאן ביישומון.",
            "לכל הסדרות",
        ) { act.selectTab(Links.TAB_SERIES) }
        when {
            videos.isNotEmpty() -> {
                val items = videos.map { Playable.youtube(it.id, it.title, "ישיבת עתניאל") }
                videos.forEachIndexed { i, v ->
                    rows += PictureCardRow(
                        v.title,
                        meta = listOf(v.date, Dates.duration(v.durationSec)).filter { it.isNotBlank() }.joinToString(" · "),
                        image = YouTube.thumbnail(v.id),
                    ) { act.playNow(items, i) }
                }
            }
            videosError != null -> rows += MessageRow("לא הצלחנו לטעון את הסרטונים החדשים מיוטיוב כרגע.", "לנסות שוב") { load(true) }
            else -> rows += LoadingRow
        }

        rows += IntroRow(
            "פודקאסטים",
            "עשרות פודקאסטים של רבני הישיבה, להאזנה כאן ביישומון, גם כשהמסך כבוי.",
            "לכל הפודקאסטים",
        ) { act.selectTab(Links.TAB_PODCASTS) }
        rows += IntroRow("שו\"ת", "שאלות ותשובות בהלכה מרבני הישיבה.", "לשאלות ותשובות") { act.go(FaqListScreen(act)) }
        rows += IntroRow("חנות ספרים", "ספרי הישיבה וסדרות שיעורים.", "לחנות") { act.go(StoreScreen(act)) }
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
        fun forTopicSlug(act: MainActivity, slug: String, name: String) = LessonsScreen(act, Site.Query(), name, isRoot = false, searchBox = false) {
            Site.topicBySlug(slug)?.let { Site.Query(topic = it.id) to it.name }
        }

        fun forRabbiSlug(act: MainActivity, slug: String) = LessonsScreen(act, Site.Query(), "שיעורים", isRoot = false, searchBox = false) {
            Site.rabbiBySlug(slug)?.let { Site.Query(rabbi = it.id) to it.name }
        }
    }

    override fun actions(): List<TopAction> = if (isRoot) {
        listOf(TopAction(R.drawable.ic_person, "לפי רב") { act.push(RabbisScreen(act)) })
    } else {
        emptyList()
    }

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
            r.onSuccess {
                if (replace) items.clear()
                items += it.items.filter { l -> items.none { o -> o.id == l.id } }
                page = number
                totalPages = it.totalPages
                error = null
            }.onFailure { error = it }
            render()
        }
    }

    private fun render() {
        val rows = ArrayList<Row>()
        val heading = if (!isRoot && searchBox) "חיפוש" else name
        rows += PageTitleRow(heading)
        if (isRoot && topics.isNotEmpty()) {
            val options = listOf("הכול") + topics.map { it.name }
            val selected = topics.indexOfFirst { it.id == query.topic }.let { if (it < 0) 0 else it + 1 }
            rows += CirclesRow(options, selected) { i ->
                query = query.copy(topic = if (i == 0) 0 else topics[i - 1].id)
                items.clear()
                load(force = true)
            }
        }
        if (searchBox) {
            rows += SearchRow("חפש שיעור...", query.search) { text ->
                query = query.copy(search = text)
                items.clear()
                load(force = true)
            }
        }
        if (query.search.isNotBlank() && items.isNotEmpty()) rows += HeaderRow("תוצאות: ${query.search}")
        items.forEach { rows += lessonRow(act, it) }
        when {
            loading -> rows += LoadingRow
            error != null && items.isEmpty() ->
                rows += if (!resolved) MessageRow("לא מצאנו את הדף הזה באתר.") else errorRow(error) { load(true) }
            error != null -> rows += MessageRow("לא הצלחנו לטעון עוד שיעורים.", "לנסות שוב") { loadPage(page + 1, false) }
            items.isEmpty() && !(searchBox && query.search.isBlank()) ->
                rows += MessageRow(if (query.search.isNotBlank()) "לא נמצאו שיעורים לחיפוש הזה." else "אין כאן שיעורים עדיין.")
        }
        show(rows)
    }
}

/** One lesson, laid out like its page on the site: title, teacher, video, summary, text. */
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
        lesson?.let { column.add(BlockViews.title(act, it.title, centered = true), top = act.dp(28)) }
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
                runCatching { Site.lessons(Site.Query(rabbi = r), 1, 5).items.filter { it.id != full.id }.take(4) }.getOrNull()
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

        // Title in the site's large letters, a thin line, and the round play mark
        column.add(BlockViews.title(c, l.title, centered = true), top = c.dp(28))
        val line = View(c).apply { setBackgroundColor(c.color(R.color.o_muted)) }
        column.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(1)).apply {
            topMargin = c.dp(16)
            marginStart = c.dp(40)
            marginEnd = c.dp(40)
        })
        if (playable != null) {
            val mark = c.horizontal().apply { gravity = Gravity.CENTER }
            mark.add(c.circleIcon(R.drawable.ic_play_arrow_fill, 48).apply {
                contentDescription = "צפייה בשיעור"
                isFocusable = true
                isClickable = true
                setOnClickListener { panel?.play() }
            }, c.dp(48), c.dp(48))
            column.add(mark, top = c.dp(16))
        }

        // The teacher: photo, name, date (the name opens his lessons)
        val by = c.horizontal()
        if (media.rabbiImage.isNotBlank() && Store.images()) {
            val photo = ImageView(c).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c.color(R.color.o_soft))
                }
                clipToOutline = true
            }
            by.addView(photo, LinearLayout.LayoutParams(c.dp(52), c.dp(52)).apply { marginEnd = c.dp(12) })
            photo.show(media.rabbiImage)
        }
        val names = c.vertical()
        for (id in l.rabbis) {
            val r = Site.rabbiById(id) ?: continue
            val t = c.text(17f, bold = true).apply {
                text = r.name
                setPadding(c.dp(4), c.dp(2), c.dp(4), c.dp(2))
            }
            t.clickable(8) { act.push(LessonsScreen(act, Site.Query(rabbi = r.id), r.name, isRoot = false, searchBox = false)) }
            names.add(t, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        by.add(names, 0, weight = 1f)
        by.add(c.text(13.5f, bold = true).apply { text = Dates.site(l.date) }, ViewGroup.LayoutParams.WRAP_CONTENT)
        column.add(by, top = c.dp(22))
        val hebrew = Dates.short(l.date)
        if (hebrew.isNotBlank()) {
            column.add(c.text(13f, R.color.o_on_surface_variant).apply {
                text = hebrew
                textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            }, top = c.dp(2))
        }

        // The video, with the site's rounded corners
        if (playable != null) {
            val poster = if (playable.isYouTube) YouTube.thumbnail(playable.videoId) else null
            val p = PlayerPanel(act, playable, poster)
            panel = p
            p.root.roundCorners(18)
            column.add(p.root, top = c.dp(18))
            if (playable.isYouTube) {
                val row = c.horizontal()
                row.gravity = Gravity.CENTER
                row.add(c.button("האזנה בלבד", R.drawable.ic_graphic_eq) {
                    panel?.play(audioOnly = true)
                    Toast.makeText(c, "מתנגן כשמע בלבד, גם כשהמסך כבוי", Toast.LENGTH_SHORT).show()
                }, ViewGroup.LayoutParams.WRAP_CONTENT)
                column.add(row, top = c.dp(10))
            }
        } else if (image.isNotBlank() && Store.images()) {
            val img = ImageView(c).apply { adjustViewBounds = true }
            img.roundCorners(18)
            column.add(img, top = c.dp(18))
            img.show(image)
        }

        // The summary in the site's large light letters, then the text
        if (l.excerpt.isNotBlank() && (playable != null || l.html.isBlank())) {
            column.add(c.display(30f, R.color.o_muted).apply {
                text = l.excerpt.removeSuffix("[…]").removeSuffix("…").trim()
                setLineSpacing(0f, 0.95f)
                setTextIsSelectable(true)
                withoutWebActions()
            }, top = c.dp(18))
        }
        val blocks = Blocks.parse(l.html)
        if (blocks.isNotEmpty()) {
            BlockViews.render(act, column, blocks, l.title, pageLink)
        } else if (playable == null && l.excerpt.isBlank()) {
            column.add(c.text(15f, R.color.o_on_surface_variant).apply { text = "לשיעור הזה אין באתר טקסט או סרטון." }, top = c.dp(12))
        }

        // Topics
        val topicNames = l.topics.mapNotNull { id -> Site.topicName(id)?.let { id to it } }
        if (topicNames.isNotEmpty()) {
            val tags = c.horizontal()
            for ((id, name) in topicNames) {
                val tag = c.text(14f, R.color.o_text, bold = true).apply {
                    text = name
                    setPadding(c.dp(14), c.dp(7), c.dp(14), c.dp(7))
                }
                tag.clickable(18, R.color.o_card) {
                    act.push(LessonsScreen(act, Site.Query(topic = id), name, isRoot = false, searchBox = false))
                }
                tags.addView(tag, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = c.dp(8)
                })
            }
            column.add(android.widget.HorizontalScrollView(c).apply {
                isHorizontalScrollBarEnabled = false
                addView(tags)
            }, top = c.dp(18))
        }

        // "שיעורים נוספים:" as on the site
        if (more.isNotEmpty()) {
            column.add(c.display(34f).apply { text = "שיעורים נוספים:" }, top = c.dp(30), bottom = c.dp(4))
            for (o in more) {
                val row = lessonRow(act, o)
                val v = row.create(column)
                row.bind(v)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, c.dp(7), 0, c.dp(7))
                column.addView(v, lp)
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
