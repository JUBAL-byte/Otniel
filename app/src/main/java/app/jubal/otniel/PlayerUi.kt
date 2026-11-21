package app.jubal.otniel

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** A frame that is always 16:9 (but never taller than most of the screen). */
class WideFrame(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        val h = minOf(w * 9 / 16, maxH)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
    }
}

@OptIn(UnstableApi::class)
fun newPlayerView(context: Context): PlayerView = PlayerView(context).apply {
    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
    controllerShowTimeoutMs = 3500
    setShowSubtitleButton(false)
    setShowShuffleButton(false)
    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    defaultArtwork = ContextCompat.getDrawable(context, R.drawable.ic_launcher)
    setShutterBackgroundColor(Color.BLACK)
    isFocusable = true
}

/**
 * The video area of a lesson: its picture with a play button, and the playing video
 * (with the player's own controls) once it is playing.
 */
@OptIn(UnstableApi::class)
class PlayerPanel(private val act: MainActivity, val playable: Playable, poster: String?) {
    val root = WideFrame(act).apply { setBackgroundColor(Color.BLACK) }
    private val playerView = newPlayerView(act)
    private val posterView = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val playButton = FrameLayout(act)

    init {
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(posterView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (!poster.isNullOrBlank() && Store.images()) posterView.show(poster) else posterView.visibility = View.GONE

        val circle = ImageView(act).apply {
            setImageResource(R.drawable.ic_play_arrow_fill)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC1F5C57.toInt())
            }
            setPadding(act.dp(14), act.dp(14), act.dp(14), act.dp(14))
        }
        playButton.addView(circle, FrameLayout.LayoutParams(act.dp(68), act.dp(68), Gravity.CENTER))
        playButton.contentDescription = "צפייה בשיעור"
        playButton.isFocusable = true
        playButton.setOnClickListener { play() }
        root.addView(playButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val full = ImageButton(act).apply {
            setImageResource(R.drawable.ic_fullscreen)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setBackgroundColor(0x55000000)
            contentDescription = "מסך מלא"
            setOnClickListener {
                if (!Playback.isCurrent(playable.id)) play()
                act.enterFullscreen()
            }
        }
        root.addView(full, FrameLayout.LayoutParams(act.dp(40), act.dp(40), Gravity.TOP or Gravity.START).apply {
            setMargins(act.dp(6), act.dp(6), act.dp(6), act.dp(6))
        })
        refresh()
    }

    fun play(audioOnly: Boolean = false) {
        Playback.play(act, listOf(playable.copy(video = !audioOnly)))
        Playback.with(act) { refresh() }
    }

    fun refresh() {
        if (Playback.isCurrent(playable.id)) {
            posterView.visibility = View.GONE
            playButton.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            act.attachPlayer(playerView)
        } else {
            act.detachPlayer(playerView)
            playButton.visibility = View.VISIBLE
            if (posterView.drawable != null) posterView.visibility = View.VISIBLE
        }
        act.updateMini()
    }

    fun release() = act.detachPlayer(playerView)
}

/** "Now playing": the video (or the sound with the controls), what it is, and what comes next. */
@OptIn(UnstableApi::class)
class PlayerScreen(act: MainActivity) : Screen(act), PlayerAware {
    override val title = "מתנגן עכשיו"
    private val playerView = newPlayerView(act).apply {
        setShowNextButton(true)
        setShowPreviousButton(true)
    }
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var audioButton: MaterialButton
    private lateinit var speedButton: MaterialButton
    private lateinit var sourceButton: MaterialButton
    private lateinit var queueHeader: TextView
    private val queue = RowAdapter()

    override fun actions(): List<TopAction> = listOf(
        TopAction(R.drawable.ic_share, "שיתוף") {
            val p = Playback.current() ?: return@TopAction
            val link = when {
                p.page.startsWith("http") && Links.isSite(p.page) -> p.page
                p.isYouTube -> YouTube.watchUrl(p.videoId)
                else -> p.uri
            }
            Outside.share(act, p.title, link)
        },
    )

    override fun showsPlayerFor(mediaId: String): Boolean = true

    override fun build(): View {
        val c = act
        val column = c.vertical()
        val frame = WideFrame(c).apply { setBackgroundColor(Color.BLACK) }
        frame.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val full = ImageButton(c).apply {
            setImageResource(R.drawable.ic_fullscreen)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setBackgroundColor(0x55000000)
            contentDescription = "מסך מלא"
            setOnClickListener { act.enterFullscreen() }
        }
        frame.addView(full, FrameLayout.LayoutParams(c.dp(40), c.dp(40), Gravity.TOP or Gravity.START).apply {
            setMargins(c.dp(6), c.dp(6), c.dp(6), c.dp(6))
        })
        column.add(frame)

        val info = c.vertical()
        info.setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(4))
        titleText = c.text(18f, bold = true)
        subtitleText = c.text(14f, R.color.o_on_surface_variant)
        info.add(titleText)
        info.add(subtitleText, top = c.dp(2))
        val buttons = c.horizontal()
        audioButton = c.button("שמע בלבד", R.drawable.ic_graphic_eq) {
            Playback.setAudioOnly(act, !Store.audioOnly())
            render()
        }
        speedButton = c.button("1×") { chooseSpeed() }
        sourceButton = c.button("לדף השיעור", R.drawable.ic_lesson) {
            val page = Playback.current()?.page ?: return@button
            if (page.isNotBlank()) act.open(page)
        }
        val scroll = android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false }
        buttons.add(audioButton, ViewGroup.LayoutParams.WRAP_CONTENT)
        buttons.add(speedButton, ViewGroup.LayoutParams.WRAP_CONTENT)
        buttons.add(sourceButton, ViewGroup.LayoutParams.WRAP_CONTENT)
        for (i in 0 until buttons.childCount) {
            (buttons.getChildAt(i).layoutParams as LinearLayout.LayoutParams).marginEnd = c.dp(8)
        }
        scroll.addView(buttons)
        info.add(scroll, top = c.dp(8))
        queueHeader = c.text(15f, bold = true).apply { setPadding(0, c.dp(12), 0, 0) }
        info.add(queueHeader)
        column.add(info)

        val list = RecyclerView(c).apply {
            layoutManager = LinearLayoutManager(c)
            adapter = queue
            itemAnimator = null
            clipToPadding = false
            setPadding(0, 0, 0, c.dp(16))
        }
        column.add(list, height = 0, weight = 1f)
        return column
    }

    override fun onShow() {
        act.attachPlayer(playerView)
        render()
    }

    override fun onMediaChanged() = render()

    private fun render() {
        if (!isBuilt) return
        val c = Playback.controller
        val p = Playback.current()
        titleText.text = p?.title ?: "לא מתנגן כלום כרגע"
        subtitleText.text = p?.subtitle ?: ""
        subtitleText.visibility = if (p?.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        audioButton.visibility = if (p?.isYouTube == true) View.VISIBLE else View.GONE
        audioButton.text = if (Store.audioOnly() || p?.video == false) "להציג תמונה" else "שמע בלבד"
        speedButton.text = speedLabel(Store.speed())
        sourceButton.visibility = if (p != null && p.page.isNotBlank()) View.VISIBLE else View.GONE

        val rows = ArrayList<Row>()
        if (c != null && c.mediaItemCount > 1) {
            for (i in 0 until c.mediaItemCount) {
                val item = Playback.playableOf(c.getMediaItemAt(i)) ?: continue
                rows += EntryRow(
                    item.title, item.subtitle,
                    icon = if (i == c.currentMediaItemIndex) R.drawable.ic_graphic_eq else R.drawable.ic_play_arrow_fill,
                    highlight = i == c.currentMediaItemIndex,
                ) {
                    c.seekTo(i, Store.position(item.id))
                    c.play()
                }
            }
        }
        queueHeader.text = if (rows.isEmpty()) "" else "ברשימה (${rows.size})"
        queueHeader.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        queue.submit(rows)
    }

    private val speeds = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    private fun speedLabel(s: Float): String {
        val t = if (s % 1f == 0f) s.toInt().toString() else s.toString()
        return "$t×"
    }

    private fun chooseSpeed() {
        val current = speeds.indexOfFirst { kotlin.math.abs(it - Store.speed()) < 0.01f }
        MaterialAlertDialogBuilder(act)
            .setTitle("מהירות השמעה")
            .setSingleChoiceItems(speeds.map { speedLabel(it) }.toTypedArray(), current) { d, which ->
                Playback.setSpeed(act, speeds[which])
                render()
                d.dismiss()
            }
            .show()
    }

    override fun destroy() {
        act.detachPlayer(playerView)
        super.destroy()
    }
}

/** Shows a lesson's / answer's / page's blocks as native views. */
object BlockViews {
    fun render(act: MainActivity, into: LinearLayout, blocks: List<Block>, owner: String, page: String) {
        for (b in blocks) {
            when (b) {
                is Block.Text -> into.add(textView(act, b), top = act.dp(if (b.style == Block.HEADING || b.style == Block.SUBHEADING) 14 else 6))
                is Block.Image -> if (Store.images()) {
                    val img = ImageView(act).apply {
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = b.caption
                    }
                    into.add(img, top = act.dp(10))
                    img.show(b.url)
                }
                is Block.YouTube -> into.add(
                    mediaCard(act, "צפייה בסרטון" + if (b.startSec > 0) " (מ־${Dates.duration(b.startSec)})" else "", R.drawable.ic_smart_display) {
                        act.playNow(listOf(Playable.youtube(b.id, owner, "", page)), startMs = b.startSec * 1000)
                    },
                    top = act.dp(10),
                )
                is Block.Video -> into.add(
                    mediaCard(act, "צפייה בסרטון", R.drawable.ic_smart_display) {
                        act.playNow(listOf(Playable.file(b.url, owner, "", page, video = true)))
                    },
                    top = act.dp(10),
                )
                is Block.Audio -> into.add(
                    mediaCard(act, "האזנה", R.drawable.ic_graphic_eq) {
                        act.playNow(listOf(Playable.file(b.url, owner, "", page, video = false)))
                    },
                    top = act.dp(10),
                )
                is Block.File -> into.add(
                    mediaCard(act, b.name.ifBlank { "קובץ מצורף" }, R.drawable.ic_description) {
                        if (b.url.lowercase().substringBefore('?').endsWith(".pdf")) act.push(PdfScreen(act, b.url, b.name)) else act.open(b.url)
                    },
                    top = act.dp(10),
                )
            }
        }
    }

    fun textView(act: MainActivity, b: Block.Text): TextView {
        val (size, colorRes, bold) = when (b.style) {
            Block.HEADING -> Triple(20f, R.color.o_on_surface, true)
            Block.SUBHEADING -> Triple(17.5f, R.color.o_on_surface, true)
            Block.SMALL -> Triple(13f, R.color.o_on_surface_variant, false)
            else -> Triple(16.5f, R.color.o_on_surface, false)
        }
        val t = act.text(size, colorRes, bold)
        t.setLineSpacing(0f, 1.25f)
        t.setTextIsSelectable(true)
        t.withoutWebActions()
        val html = if (b.style == Block.BULLET) "•&nbsp;&nbsp;${b.html}" else b.html
        t.setHtml(html) { act.open(it) }
        when (b.style) {
            Block.QUOTE -> {
                t.setTypeface(t.typeface, Typeface.ITALIC)
                t.background = GradientDrawable().apply {
                    cornerRadius = act.dp(10).toFloat()
                    setColor(act.color(R.color.o_surface_container))
                }
                t.setPadding(act.dp(14), act.dp(10), act.dp(14), act.dp(10))
            }
            Block.BULLET -> t.setPadding(act.dp(8), 0, 0, 0)
        }
        return t
    }

    fun mediaCard(act: Context, label: String, icon: Int, onClick: () -> Unit): View {
        val row = act.horizontal()
        row.setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(12))
        val ic = ImageView(act).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(act.color(R.color.o_primary))
        }
        row.addView(ic, LinearLayout.LayoutParams(act.dp(26), act.dp(26)).apply { marginEnd = act.dp(12) })
        row.add(act.text(15.5f, R.color.o_primary, bold = true).apply {
            text = label
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, 0, weight = 1f)
        row.clickable(14, R.color.o_primary_container) { onClick() }
        return row
    }

    /** A scrolling page with a padded column inside. */
    fun page(act: Context): Pair<NestedScrollView, LinearLayout> {
        val scroll = NestedScrollView(act).apply { isFillViewport = true }
        val col = act.vertical()
        col.setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(32))
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll to col
    }

    fun title(act: Context, text: String): TextView = act.text(21f, bold = true).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        setLineSpacing(0f, 1.15f)
        setTextIsSelectable(true)
        withoutWebActions()
    }
}
