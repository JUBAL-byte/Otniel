package app.jubal.otniel

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** The fifth tab: everything else on the site, and the app's settings. */
class MoreScreen(act: MainActivity) : ListScreen(act) {
    override val title = "עוד"

    override fun load(force: Boolean) {
        doneRefreshing()
        val rows = ArrayList<Row>()
        rows += HeaderRow("באתר הישיבה")
        rows += EntryRow("שו\"ת", "שאלות ותשובות מרבני הישיבה", icon = R.drawable.ic_description) { act.push(FaqListScreen(act)) }
        rows += EntryRow("רבני הישיבה", "השיעורים של כל רב", icon = R.drawable.ic_person) { act.push(RabbisScreen(act)) }
        rows += EntryRow("חנות הספרים", "ספרי הישיבה וסדרות שיעורים", icon = R.drawable.ic_book) { act.push(StoreScreen(act)) }
        rows += EntryRow("על הישיבה", icon = R.drawable.ic_info) { act.push(PageScreen(act, "about", "על הישיבה")) }
        rows += EntryRow("צור קשר", icon = R.drawable.ic_phone) { act.push(PageScreen(act, "contact", "צור קשר", contact = true)) }
        rows += EntryRow("תרומה לישיבה", "נפתח בדפדפן של הטלפון", icon = R.drawable.ic_open_in_new) {
            act.confirmBrowser("https://otniel.org/donations/", "התרומה נעשית באתר הישיבה, בדפדפן של הטלפון ולא בתוך היישומון.")
        }
        rows += HeaderRow("היישומון")
        rows += EntryRow("הגדרות", "מראה, גודל, תמונות, התראות", icon = R.drawable.ic_settings) { act.push(SettingsScreen(act)) }
        rows += EntryRow("פתיחת קישורים של האתר ביישומון", "קישור ל־otniel.org ייפתח כאן", icon = R.drawable.ic_link) { act.openLinkSettings() }
        show(rows)
    }
}

class FaqListScreen(act: MainActivity) : ListScreen(act) {
    override val title = "שו\"ת"
    private val items = ArrayList<Faq>()
    private var page = 0
    private var total = 1
    private var loading = false
    private var error: Throwable? = null

    override fun load(force: Boolean) {
        items.clear()
        page = 0
        total = 1
        next()
    }

    override fun onNearEnd() {
        if (!loading && error == null && page in 1 until total) next()
    }

    private fun next() {
        loading = true
        render()
        val number = page + 1
        io({
            runCatching { Site.rabbis() }
            Site.faqs(number)
        }) { r ->
            loading = false
            doneRefreshing()
            r.onSuccess {
                items += it.items
                page = number
                total = it.totalPages
                error = null
            }.onFailure { error = it }
            render()
        }
    }

    private fun render() {
        val rows = ArrayList<Row>()
        for (f in items) {
            rows += EntryRow(f.title, Site.rabbiNames(f.rabbis), Dates.short(f.date), icon = R.drawable.ic_description) {
                act.push(FaqScreen(act, faq = f))
            }
        }
        when {
            loading -> rows += LoadingRow
            error != null -> rows += errorRow(error) { if (items.isEmpty()) load(true) else next() }
            items.isEmpty() -> rows += MessageRow("אין שאלות עדיין.")
        }
        show(rows)
    }
}

class FaqScreen(act: MainActivity, private var faq: Faq? = null, private val slug: String? = null) : Screen(act) {
    override val title = "שו\"ת"
    private lateinit var column: LinearLayout

    override fun actions(): List<TopAction> = listOf(TopAction(R.drawable.ic_share, "שיתוף") {
        val f = faq ?: return@TopAction
        Outside.share(act, f.title, f.link)
    })

    override fun build(): View {
        val (scroll, col) = BlockViews.page(act)
        column = col
        load()
        return scroll
    }

    private fun load() {
        column.removeAllViews()
        column.add(LoadingRow.create(column).also { LoadingRow.bind(it) })
        io({
            runCatching { Site.rabbis() }
            val f = faq ?: slug?.let { Site.faqBySlug(it) } ?: throw IllegalStateException("not found")
            f to Site.faqAnswer(f.link)
        }) { r ->
            column.removeAllViews()
            r.onSuccess { (f, answer) ->
                faq = f
                val c = act
                column.add(BlockViews.title(c, f.title))
                val meta = listOf(Site.rabbiNames(f.rabbis), Dates.full(f.date)).filter { it.isNotBlank() }.joinToString(" · ")
                if (meta.isNotBlank()) column.add(c.text(14f, R.color.o_on_surface_variant).apply { text = meta }, top = c.dp(4))
                if (answer.question.isNotBlank()) {
                    column.add(c.text(17f, R.color.o_primary, bold = true).apply { text = "שאלה" }, top = c.dp(18))
                    column.add(c.text(16.5f).apply {
                        text = answer.question
                        setLineSpacing(0f, 1.25f)
                        setTextIsSelectable(true)
                        withoutWebActions()
                    }, top = c.dp(4))
                }
                column.add(c.text(17f, R.color.o_primary, bold = true).apply { text = "תשובה" }, top = c.dp(18))
                val blocks = Blocks.parse(answer.answerHtml)
                if (blocks.isEmpty()) {
                    column.add(c.text(15f, R.color.o_on_surface_variant).apply { text = "לא הצלחנו לקרוא את התשובה מהאתר." }, top = c.dp(6))
                } else {
                    BlockViews.render(act, column, blocks, f.title, f.link)
                }
            }.onFailure { e ->
                val row = MessageRow(if (faq == null) "לא מצאנו את השאלה הזו באתר." else errorText(e), "לנסות שוב") { load() }
                column.add(row.create(column).also { row.bind(it) })
            }
        }
    }
}

class RabbisScreen(act: MainActivity) : ListScreen(act) {
    override val title = "רבני הישיבה"

    override fun load(force: Boolean) {
        show(listOf(LoadingRow))
        io({ Site.rabbis() }) { r ->
            doneRefreshing()
            r.onSuccess { list ->
                show(list.filter { it.count > 0 }.sortedBy { it.name.removePrefix("הרב ").removePrefix("ד\"ר ").removePrefix("פרופ' ") }.map { t ->
                    EntryRow(t.name, meta = "${t.count} שיעורים", icon = R.drawable.ic_person) {
                        act.push(LessonsScreen(act, Site.Query(rabbi = t.id), t.name, isRoot = false, searchBox = false))
                    }
                })
            }.onFailure { show(listOf(errorRow(it) { load(true) })) }
        }
    }
}

/** A plain page of the site (about, contact...), shown natively. */
class PageScreen(
    act: MainActivity,
    private val slug: String,
    private var name: String,
    private val url: String = "https://otniel.org/$slug/",
    private val contact: Boolean = false,
) : Screen(act) {
    override val title: String get() = name.ifBlank { "ישיבת עתניאל" }
    private lateinit var column: LinearLayout

    override fun build(): View {
        val (scroll, col) = BlockViews.page(act)
        column = col
        load()
        return scroll
    }

    private fun load() {
        column.removeAllViews()
        if (contact) contactButtons()
        val loading = LoadingRow.create(column).also { LoadingRow.bind(it) }
        column.add(loading)
        io({ Site.page(slug) }) { r ->
            column.removeView(loading)
            val page = r.getOrNull()
            if (page != null) {
                if (name.isBlank()) {
                    name = page.title
                    act.refreshBar()
                }
                val blocks = Blocks.parse(page.html)
                BlockViews.render(act, column, blocks, page.title, page.link.ifBlank { url })
            } else if (!contact) {
                val msg = if (r.isFailure) errorText(r.exceptionOrNull()) else "הדף הזה לא זמין ביישומון."
                val row = if (r.isFailure) MessageRow(msg, "לנסות שוב") { load() } else MessageRow(msg, "פתח בדפדפן") { act.confirmBrowser(url) }
                column.add(row.create(column).also { row.bind(it) })
            }
        }
    }

    private fun contactButtons() {
        val c = act
        column.add(c.text(15f, R.color.o_on_surface_variant).apply { text = "ישיבת עתניאל, ד.נ. הר חברון 90407" })
        for (phone in listOf("02-9964241", "02-9964775")) {
            column.add(BlockViews.mediaCard(c, "חיוג: $phone", R.drawable.ic_phone) { Outside.dial(c, "tel:${phone.replace("-", "")}") }, top = c.dp(8))
        }
    }
}

/** The site's book store. Buying itself happens on the site, in the phone's browser. */
class StoreScreen(act: MainActivity) : ListScreen(act) {
    override val title = "חנות הספרים"

    override fun load(force: Boolean) {
        show(listOf(LoadingRow))
        io({ Site.books() }) { r ->
            doneRefreshing()
            r.onSuccess { books ->
                val rows = ArrayList<Row>()
                rows += MessageRow("את הספרים אפשר לראות כאן. הרכישה עצמה נעשית באתר הישיבה, בדף התשלום המאובטח שלו, בדפדפן של הטלפון.")
                books.forEach { b ->
                    rows += EntryRow(
                        b.name,
                        price(b),
                        meta = if (!b.inStock) "אזל מהמלאי" else "",
                        icon = R.drawable.ic_book,
                        image = b.images.firstOrNull(),
                        wide = false,
                    ) { act.push(BookScreen(act, book = b)) }
                }
                if (books.isEmpty()) rows += MessageRow("אין כרגע ספרים בחנות.")
                show(rows)
            }.onFailure { show(listOf(errorRow(it) { load(true) })) }
        }
    }

    companion object {
        fun price(b: Book): String = if (b.onSale) "${b.money(b.price)} (במקום ${b.money(b.regularPrice)})" else b.money(b.price)
    }
}

class BookScreen(act: MainActivity, private var book: Book? = null, private val slug: String? = null) : Screen(act) {
    override val title = "חנות הספרים"
    private lateinit var column: LinearLayout

    override fun actions(): List<TopAction> = listOf(TopAction(R.drawable.ic_share, "שיתוף") {
        val b = book ?: return@TopAction
        Outside.share(act, b.name, b.link)
    })

    override fun build(): View {
        val (scroll, col) = BlockViews.page(act)
        column = col
        load()
        return scroll
    }

    private fun load() {
        column.removeAllViews()
        val b = book
        if (b != null) {
            render(b)
            return
        }
        column.add(LoadingRow.create(column).also { LoadingRow.bind(it) })
        io({ slug?.let { Site.bookBySlug(it) } }) { r ->
            column.removeAllViews()
            val found = r.getOrNull()
            if (found != null) {
                book = found
                render(found)
            } else {
                val row = MessageRow(if (r.isFailure) errorText(r.exceptionOrNull()) else "לא מצאנו את הספר הזה בחנות.", "לנסות שוב") { load() }
                column.add(row.create(column).also { row.bind(it) })
            }
        }
    }

    private fun render(b: Book) {
        val c = act
        if (b.images.isNotEmpty() && Store.images()) {
            val strip = c.horizontal()
            for (url in b.images) {
                val img = ImageView(c).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                strip.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, c.dp(220)).apply { marginEnd = c.dp(8) })
                img.show(url)
            }
            val scroll = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false }
            scroll.addView(strip)
            column.add(scroll, bottom = c.dp(10))
        }
        column.add(BlockViews.title(c, b.name))
        column.add(c.text(18f, R.color.o_primary, bold = true).apply { text = StoreScreen.price(b) }, top = c.dp(6))
        if (!b.inStock) column.add(c.text(14f, R.color.o_error).apply { text = "אזל מהמלאי כרגע" }, top = c.dp(4))

        if (b.purchasable && b.inStock) {
            column.add(c.button("לרכישה באתר הישיבה", R.drawable.ic_open_in_new, filled = true) {
                act.confirmBrowser(
                    Site.buyLink(b),
                    "הספר יתווסף לסל הקניות באתר הישיבה, והתשלום ייעשה שם, בדף התשלום המאובטח של האתר, בדפדפן של הטלפון ולא בתוך היישומון.",
                )
            }, top = c.dp(14))
            column.add(c.text(13f, R.color.o_on_surface_variant).apply {
                text = "היישומון לא מקבל ולא שומר פרטי תשלום. הרכישה כולה נעשית באתר הישיבה."
            }, top = c.dp(6))
        }

        val blocks = Blocks.parse(b.html)
        if (blocks.isNotEmpty()) {
            column.add(c.text(17f, bold = true).apply { text = "על הספר" }, top = c.dp(22))
            BlockViews.render(act, column, blocks, b.name, b.link)
        }
    }
}

/** A PDF from the site (a source sheet, a lesson summary), shown page by page inside the app. */
class PdfScreen(act: MainActivity, private val url: String, private val name: String) : Screen(act) {
    override val title: String get() = name.ifBlank { "קובץ" }
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var file: File? = null
    private lateinit var list: RecyclerView
    private val pages = PageAdapter()

    override fun actions(): List<TopAction> = listOf(TopAction(R.drawable.ic_share, "שיתוף") {
        val f = file ?: return@TopAction
        runCatching {
            val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", f)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("application/pdf")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(android.content.Intent.createChooser(send, "שיתוף הקובץ"))
        }
    })

    override fun build(): View {
        val frame = android.widget.FrameLayout(act)
        list = RecyclerView(act).apply {
            layoutManager = LinearLayoutManager(act)
            adapter = pages
            setBackgroundColor(act.color(R.color.o_surface_container_high))
        }
        frame.addView(list)
        val loading = LoadingRow.create(frame).also { LoadingRow.bind(it) }
        frame.addView(loading)
        io({
            val dir = File(act.cacheDir, "pdf").apply { mkdirs() }
            val f = File(dir, "${url.hashCode().toUInt()}.pdf")
            if (!f.exists() || f.length() == 0L) {
                Net.get(url).use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("empty")
                    f.outputStream().use { out -> body.byteStream().copyTo(out) }
                }
            }
            f
        }) { r ->
            frame.removeView(loading)
            r.onSuccess { f ->
                file = f
                runCatching {
                    val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                    descriptor = d
                    renderer = PdfRenderer(d)
                    pages.notifyDataSetChanged()
                }.onFailure { showError("לא הצלחנו לפתוח את הקובץ.") }
            }.onFailure { showError(errorText(it)) }
        }
        return frame
    }

    private fun showError(text: String) {
        Toast.makeText(act, text, Toast.LENGTH_LONG).show()
    }

    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = renderer?.pageCount ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val img = ImageView(parent.context).apply {
                adjustViewBounds = true
                setBackgroundColor(Color.WHITE)
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(parent.context.dp(6), parent.context.dp(6), parent.context.dp(6), 0)
                }
            }
            return object : RecyclerView.ViewHolder(img) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val img = holder.itemView as ImageView
            img.setImageBitmap(null)
            val width = (list.width.takeIf { it > 0 } ?: act.resources.displayMetrics.widthPixels) * 3 / 2
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { renderPage(position, width) } ?: return@launch
                if (holder.bindingAdapterPosition == position) img.setImageBitmap(bmp)
            }
        }
    }

    @Synchronized
    private fun renderPage(index: Int, width: Int): Bitmap? = runCatching {
        val r = renderer ?: return null
        r.openPage(index).use { page ->
            val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bmp).drawColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }.getOrNull()

    override fun destroy() {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        renderer = null
        super.destroy()
    }
}

class SettingsScreen(act: MainActivity) : ListScreen(act) {
    override val title = "הגדרות"

    override fun load(force: Boolean) {
        doneRefreshing()
        render()
    }

    private fun choose(title: String, labels: List<String>, selected: Int, pick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(act)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { d, which ->
                d.dismiss()
                pick(which)
            }
            .show()
    }

    private fun render() {
        val rows = ArrayList<Row>()
        rows += HeaderRow("תצוגה")
        rows += EntryRow("מראה", Settings.themeLabel(act), icon = R.drawable.ic_dark_mode) {
            val idx = Settings.THEMES.indexOfFirst { it.first == Settings.theme(act) }
            choose("מראה", Settings.THEMES.map { it.second }, idx) { Settings.setTheme(act, Settings.THEMES[it].first) }
        }
        rows += EntryRow("גודל תצוגה", Settings.scaleLabel(act), icon = R.drawable.ic_format_size) {
            val idx = Settings.SCALES.indexOfFirst { kotlin.math.abs(it.first - Settings.scale(act)) < 0.001f }
            choose("גודל תצוגה", Settings.SCALES.map { it.second }, idx) {
                Settings.setScale(act, Settings.SCALES[it].first)
                act.recreate()
            }
        }
        rows += EntryRow("תמונות", if (Store.images()) "מוצגות" else "מוסתרות (חוסך גלישה)", icon = R.drawable.ic_smart_display) {
            Store.setImages(!Store.images())
            render()
        }
        rows += HeaderRow("השמעה")
        rows += EntryRow("סרטונים", if (Store.audioOnly()) "שמע בלבד (חוסך גלישה)" else "עם תמונה", icon = R.drawable.ic_graphic_eq) {
            Playback.setAudioOnly(act, !Store.audioOnly())
            render()
        }
        rows += EntryRow("מהירות השמעה", "${Store.speed()}×", icon = R.drawable.ic_forward_30) {
            val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
            choose("מהירות השמעה", speeds.map { "$it×" }, speeds.indexOfFirst { kotlin.math.abs(it - Store.speed()) < 0.01f }) {
                Playback.setSpeed(act, speeds[it])
                render()
            }
        }
        rows += HeaderRow("תוכן חדש")
        rows += EntryRow("התראות על שיעור, סרטון, סדרה או הסכת חדשים", if (Store.notifications()) "פעיל" else "כבוי", icon = R.drawable.ic_history) {
            Store.setNotifications(!Store.notifications())
            render()
        }
        rows += EntryRow("פתיחת קישורים של האתר ביישומון", "קישור ל־otniel.org ייפתח ישר כאן", icon = R.drawable.ic_link) { act.openLinkSettings() }
        rows += HeaderRow("על היישומון")
        val version = runCatching { act.packageManager.getPackageInfo(act.packageName, 0).versionName }.getOrNull() ?: ""
        rows += EntryRow("גרסה $version", "בדיקה אם יש גרסה חדשה", icon = R.drawable.ic_download) { checkUpdate() }
        rows += MessageRow(
            "יישומון לא רשמי שמציג את התכנים של אתר ישיבת עתניאל (otniel.org) ושל ערוץ היוטיוב שלה, ישר מהמקור. כל התוכן שייך לישיבה. אין ביישומון דפדפן: קישורים חיצוניים נפתחים רק אחרי אישור, בדפדפן של הטלפון.",
        )
        show(rows)
    }

    private fun checkUpdate() {
        scope.launch {
            val release = runCatching { Updater.newer(act, force = true) }.getOrNull()
            if (release == null) {
                Toast.makeText(act, "יש לך את הגרסה האחרונה", Toast.LENGTH_SHORT).show()
            } else {
                MaterialAlertDialogBuilder(act)
                    .setTitle("גרסה חדשה")
                    .setMessage("יש גרסה חדשה של היישומון. להוריד ולהתקין אותה עכשיו?")
                    .setPositiveButton("עדכן") { _, _ -> act.installUpdate(release) }
                    .setNegativeButton("לא עכשיו", null)
                    .show()
            }
        }
    }
}
