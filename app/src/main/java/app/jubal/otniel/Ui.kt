package app.jubal.otniel

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Locale

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.color(res: Int): Int = ContextCompat.getColor(this, res)

fun Context.isNight(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

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
        setStroke(dp(2), color(R.color.o_primary))
    }
    val states = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(fill, focused)))
        addState(intArrayOf(), fill)
    }
    val mask = GradientDrawable().apply {
        cornerRadius = radius
        setColor(0xFFFFFFFF.toInt())
    }
    return RippleDrawable(ColorStateList.valueOf(color(R.color.o_primary) and 0x33FFFFFF), states, mask)
}

fun View.clickable(radiusDp: Int = 12, fillRes: Int = 0, onClick: () -> Unit) {
    background = context.pressable(radiusDp, fillRes)
    isFocusable = true
    isClickable = true
    setOnClickListener { onClick() }
}

fun Context.text(sizeSp: Float, colorRes: Int = R.color.o_on_surface, bold: Boolean = false): TextView = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color(colorRes))
    if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
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

fun Context.button(label: String, icon: Int = 0, filled: Boolean = false, onClick: () -> Unit): MaterialButton {
    val style = if (filled) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle
    return MaterialButton(this, null, style).apply {
        text = label
        isAllCaps = false
        if (icon != 0) {
            setIconResource(icon)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        }
        setOnClickListener { onClick() }
    }
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

fun ImageView.show(url: String?) {
    if (url.isNullOrBlank() || !Store.images()) {
        visibility = View.GONE
        return
    }
    visibility = View.VISIBLE
    load(url) {
        crossfade(true)
        listener(onError = { _, _ -> visibility = View.GONE })
    }
}

/** Dates as the site writes them, with the Hebrew date first: "כ״א בכסלו תשפ״ז · 21.11.2026". */
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

// ---------------------------------------------------------------------- list rows

/** One row in a list screen. Rows build their own views; the adapter only recycles by [kind]. */
abstract class Row {
    abstract val kind: Int
    abstract fun create(parent: ViewGroup): View
    abstract fun bind(view: View)
    open val key: String get() = ""
}

class HeaderRow(private val text: String, private val action: String? = null, private val onAction: (() -> Unit)? = null) : Row() {
    override val kind = 1
    override fun create(parent: ViewGroup): View {
        val c = parent.context
        val row = c.horizontal()
        row.setPadding(c.dp(16), c.dp(18), c.dp(12), c.dp(6))
        val title = c.text(17f, bold = true).apply { id = R.id.row_title }
        row.add(title, 0, weight = 1f)
        val more = c.text(14f, R.color.o_primary, bold = true).apply {
            id = R.id.row_action
            setPadding(c.dp(10), c.dp(6), c.dp(10), c.dp(6))
        }
        row.add(more, ViewGroup.LayoutParams.WRAP_CONTENT)
        row.layoutParams = matchWrap()
        return row
    }

    override fun bind(view: View) {
        view.findViewById<TextView>(R.id.row_title).text = text
        val more = view.findViewById<TextView>(R.id.row_action)
        if (action != null && onAction != null) {
            more.visibility = View.VISIBLE
            more.text = action
            more.clickable(16) { onAction.invoke() }
        } else {
            more.visibility = View.GONE
        }
    }
}

/**
 * A lesson, video, series, podcast, book or menu entry. [image] is shown at the start when
 * pictures are on; [wide] makes it a 16:9 video picture, otherwise square.
 */
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
        row.gravity = Gravity.TOP
        row.setPadding(c.dp(12), c.dp(10), c.dp(12), c.dp(10))
        val lp = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(c.dp(6), c.dp(2), c.dp(6), c.dp(2))
        row.layoutParams = lp

        val pic = ImageView(c).apply {
            id = R.id.row_image
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, c.dp(8).toFloat())
                }
            }
            setBackgroundColor(c.color(R.color.o_surface_container_high))
        }
        row.addView(pic, LinearLayout.LayoutParams(c.dp(104), c.dp(58)).apply { marginEnd = c.dp(12) })

        val ic = ImageView(c).apply {
            id = R.id.row_icon
            imageTintList = ColorStateList.valueOf(c.color(R.color.o_primary))
        }
        row.addView(ic, LinearLayout.LayoutParams(c.dp(24), c.dp(24)).apply {
            marginEnd = c.dp(14)
            topMargin = c.dp(2)
        })

        val texts = c.vertical()
        texts.add(c.text(16f).apply { id = R.id.row_title; maxLines = 4; ellipsize = TextUtils.TruncateAt.END })
        texts.add(c.text(13.5f, R.color.o_on_surface_variant).apply { id = R.id.row_subtitle; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, top = c.dp(2))
        texts.add(c.text(12.5f, R.color.o_outline).apply { id = R.id.row_meta; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, top = c.dp(2))
        texts.add(
            LinearProgressIndicator(c).apply {
                id = R.id.row_progress
                max = 100
                trackCornerRadius = c.dp(2)
                trackThickness = c.dp(3)
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
        t.setTextColor(c.color(if (highlight) R.color.o_primary else R.color.o_on_surface))
        t.typeface = if (highlight) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
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
        } else {
            ic.visibility = View.GONE
        }
        view.clickable(12) { onClick() }
        if (onLong != null) view.setOnLongClickListener { onLong.invoke(); true } else view.setOnLongClickListener(null)
    }
}

class MessageRow(private val text: String, private val button: String? = null, private val onButton: (() -> Unit)? = null) : Row() {
    override val kind = 3
    override fun create(parent: ViewGroup): View {
        val c = parent.context
        val box = c.vertical()
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(c.dp(24), c.dp(28), c.dp(24), c.dp(28))
        box.add(c.text(15f, R.color.o_on_surface_variant).apply { id = R.id.row_title; gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
        box.add(c.button("", filled = false) {}.apply { id = R.id.row_action }, ViewGroup.LayoutParams.WRAP_CONTENT, top = c.dp(12))
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
        box.add(ProgressBar(c), c.dp(36), c.dp(36))
        box.layoutParams = matchWrap()
        return box
    }

    override fun bind(view: View) {}
}

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
            setPadding(c.dp(12), c.dp(4), c.dp(12), c.dp(4))
        }
        scroll.addView(line)
        return scroll
    }

    override fun bind(view: View) {
        val c = view.context
        val line = view.findViewById<LinearLayout>(R.id.row_chips)
        line.removeAllViews()
        options.forEachIndexed { i, label ->
            val chip = Chip(c).apply {
                text = label
                isCheckable = true
                isChecked = i == selected
                isFocusable = true
                setOnClickListener { onSelect(i) }
            }
            line.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = c.dp(8)
            })
        }
    }
}

class SearchRow(private val hint: String, private val initial: String, private val onSearch: (String) -> Unit) : Row() {
    override val kind = 6
    override fun create(parent: ViewGroup): View {
        val c = parent.context
        val box = c.horizontal()
        box.setPadding(c.dp(12), c.dp(8), c.dp(12), c.dp(4))
        val field = EditText(c).apply {
            id = R.id.row_search
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = GradientDrawable().apply {
                cornerRadius = c.dp(24).toFloat()
                setColor(c.color(R.color.o_surface_container_high))
            }
            setPadding(c.dp(16), c.dp(10), c.dp(16), c.dp(10))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = c.dp(8)
            compoundDrawableTintList = ColorStateList.valueOf(c.color(R.color.o_on_surface_variant))
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
        box.background = GradientDrawable().apply {
            cornerRadius = c.dp(16).toFloat()
            setColor(c.color(R.color.o_secondary_container))
        }
        box.setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(10))
        box.add(c.text(14.5f, R.color.o_on_secondary_container).apply { id = R.id.row_title })
        val buttons = c.horizontal()
        buttons.gravity = Gravity.END
        buttons.add(c.button("", filled = true) {}.apply { id = R.id.row_action }, ViewGroup.LayoutParams.WRAP_CONTENT)
        buttons.add(c.button("לא עכשיו") {}.apply { id = R.id.row_close }, ViewGroup.LayoutParams.WRAP_CONTENT)
        box.add(buttons, top = c.dp(6))
        val lp = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(c.dp(12), c.dp(10), c.dp(12), c.dp(6))
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
