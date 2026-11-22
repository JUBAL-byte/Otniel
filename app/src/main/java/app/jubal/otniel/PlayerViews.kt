package app.jubal.otniel

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.Player
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// The site's colors and shapes, for the few screens the app draws itself (the podcast player)

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.color(id: Int): Int = ContextCompat.getColor(this, id)

fun Context.titleFont(): Typeface? = runCatching { ResourcesCompat.getFont(this, R.font.karantina) }.getOrNull()

fun Context.rounded(fill: Int, radiusDp: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = dp(radiusDp).toFloat()
    if (stroke != 0) setStroke(dp(1), stroke)
}

/** The site's round play button: a light-blue circle with a dark icon. */
fun Context.roundButton(icon: Int, sizeDp: Int, description: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
    setImageResource(icon)
    imageTintList = ColorStateList.valueOf(color(R.color.o_text))
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color(R.color.o_cyan))
    }
    val pad = dp(sizeDp) / 5
    setPadding(pad, pad, pad, pad)
    contentDescription = description
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    setOnClickListener { onClick() }
}

fun Context.plainButton(icon: Int, sizeDp: Int, description: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
    setImageResource(icon)
    imageTintList = ColorStateList.valueOf(color(R.color.o_teal))
    val pad = dp(sizeDp) / 5
    setPadding(pad, pad, pad, pad)
    contentDescription = description
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    val ripple = android.util.TypedValue()
    context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
    setBackgroundResource(ripple.resourceId)
    setOnClickListener { onClick() }
}

private fun isPlaying(p: Player): Boolean = p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING)

/**
 * The small bar under the site while an episode is playing: play/pause, what is playing,
 * and a close button. Tapping it opens the podcast's page.
 */
class MiniPlayer(context: Context, private val open: () -> Unit) : LinearLayout(context) {
    private val play: ImageView
    private val title = TextView(context)
    private val subtitle = TextView(context)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update()
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(context.dp(12), context.dp(8), context.dp(8), context.dp(8))
        elevation = context.dp(8).toFloat()
        visibility = View.GONE

        play = context.roundButton(R.drawable.ic_play_arrow_fill, 42, "ניגון") {
            Playback.controller?.let { if (isPlaying(it)) it.pause() else it.play() }
        }
        addView(play)

        title.apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.color(R.color.o_text))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitle.apply {
            textSize = 12f
            setTextColor(context.color(R.color.o_teal))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(context.dp(12), 0, context.dp(8), 0)
            addView(title)
            addView(subtitle)
            setOnClickListener { open() }
        }
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(context.plainButton(R.drawable.ic_close, 40, "סגירת הנגן") { Playback.stop() })
        setOnClickListener { open() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Playback.addListener(listener)
        update()
    }

    override fun onDetachedFromWindow() {
        Playback.removeListener(listener)
        super.onDetachedFromWindow()
    }

    fun update() {
        val c = Playback.controller
        val item = c?.currentMediaItem
        visibility = if (item == null) View.GONE else View.VISIBLE
        if (c == null || item == null) return
        title.text = item.mediaMetadata.title ?: ""
        subtitle.text = item.mediaMetadata.artist ?: ""
        val playing = isPlaying(c)
        play.setImageResource(if (playing) R.drawable.ic_pause_fill else R.drawable.ic_play_arrow_fill)
        play.contentDescription = if (playing) "השהיה" else "ניגון"
    }
}

/** The player at the bottom of a podcast's page: what is playing, the time line and the buttons. */
class PlayerPanel(context: Context) : LinearLayout(context) {
    companion object {
        val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

        fun speedLabel(speed: Float): String =
            (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"
    }

    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val seek = SeekBar(context)
    private val elapsed = TextView(context)
    private val total = TextView(context)
    private val speed = TextView(context)
    private val play: ImageView
    private var dragging = false
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 500)
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update()
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        elevation = context.dp(10).toFloat()
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(10))
        visibility = View.GONE

        title.apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.color(R.color.o_text))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitle.apply {
            textSize = 13f
            setTextColor(context.color(R.color.o_teal))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(title)
        addView(subtitle)

        // A time line and player buttons run left to right in every language
        val timeRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, context.dp(6), 0, 0)
        }
        for (t in listOf(elapsed, total)) {
            t.textSize = 12f
            t.setTextColor(context.color(R.color.o_teal))
            t.text = "0:00"
        }
        seek.apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(context.color(R.color.o_teal))
            thumbTintList = ColorStateList.valueOf(context.color(R.color.o_teal))
            progressBackgroundTintList = ColorStateList.valueOf(context.color(R.color.o_line))
            contentDescription = "מיקום בפרק"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val d = Playback.controller?.duration ?: 0L
                        if (d > 0) elapsed.text = Feeds.clock(d * progress / 1000 / 1000)
                    }
                }

                override fun onStartTrackingTouch(s: SeekBar) {
                    dragging = true
                }

                override fun onStopTrackingTouch(s: SeekBar) {
                    dragging = false
                    val c = Playback.controller ?: return
                    if (c.duration > 0) c.seekTo(c.duration * s.progress / 1000)
                }
            })
        }
        timeRow.addView(elapsed)
        timeRow.addView(seek, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(total)
        addView(timeRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        speed.apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.color(R.color.o_teal))
            gravity = Gravity.CENTER
            contentDescription = "מהירות השמעה"
            setOnClickListener { chooseSpeed() }
        }
        play = context.roundButton(R.drawable.ic_play_arrow_fill, 60, "ניגון") {
            Playback.controller?.let {
                if (isPlaying(it)) it.pause() else {
                    if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
                    if (it.playbackState == Player.STATE_IDLE) it.prepare()
                    it.play()
                }
            }
        }
        val side = LayoutParams(context.dp(56), context.dp(48))
        buttons.addView(speed, LayoutParams(side))
        buttons.addView(space())
        buttons.addView(context.plainButton(R.drawable.ic_replay_10, 52, "10 שניות אחורה") { Playback.controller?.seekBack() })
        buttons.addView(space())
        buttons.addView(play)
        buttons.addView(space())
        buttons.addView(context.plainButton(R.drawable.ic_forward_30, 52, "30 שניות קדימה") { Playback.controller?.seekForward() })
        buttons.addView(space())
        buttons.addView(View(context), LayoutParams(side))
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun space(): View = View(context).apply { layoutParams = LayoutParams(context.dp(14), 1) }

    private fun chooseSpeed() {
        val now = Store.speed()
        MaterialAlertDialogBuilder(context)
            .setTitle("מהירות השמעה")
            .setSingleChoiceItems(SPEEDS.map { speedLabel(it) }.toTypedArray(), SPEEDS.indexOfFirst { kotlin.math.abs(it - now) < 0.01f }) { d, which ->
                d.dismiss()
                Playback.setSpeed(context, SPEEDS[which])
                speed.text = speedLabel(SPEEDS[which])
            }
            .show()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Playback.addListener(listener)
        update()
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        Playback.removeListener(listener)
        super.onDetachedFromWindow()
    }

    fun update() {
        val c = Playback.controller
        val item = c?.currentMediaItem
        visibility = if (item == null) View.GONE else View.VISIBLE
        if (c == null || item == null) return
        title.text = item.mediaMetadata.title ?: ""
        subtitle.text = item.mediaMetadata.artist ?: ""
        speed.text = speedLabel(c.playbackParameters.speed)
        val playing = isPlaying(c)
        play.setImageResource(if (playing) R.drawable.ic_pause_fill else R.drawable.ic_play_arrow_fill)
        play.contentDescription = if (playing) "השהיה" else "ניגון"
        updateTime()
    }

    private fun updateTime() {
        val c = Playback.controller ?: return
        if (c.currentMediaItem == null || dragging) return
        val d = c.duration
        val p = c.currentPosition
        if (d > 0) {
            seek.progress = (p * 1000 / d).toInt()
            total.text = Feeds.clock(d / 1000)
        } else {
            seek.progress = 0
            total.text = "--:--"
        }
        elapsed.text = Feeds.clock(p / 1000)
    }
}

/** A white card with rounded corners, like the site's. */
fun Context.card(): FrameLayout = FrameLayout(this).apply {
    background = rounded(Color.WHITE, 16)
    elevation = dp(2).toFloat()
}
