package app.jubal.otniel

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Locale

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.color(res: Int): Int = ContextCompat.getColor(this, res)

fun Context.isNight(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** The site's two typefaces: a tall condensed one for titles, a plain sans for text (free look-alikes). */
object Fonts {
    private val cache = HashMap<Int, Typeface>()

    private fun get(context: Context, res: Int, fallback: Typeface): Typeface =
        cache[res] ?: (runCatching { ResourcesCompat.getFont(context, res) }.getOrNull() ?: fallback).also { cache[res] = it }

    fun body(context: Context, bold: Boolean = false): Typeface =
        get(context, if (bold) R.font.heebo_bold else R.font.heebo_regular, if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)

    fun display(context: Context, bold: Boolean = true): Typeface =
        get(context, if (bold) R.font.karantina_bold else R.font.karantina_regular, Typeface.DEFAULT_BOLD)
}

fun Context.rounded(radiusDp: Int, colorRes: Int): GradientDrawable = GradientDrawable().apply {
    cornerRadius = dp(radiusDp).toFloat()
    setColor(color(colorRes))
}

/** Rounded background with a touch ripple and a clear outline when chosen with the phone's keys. */
fun Context.pressable(radiusDp: Int = 12, fillRes: Int = 0): RippleDrawable {
    val radius = dp(radiusDp).toFloat()
    val fill = GradientDrawable().apply {
        cornerRadius = radius
        setColor(if (fillRes != 0) color(fillRes) else 0)
    }
    val focused = GradientDrawable().apply {
        cornerRadius = radius
        setColor(0)
        setStroke(dp(2), color(R.color.o_teal))
    }
    val states = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), LayerDrawable(arrayOf<Drawable>(fill, focused)))
        addState(intArrayOf(), fill)
    }
    val mask = GradientDrawable().apply {
        cornerRadius = radius
        setColor(0xFFFFFFFF.toInt())
    }
    return RippleDrawable(ColorStateList.valueOf(color(R.color.o_teal) and 0x33FFFFFF), states, mask)
}

fun View.clickable(radiusDp: Int = 12, fillRes: Int = 0, onClick: () -> Unit) {
    background = context.pressable(radiusDp, fillRes)
    isFocusable = true
    isClickable = true
    setOnClickListener { onClick() }
}

fun Context.text(sizeSp: Float, colorRes: Int = R.color.o_text, bold: Boolean = false): TextView = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color(colorRes))
    typeface = Fonts.body(this@text, bold)
    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
}

/** A title in the site's tall condensed letters. */
fun Context.display(sizeSp: Float, colorRes: Int = R.color.o_text, bold: Boolean = true): TextView = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color(colorRes))
    typeface = Fonts.display(this@display, bold)
    setLineSpacing(0f, 0.9f)
    includeFontPadding = false
    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
}

fun Context.vertical(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.horizontal(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun matchWrap() = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

fun LinearLayout.add(view: View, width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f, top: Int = 0, bottom: Int = 0): View {
    val lp = LinearLayout.LayoutParams(width, height, weight)
    lp.topMargin = top
    lp.bottomMargin = bottom
    addView(view, lp)
    return view
}

/**
 * The site's buttons: [filled] is the deep teal one with white letters ("לכל השיעורים"),
 * otherwise the light cyan one with dark letters ("חיפוש").
 */
fun Context.button(label: String, icon: Int = 0, filled: Boolean = false, onClick: () -> Unit): MaterialButton =
    MaterialButton(this).apply {
        text = label
        isAllCaps = false
        typeface = Fonts.body(this@button, bold = true)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        cornerRadius = dp(10)
        insetTop = 0
        insetBottom = 0
        minHeight = dp(44)
        val bg = if (filled) R.color.o_teal else R.color.o_cyan
        val fg = if (filled) R.color.o_card else R.color.o_on_primary_container
        backgroundTintList = ColorStateList.valueOf(color(bg))
        setTextColor(color(fg))
        if (icon != 0) {
            setIconResource(icon)
            iconTint = ColorStateList.valueOf(color(fg))
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconSize = dp(20)
        }
        elevation = 0f
        stateListAnimator = null
        setPadding(dp(18), 0, dp(18), 0)
        setOnClickListener { onClick() }
    }

/** Removes "web search", "translate" and the like from the text menu, so nothing in the app opens a browser. */
fun TextView.withoutWebActions() {
    val allowed = setOf(android.R.id.copy, android.R.id.selectAll, android.R.id.cut, android.R.id.paste, android.R.id.pasteAsPlainText)
    val callback = object : ActionMode.Callback {
        private fun strip(menu: Menu) {
            var guard = 0
            while (guard++ < 60) {
                val extra = (0 until menu.size()).map { menu.getItem(it) }.firstOrNull { it.itemId !in allowed } ?: break
                menu.removeItem(extra.itemId)
            }
        }
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { strip(menu); return true }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean { strip(menu); return true }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
        override fun onDestroyActionMode(mode: ActionMode) {}
    }
    customSelectionActionModeCallback = callback
    customInsertionActionModeCallback = callback
}

/** HTML text whose links go through the app ([open]) instead of a browser. */
fun TextView.setHtml(html: String, open: (String) -> Unit) {
    val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
    val sb = SpannableStringBuilder(spanned)
    for (span in sb.getSpans(0, sb.length, URLSpan::class.java)) {
        val start = sb.getSpanStart(span)
        val end = sb.getSpanEnd(span)
        val url = span.url
        sb.removeSpan(span)
        sb.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) = open(url)
        }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    // Drop the "object replacement" boxes left by images (pictures are shown separately)
    var i = sb.indexOf("￼")
    while (i >= 0) {
        sb.delete(i, i + 1)
        i = sb.indexOf("￼")
    }
    while (sb.isNotEmpty() && sb[sb.length - 1].isWhitespace()) sb.delete(sb.length - 1, sb.length)
    text = sb
    setLinkTextColor(context.color(R.color.o_link))
    movementMethod = android.text.method.LinkMovementMethod.getInstance()
}

fun ImageView.show(url: String?, always: Boolean = false) {
    if (url.isNullOrBlank() || (!always && !Store.images())) {
        visibility = View.GONE
        return
    }
    visibility = View.VISIBLE
    load(url) {
        crossfade(true)
        listener(onError = { _, _ -> visibility = View.GONE })
    }
}

/** Corners like the site's pictures. */
fun View.roundCorners(radiusDp: Int) {
    val r = context.dp(radiusDp).toFloat()
    clipToOutline = true
    outlineProvider = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, r)
        }
    }
}

/** A filled circle with a white icon, like the site's play / document marks. */
fun Context.circleIcon(icon: Int, sizeDp: Int = 34, fillRes: Int = R.color.o_cyan): ImageView = ImageView(this).apply {
    setImageResource(icon)
    imageTintList = ColorStateList.valueOf(Color.WHITE)
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color(fillRes))
    }
    val pad = dp(sizeDp) / 5
    setPadding(pad, pad, pad, pad)
}

/** Dates as the site writes them, with the Hebrew date: "כ״א בכסלו תשפ״ז · 21.11.2026". */
object Dates {
    private fun parse(iso: String): java.util.Date? = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Jerusalem")
        }.parse(iso)
    }.getOrNull()

    fun hebrew(millis: Long, withYear: Boolean = true): String {
        if (millis <= 0) return ""
        return runCatching {
            val cal = android.icu.util.HebrewCalendar(android.icu.util.TimeZone.getTimeZone("Asia/Jerusalem"))
            cal.timeInMillis = millis
            val year = cal.get(android.icu.util.Calendar.EXTENDED_YEAR)
            val month = cal.get(android.icu.util.Calendar.MONTH)
            val day = cal.get(android.icu.util.Calendar.DAY_OF_MONTH)
            val leap = ((7 * year + 1) % 19) < 7
            Hebrew.date(day, month, year, leap, withYear)
        }.getOrDefault("")
    }

    fun gregorian(millis: Long): String =
        if (millis <= 0) "" else SimpleDateFormat("dd.MM.yyyy", Locale.US).format(java.util.Date(millis))

    fun millis(iso: String): Long = parse(iso)?.time ?: 0

    /** As on the site's cards: 19.11.2026 */
    fun site(iso: String): String = gregorian(millis(iso))

    fun short(iso: String): String = hebrew(millis(iso), withYear = true)

    fun full(iso: String): String {
        val m = millis(iso)
        if (m <= 0) return ""
        return "${hebrew(m)} · ${gregorian(m)}"
    }

    fun duration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 && m > 0 -> "$h שע׳ $m דק׳"
            h > 0 -> "$h שע׳"
            else -> "${maxOf(1, m)} דק׳"
        }
    }
}

// ---------------------------------------------------------------------- list rows (the site's cards)

/** One row in a list screen. Rows build their own views; the adapter only recycles by [kind]. */
abstract class Row {
    abstract val kind: Int
    abstract fun create(parent: ViewGroup): View
    abstract fun bind(view: View)
    open val key: String get() = ""
}

private fun Context.cardFrame(): LinearLayout = vertical().apply {
    val lp = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.setMargins(dp(14), dp(7), dp(14), dp(7))
    layoutParams = lp
}

/** The big heading of the site's inner pages ("שיעורים", "פודקאסטים"...) over a faded picture. */
class PageTitleRow(private val title: String, private val subtitle: String = "") : Row() {
    override val kind = 10
    override fun create(parent: ViewGroup): View {
        val c = parent.context
        val box = FrameLayout(c)
        box.layoutParams = matchWrap()
        val img = ImageView(c).apply {
            id = R.id.row_image
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.3f
        }
        box.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val page = c.color(R.color.o_page)
        val fade = View(c).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf((page and 0x00FFFFFF) or 0x55000000, page),
            )
        }
        box.addView(fade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val col = c.vertical()
        col.setPadding(c.dp(20), c.dp(54), c.dp(20), c.dp(14))
        col.add(c.display(54f).apply { id = R.id.row_title })
        col.add(c.text(15f, R.color.o_on_surface_variant).apply { id = R.id.row_subtitle }, top = c.dp(6))
        box.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val line = View(c).apply { setBackgroundColor(c.color(R.color.o_muted)) }
        box.addView(line, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(1), Gravity.BOTTOM))
        return box
    }

    override fun bind(view: View) {
        val t = view.findViewById<TextView>(R.id.row_title)
        t.text = title
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (title.length > 16) 42f else 54f)
        view.findViewById<TextView>(R.id.row_subtitle).apply {
            text = subtitle
            visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<ImageView>(R.id.row_image).show(Site.HERO_IMAGE)
    }
}

/** A section heading inside a page, in the site's display letters. */
class HeaderRow(private val text: String, private val action: String? = null, private val onAction: (() -> Unit)? = null) : Row() {
    override val kind = 1
    override fun create(parent: ViewGroup): View {
        val c = parent.context
        val row = c.horizontal()
        row.gravity = Gravity.BOTTOM
        row.setPadding(c.dp(18), c.dp(24), c.dp(14), c.dp(6))
        row.add(c.display(36f).apply { id = R.id.row_title }, 0, weight = 1f)
        row.add(c.text(14.5f, R.color.o_teal, bold = true).apply {
            id = R.id.row_action
            setPadding(c.dp(10), c.dp(8), c.dp(10), c.dp(8))
        }, ViewGroup.LayoutParams.WRAP_CONTENT)
        row.layoutParams = matchWrap()
        return row
    }

    override fun bind(view: View) {
        view.findViewById<TextView>(R.id.row_title).text = text
        val more = view.findViewById<TextView>(R.id.row_action)
        if (action != null && onAction != null) {
            more.visibility = View.VISIBLE
            more.text = "$action ←"
            more.clickable(10) { onAction.invoke() }
        } else {
            more.visibility = View.GONE
        }
    }
}

/** A short explanation and a button, like the site's home page sections (פודקאסטים, סדרות שיעורים). */
class IntroRow(private val title: String, private val text: String, private val button: String, private val onButton: () -> Unit) : Row() {
    override val kind = 11
    override fun create(parent: ViewGroup): View {
        val c = parent.context
        val col = c.vertical()
        col.setPadding(c.dp(18), c.dp(26), c.dp(18), c.dp(8))
        col.add(c.display(44f, R.color.o_teal).apply { id = R.id.row_title })
        col.add(c.text(16.5f).apply { id = R.id.row_subtitle; setLineSpacing(0f, 1.2f) }, top = c.dp(8))
        col.add(c.button("", filled = true) {}.apply { id = R.id.row_action }, ViewGroup.LayoutParams.WRAP_CONTENT, top = c.dp(12))
        col.layoutParams = matchWrap()
        return col
    }

    override fun bind(view: View) {
        view.findViewById<TextView>(R.id.row_title).text = title
        view.findViewById<TextView>(R.id.row_subtitle).text = text
        view.findViewById<MaterialButton>(R.id.row_action).apply {
            this.text = "$button ←"
            setOnClickListener { onButton() }
        }
    }
}

/** One button on its own line (teal like "לכל השיעורים", or the light cyan one). */
class PillRow(private val label: String, private val filled: Boolean = true, private val onClick: () -> Unit) : Row() {
    override val kind = 12
    override fun create(parent: ViewGroup): View {
        val c = parent.context
        val box = c.horizontal()
        box.gravity = Gravity.CENTER
        box.setPadding(c.dp(16), c.dp(10), c.dp(16), c.dp(10))
        box.add(c.button("", filled = true) {}.apply { id = R.id.row_action }, ViewGroup.LayoutParams.WRAP_CONTENT)
        box.layoutParams = matchWrap()
        return box
    }

    override fun bind(view: View) {
        view.findViewById<MaterialButton>(R.id.row_action).apply {
            text = label
            val bg = if (filled) R.color.o_teal else R.color.o_cyan
            val fg = if (filled) R.color.o_card else R.color.o_on_primary_container
            backgroundTintList = ColorStateList.valueOf(context.color(bg))
            setTextColor(context.color(fg))
            setOnClickListener { onClick() }
        }
    }
}

/**
 * The site's lesson card: a round mark (play for a video, a page for text), the title in
 * display letters, and "rabbi | date" with an arrow.
 */
class LessonCardRow(
    private val title: String,
    private val by: String,
    private val date: String,
    private val icon: Int = R.drawable.ic_play_arrow_fill,
    private val progress: Int = -1,
    override val key: String = "",
    private val onClick: () -> Unit,
) : Row() {
    override val kind = 13

    override fun create(parent: ViewGroup): View {
        val c = parent.context
        val card = c.cardFrame()
        card.setPadding(c.dp(18), c.dp(14), c.dp(18), c.dp(12))
        val top = c.horizontal()
        top.add(c.circleIcon(R.drawable.ic_play_arrow_fill).apply { id = R.id.row_icon }, c.dp(34), c.dp(34))
        card.add(top)
        card.add(c.display(27f).apply {
            id = R.id.row_title
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }, top = c.dp(8))
        card.add(LinearProgressIndicator(c).apply {
            id = R.id.row_progress
            max = 100
            trackCornerRadius = c.dp(2)
            trackThickness = c.dp(3)
            setIndicatorColor(c.color(R.color.o_teal))
            trackColor = c.color(R.color.o_soft)
        }, top = c.dp(8))
        val bottom = c.horizontal()
        bottom.add(c.text(13.5f, bold = true).apply { id = R.id.row_subtitle; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, ViewGroup.LayoutParams.WRAP_CONTENT)
        val divider = View(c).apply { id = R.id.row_close; setBackgroundColor(c.color(R.color.o_line)) }
        bottom.addView(divider, LinearLayout.LayoutParams(c.dp(1), c.dp(20)).apply { setMargins(c.dp(12), 0, c.dp(12), 0) })
        bottom.add(c.text(12.5f, bold = true).apply { id = R.id.row_meta; maxLines = 1 }, ViewGroup.LayoutParams.WRAP_CONTENT)
        bottom.add(View(c), 0, c.dp(1), weight = 1f)
        bottom.add(ImageView(c).apply {
            setImageResource(R.drawable.ic_arrow_left)
            imageTintList = ColorStateList.valueOf(c.color(R.color.o_text))
        }, c.dp(30), c.dp(30))
        card.add(bottom, top = c.dp(10))
        return card
    }

    override fun bind(view: View) {
        view.findViewById<ImageView>(R.id.row_icon).setImageResource(icon)
        view.findViewById<TextView>(R.id.row_title).text = title
        view.findViewById<TextView>(R.id.row_subtitle).apply {
            text = by
            visibility = if (by.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<View>(R.id.row_close).visibility = if (by.isBlank() || date.isBlank()) View.GONE else View.VISIBLE
        view.findViewById<TextView>(R.id.row_meta).text = date
        view.findViewById<LinearProgressIndicator>(R.id.row_progress).apply {
            visibility = if (progress in 1..99) View.VISIBLE else View.GONE
            if (progress in 1..99) setProgressCompat(progress, false)
        }
        view.clickable(18, R.color.o_card) { onClick() }
    }
}

/** A picture card with the title under it, like the site's video series and the channel's videos. */
class PictureCardRow(
    private val title: String,
    private val meta: String = "",
    private val image: String?,
    private val play: Boolean = true,
    private val progress: Int = -1,
    override val key: String = "",
    private val onClick: () -> Unit,
) : Row() {
    override val kind = 14

    override fun create(parent: ViewGroup): View {
        val c = parent.context
        val card = c.cardFrame()
        card.setPadding(0, 0, 0, c.dp(10))
        val frame = WideFrame(c).apply {
            id = R.id.row_chips
            setBackgroundColor(c.color(R.color.o_soft))
            roundCorners(16)
        }
        frame.addView(ImageView(c).apply {
            id = R.id.row_image
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(
            c.circleIcon(R.drawable.ic_play_arrow_fill, 46).apply { id = R.id.row_icon },
            FrameLayout.LayoutParams(c.dp(46), c.dp(46), Gravity.BOTTOM or Gravity.START).apply { setMargins(c.dp(12), c.dp(12), c.dp(12), c.dp(12)) },
        )
        card.add(frame)
        card.add(LinearProgressIndicator(c).apply {
            id = R.id.row_progress
            max = 100
            trackThickness = c.dp(3)
            setIndicatorColor(c.color(R.color.o_teal))
            trackColor = c.color(R.color.o_soft)
        })
        card.add(c.text(17f, R.color.o_teal, bold = true).apply {
            id = R.id.row_title
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(c.dp(10), 0, c.dp(10), 0)
        }, top = c.dp(8))
        card.add(c.text(13f, R.color.o_on_surface_variant).apply {
            id = R.id.row_meta
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }, top = c.dp(2))
        return card
    }

    override fun bind(view: View) {
        val pic = view.findViewById<ImageView>(R.id.row_image)
        val frame = view.findViewById<View>(R.id.row_chips)
        if (!image.isNullOrBlank() && Store.images()) {
            frame.visibility = View.VISIBLE
            pic.show(image)
            pic.visibility = View.VISIBLE
        } else {
            frame.visibility = View.GONE
        }
        view.findViewById<View>(R.id.row_icon).visibility = if (play) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.row_title).text = title
        view.findViewById<TextView>(R.id.row_meta).apply {
            text = meta
            visibility = if (meta.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<LinearProgressIndicator>(R.id.row_progress).apply {
            visibility = if (progress in 1..99) View.VISIBLE else View.GONE
            if (progress in 1..99) setProgressCompat(progress, false)
        }
        view.clickable(16) { onClick() }
    }
}

/** The site's podcast card: a square cover in the middle, a large title and the presenter. */
class CoverCardRow(
    private val title: String,
    private val subtitle: String,
    private val image: String?,
    override val key: String = "",
    private val onClick: () -> Unit,
) : Row() {
    override val kind = 15

    override fun create(parent: ViewGroup): View {
        val c = parent.context
        val card = c.cardFrame()
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.setPadding(c.dp(18), c.dp(22), c.dp(18), c.dp(20))
        val size = (c.resources.displayMetrics.widthPixels * 0.55f).toInt().coerceAtMost(c.dp(260))
        card.add(ImageView(c).apply {
            id = R.id.row_image
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(c.color(R.color.o_soft))
            roundCorners(12)
        }, size, size)
        card.add(c.display(36f, R.color.o_teal).apply {
            id = R.id.row_title
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }, top = c.dp(14))
        card.add(c.text(16f).apply {
            id = R.id.row_subtitle
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }, top = c.dp(4))
        return card
    }

    override fun bind(view: View) {
        val pic = view.findViewById<ImageView>(R.id.row_image)
        if (!image.isNullOrBlank() && Store.images()) pic.show(image) else pic.visibility = View.GONE
        view.findViewById<TextView>(R.id.row_title).text = title
        view.findViewById<TextView>(R.id.row_subtitle).apply {
            text = subtitle
            visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        }
        view.clickable(18, R.color.o_card) { onClick() }
    }
}

/** A plain white card entry (rabbis, settings, Q&A, books). */
class EntryRow(
    private val title: String,
    private val subtitle: String = "",
    private val meta: String = "",
    private val icon: Int = 0,
    private val image: String? = null,
    private val wide: Boolean = true,
    private val progress: Int = -1,
    private val highlight: Boolean = false,
    override val key: String = "",
    private val onLong: (() -> Unit)? = null,
    private val onClick: () -> Unit,
) : Row() {
    override val kind = 2

    override fun create(parent: ViewGroup): View {
        val c = parent.context
        val row = c.horizontal()
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(14))
        val lp = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(c.dp(14), c.dp(5), c.dp(14), c.dp(5))
        row.layoutParams = lp

        val pic = ImageView(c).apply {
            id = R.id.row_image
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundCorners(10)
            setBackgroundColor(c.color(R.color.o_soft))
        }
        row.addView(pic, LinearLayout.LayoutParams(c.dp(104), c.dp(58)).apply { marginEnd = c.dp(12) })
        val ic = c.circleIcon(R.drawable.ic_play_arrow_fill, 34).apply { id = R.id.row_icon }
        row.addView(ic, LinearLayout.LayoutParams(c.dp(34), c.dp(34)).apply { marginEnd = c.dp(12) })

        val texts = c.vertical()
        texts.add(c.text(16f, bold = true).apply { id = R.id.row_title; maxLines = 4; ellipsize = TextUtils.TruncateAt.END })
        texts.add(c.text(14f, R.color.o_on_surface_variant).apply { id = R.id.row_subtitle; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, top = c.dp(2))
        texts.add(c.text(12.5f, R.color.o_outline, bold = true).apply { id = R.id.row_meta; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, top = c.dp(2))
        texts.add(
            LinearProgressIndicator(c).apply {
                id = R.id.row_progress
                max = 100
                trackCornerRadius = c.dp(2)
                trackThickness = c.dp(3)
                setIndicatorColor(c.color(R.color.o_teal))
                trackColor = c.color(R.color.o_soft)
            },
            top = c.dp(6),
        )
        row.add(texts, 0, weight = 1f)
        return row
    }

    override fun bind(view: View) {
        val c = view.context
        val t = view.findViewById<TextView>(R.id.row_title)
        t.text = title
        t.setTextColor(c.color(if (highlight) R.color.o_teal else R.color.o_text))
        view.findViewById<TextView>(R.id.row_subtitle).apply {
            text = subtitle
            visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.row_meta).apply {
            text = meta
            visibility = if (meta.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<LinearProgressIndicator>(R.id.row_progress).apply {
            visibility = if (progress in 1..99) View.VISIBLE else View.GONE
            if (progress in 1..99) setProgressCompat(progress, false)
        }
        val pic = view.findViewById<ImageView>(R.id.row_image)
        val showPic = !image.isNullOrBlank() && Store.images()
        if (showPic) {
            val lp = pic.layoutParams
            lp.width = c.dp(if (wide) 104 else 64)
            lp.height = c.dp(if (wide) 58 else 64)
            pic.layoutParams = lp
            pic.show(image)
        } else {
            pic.visibility = View.GONE
        }
        val ic = view.findViewById<ImageView>(R.id.row_icon)
        if (icon != 0 && !showPic) {
            ic.visibility = View.VISIBLE
            ic.setImageResource(icon)
            ic.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c.color(if (highlight) R.color.o_teal else R.color.o_cyan))
            }
        } else {
            ic.visibility = View.GONE
        }
        view.clickable(16, R.color.o_card) { onClick() }
        if (onLong != null) view.setOnLongClickListener { onLong.invoke(); true } else view.setOnLongClickListener(null)
    }
}

class MessageRow(private val text: String, private val button: String? = null, private val onButton: (() -> Unit)? = null) : Row() {
    override val kind = 3
    override fun create(parent: ViewGroup): View {
        val c = parent.context
        val box = c.vertical()
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(c.dp(24), c.dp(22), c.dp(24), c.dp(22))
        box.add(c.text(15.5f, R.color.o_on_surface_variant).apply { id = R.id.row_title; gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
        box.add(c.button("", filled = true) {}.apply { id = R.id.row_action }, ViewGroup.LayoutParams.WRAP_CONTENT, top = c.dp(12))
        box.layoutParams = matchWrap()
        return box
    }

    override fun bind(view: View) {
        view.findViewById<TextView>(R.id.row_title).text = text
        val b = view.findViewById<MaterialButton>(R.id.row_action)
        if (button != null && onButton != null) {
            b.visibility = View.VISIBLE
            b.text = button
            b.setOnClickListener { onButton.invoke() }
        } else {
            b.visibility = View.GONE
        }
    }
}

object LoadingRow : Row() {
    override val kind = 4
    override fun create(parent: ViewGroup): View {
        val c = parent.context
        val box = c.vertical()
        box.gravity = Gravity.CENTER
        box.setPadding(0, c.dp(24), 0, c.dp(24))
        box.add(ProgressBar(c).apply { indeterminateTintList = ColorStateList.valueOf(c.color(R.color.o_teal)) }, c.dp(36), c.dp(36))
        box.layoutParams = matchWrap()
        return box
    }

    override fun bind(view: View) {}
}

/** White rounded pills to filter by (categories), the chosen one in cyan. */
class ChipsRow(private val options: List<String>, private val selected: Int, private val onSelect: (Int) -> Unit) : Row() {
    override val kind = 5
    override fun create(parent: ViewGroup): View {
        val c = parent.context
        val scroll = HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = matchWrap()
        }
        val line = c.horizontal().apply {
            id = R.id.row_chips
            setPadding(c.dp(14), c.dp(8), c.dp(14), c.dp(8))
        }
        scroll.addView(line)
        return scroll
    }

    override fun bind(view: View) {
        val c = view.context
        val line = view.findViewById<LinearLayout>(R.id.row_chips)
        line.removeAllViews()
        options.forEachIndexed { i, label ->
            val on = i == selected
            val chip = c.text(14.5f, R.color.o_text, bold = on).apply {
                text = label
                setPadding(c.dp(16), c.dp(9), c.dp(16), c.dp(9))
            }
            chip.clickable(20, if (on) R.color.o_cyan else R.color.o_card) { onSelect(i) }
            line.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = c.dp(8)
            })
        }
    }
}

/** The site's topic circles (תנ"ך, הלכה, מחשבה...). */
class CirclesRow(private val options: List<String>, private val selected: Int, private val onSelect: (Int) -> Unit) : Row() {
    override val kind = 16
    override fun create(parent: ViewGroup): View {
        val c = parent.context
        val scroll = HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = matchWrap()
        }
        val line = c.horizontal().apply {
            id = R.id.row_chips
            setPadding(c.dp(12), c.dp(14), c.dp(12), c.dp(10))
        }
        scroll.addView(line)
        return scroll
    }

    override fun bind(view: View) {
        val c = view.context
        val line = view.findViewById<LinearLayout>(R.id.row_chips)
        line.removeAllViews()
        options.forEachIndexed { i, label ->
            val on = i == selected
            val circle = c.text(13.5f, if (on) R.color.o_teal else R.color.o_text, bold = true).apply {
                text = label
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                maxLines = 3
                setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8))
            }
            circle.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c.color(R.color.o_card))
                if (on) setStroke(c.dp(3), c.color(R.color.o_teal))
            }
            circle.isFocusable = true
            circle.isClickable = true
            circle.foreground = c.pressable(48)
            circle.setOnClickListener { onSelect(i) }
            line.addView(circle, LinearLayout.LayoutParams(c.dp(92), c.dp(92)).apply { marginEnd = c.dp(10) })
        }
    }
}

class SearchRow(private val hint: String, private val initial: String, private val onSearch: (String) -> Unit) : Row() {
    override val kind = 6
    override fun create(parent: ViewGroup): View {
        val c = parent.context
        val box = c.horizontal()
        box.setPadding(c.dp(14), c.dp(10), c.dp(14), c.dp(6))
        val field = EditText(c).apply {
            id = R.id.row_search
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Fonts.body(c)
            setTextColor(c.color(R.color.o_text))
            setHintTextColor(c.color(R.color.o_on_surface_variant))
            background = c.rounded(12, R.color.o_card)
            setPadding(c.dp(18), c.dp(13), c.dp(18), c.dp(13))
            withoutWebActions()
        }
        box.add(field, 0, weight = 1f)
        box.layoutParams = matchWrap()
        return box
    }

    override fun bind(view: View) {
        val field = view.findViewById<EditText>(R.id.row_search)
        field.hint = hint
        if (field.text.toString() != initial && !field.hasFocus()) field.setText(initial)
        field.setOnEditorActionListener { v, actionId, event ->
            val enter = event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                onSearch(v.text.toString().trim())
                val imm = v.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else {
                false
            }
        }
    }
}

/** A highlighted card with a short explanation and a button (tips, updates). */
class NoticeRow(private val text: String, private val button: String, private val onButton: () -> Unit, private val onClose: (() -> Unit)? = null) : Row() {
    override val kind = 7
    override fun create(parent: ViewGroup): View {
        val c = parent.context
        val box = c.vertical()
        box.background = c.rounded(18, R.color.o_soft)
        box.setPadding(c.dp(18), c.dp(16), c.dp(18), c.dp(12))
        box.add(c.text(15f).apply { id = R.id.row_title; setLineSpacing(0f, 1.15f) })
        val buttons = c.horizontal()
        buttons.add(c.button("", filled = true) {}.apply { id = R.id.row_action }, ViewGroup.LayoutParams.WRAP_CONTENT)
        buttons.add(c.button("לא עכשיו") {}.apply { id = R.id.row_close }, ViewGroup.LayoutParams.WRAP_CONTENT)
        (buttons.getChildAt(0).layoutParams as LinearLayout.LayoutParams).marginEnd = c.dp(8)
        box.add(buttons, top = c.dp(10))
        val lp = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(c.dp(14), c.dp(12), c.dp(14), c.dp(6))
        box.layoutParams = lp
        return box
    }

    override fun bind(view: View) {
        view.findViewById<TextView>(R.id.row_title).text = text
        view.findViewById<MaterialButton>(R.id.row_action).apply {
            this.text = button
            setOnClickListener { onButton() }
        }
        view.findViewById<MaterialButton>(R.id.row_close).apply {
            visibility = if (onClose == null) View.GONE else View.VISIBLE
            setOnClickListener { onClose?.invoke() }
        }
    }
}

/** A row made of any view (the home page's opening picture). */
class ViewRow(override val kind: Int, private val make: (ViewGroup) -> View, private val fill: (View) -> Unit = {}) : Row() {
    override fun create(parent: ViewGroup): View = make(parent)
    override fun bind(view: View) = fill(view)
}

class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var rows: List<Row> = emptyList()
        private set
    private val samples = HashMap<Int, Row>()

    fun submit(list: List<Row>) {
        rows = list
        list.forEach { samples.putIfAbsent(it.kind, it) }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = rows[position].kind

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val row = samples[viewType] ?: rows.first { it.kind == viewType }
        return object : RecyclerView.ViewHolder(row.create(parent)) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = rows[position].bind(holder.itemView)

    override fun getItemCount(): Int = rows.size
}
