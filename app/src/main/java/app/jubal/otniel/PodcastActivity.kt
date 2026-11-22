package app.jubal.otniel

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import coil.transform.RoundedCornersTransformation
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One of the yeshiva's podcasts: its episodes, played in the app (also with the screen off).
 * Opened from the podcast's Spotify, Apple or YouTube link on the site.
 */
class PodcastActivity : AppCompatActivity() {
    companion object {
        fun open(context: Context, p: PodcastRef) {
            context.startActivity(
                Intent(context, PodcastActivity::class.java)
                    .putExtra("feed", p.feedUrl)
                    .putExtra("title", p.title)
                    .putExtra("presenter", p.presenter)
                    .putExtra("image", p.image)
                    .putExtra("spotify", p.spotify)
                    .putExtra("apple", p.apple)
                    .putExtra("youtube", p.youtube)
            )
        }

        private val DATE = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT)
    }

    private lateinit var podcast: PodcastRef
    private var feed: Feed? = null
    private lateinit var list: RecyclerView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var loading: CircularProgressIndicator
    private lateinit var message: LinearLayout
    private lateinit var messageText: TextView
    private val adapter = EpisodeAdapter()

    private val podcastTitle: String get() = podcast.title.ifBlank { feed?.title.orEmpty() }
    private val coverImage: String get() = podcast.image.ifBlank { feed?.image.orEmpty() }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                adapter.notifyDataSetChanged()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val x = intent
        podcast = PodcastRef(
            x.getStringExtra("feed").orEmpty(),
            x.getStringExtra("title").orEmpty(),
            x.getStringExtra("presenter").orEmpty(),
            x.getStringExtra("image").orEmpty(),
            x.getStringExtra("spotify").orEmpty(),
            x.getStringExtra("apple").orEmpty(),
            x.getStringExtra("youtube").orEmpty(),
        )
        if (podcast.feedUrl.isBlank()) {
            finish()
            return
        }

        val toolbar = MaterialToolbar(this).apply {
            setBackgroundColor(Color.WHITE)
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = "חזרה"
            setNavigationOnClickListener { finish() }
        }
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@PodcastActivity.adapter
            clipToPadding = false
            setPadding(0, 0, 0, dp(16))
        }
        refresh = SwipeRefreshLayout(this).apply {
            addView(list, ViewGroup.LayoutParams(-1, -1))
            setColorSchemeColors(color(R.color.o_teal))
            setOnRefreshListener { load(fresh = true) }
        }
        loading = CircularProgressIndicator(this).apply {
            isIndeterminate = true
            setIndicatorColor(color(R.color.o_teal))
        }
        messageText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.o_text))
        }
        val retry = MaterialButton(this).apply {
            text = "לנסות שוב"
            backgroundTintList = ColorStateList.valueOf(color(R.color.o_cyan))
            setTextColor(color(R.color.o_text))
            setOnClickListener { load(fresh = true) }
        }
        message = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
            addView(messageText)
            addView(retry, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        }
        val body = FrameLayout(this).apply {
            setBackgroundColor(color(R.color.o_page))
            addView(refresh, FrameLayout.LayoutParams(-1, -1))
            addView(loading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(message, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(-1, -2))
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(PlayerPanel(this@PodcastActivity), LinearLayout.LayoutParams(-1, -2))
        })

        Playback.connect(this)
        Playback.addListener(listener)
        load(fresh = false)
    }

    override fun onDestroy() {
        Playback.removeListener(listener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
    }

    /** First the copy kept on the phone (shown at once), then the feed fresh from the internet. */
    private fun load(fresh: Boolean) {
        message.visibility = View.GONE
        lifecycleScope.launch {
            if (!fresh && feed == null) {
                withContext(Dispatchers.IO) { Podcasts.cached(podcast.feedUrl) }?.let { show(it) }
            }
            if (feed == null) loading.visibility = View.VISIBLE
            val result = withContext(Dispatchers.IO) { runCatching { Podcasts.load(podcast.feedUrl) } }
            loading.visibility = View.GONE
            refresh.isRefreshing = false
            result.onSuccess { show(it) }.onFailure {
                android.util.Log.w("Otniel", "podcast feed ${podcast.feedUrl}: $it")
                if (feed == null) {
                    messageText.text = "לא הצלחנו לטעון את הפרקים. אולי אין חיבור לאינטרנט."
                    message.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun show(f: Feed) {
        feed = f
        if (f.episodes.isEmpty()) {
            messageText.text = "עדיין אין פרקים בפודקאסט הזה."
            message.visibility = View.VISIBLE
        }
        adapter.notifyDataSetChanged()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (podcast.spotify.isNotBlank()) menu.add(0, 1, 0, "פתיחה בספוטיפיי")
        if (podcast.apple.isNotBlank()) menu.add(0, 2, 1, "פתיחה ב-Apple Podcasts")
        if (podcast.youtube.isNotBlank()) menu.add(0, 3, 2, "צפייה ביוטיוב")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val url = when (item.itemId) {
            1 -> podcast.spotify
            2 -> podcast.apple
            3 -> podcast.youtube
            else -> return super.onOptionsItemSelected(item)
        }
        Outside.website(this, url)
        return true
    }

    private fun toggle(e: Episode) {
        val c = Playback.controller
        if (c != null && c.currentMediaItem?.mediaId == e.id && (c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING))) {
            c.pause()
        } else {
            Playback.play(this, podcast, podcastTitle, coverImage, e)
        }
    }

    // ------------------------------------------------------------------ the list: the podcast's card, then its episodes

    private inner class EpisodeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = feed?.let { 1 + it.episodes.size } ?: 0

        override fun getItemViewType(position: Int): Int = if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) HeaderHolder(LinearLayout(this@PodcastActivity)) else EpisodeHolder(card())

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is HeaderHolder -> holder.bind()
                is EpisodeHolder -> feed?.episodes?.getOrNull(position - 1)?.let { holder.bind(it) }
            }
        }
    }

    private inner class HeaderHolder(row: LinearLayout) : RecyclerView.ViewHolder(row) {
        private val image = ImageView(this@PodcastActivity)
        private val title = TextView(this@PodcastActivity)
        private val presenter = TextView(this@PodcastActivity)
        private val count = TextView(this@PodcastActivity)

        init {
            row.layoutParams = RecyclerView.LayoutParams(-1, -2)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(16), dp(20), dp(16), dp(12))
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.contentDescription = "תמונת הפודקאסט"
            row.addView(image, LinearLayout.LayoutParams(dp(118), dp(118)))
            title.apply {
                textSize = 34f
                setTypeface(titleFont() ?: Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(color(R.color.o_text))
                setLineSpacing(0f, 0.85f)
            }
            presenter.apply {
                textSize = 15f
                setTextColor(color(R.color.o_teal))
            }
            count.apply {
                textSize = 13f
                setTextColor(color(R.color.o_muted))
                setPadding(0, dp(4), 0, 0)
            }
            row.addView(LinearLayout(this@PodcastActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, 0, 0)
                addView(title)
                addView(presenter)
                addView(count)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }

        fun bind() {
            val f = feed ?: return
            title.text = podcastTitle
            presenter.text = podcast.presenter.ifBlank { f.author }
            presenter.visibility = if (presenter.text.isNullOrBlank()) View.GONE else View.VISIBLE
            count.text = when (val n = f.episodes.size) {
                1 -> "פרק אחד"
                else -> "$n פרקים"
            }
            image.load(coverImage) {
                crossfade(true)
                transformations(RoundedCornersTransformation(dp(16).toFloat()))
            }
        }
    }

    private inner class EpisodeHolder(box: FrameLayout) : RecyclerView.ViewHolder(box) {
        private val play: ImageView
        private val title = TextView(this@PodcastActivity)
        private val meta = TextView(this@PodcastActivity)
        private val heard = LinearProgressIndicator(this@PodcastActivity)
        private var episode: Episode? = null

        init {
            itemView.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(6), dp(12), dp(6)) }
            play = roundButton(R.drawable.ic_play_arrow_fill, 44, "ניגון") { episode?.let { toggle(it) } }
            title.apply {
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.o_text))
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }
            meta.apply {
                textSize = 13f
                setTextColor(color(R.color.o_teal))
                setPadding(0, dp(2), 0, 0)
            }
            heard.apply {
                max = 100
                trackThickness = dp(3)
                trackCornerRadius = dp(2)
                setIndicatorColor(color(R.color.o_teal))
                trackColor = color(R.color.o_soft)
            }
            val texts = LinearLayout(this@PodcastActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(title)
                addView(meta)
                addView(heard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            val row = LinearLayout(this@PodcastActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                addView(play)
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            }
            box.addView(row, FrameLayout.LayoutParams(-1, -2))
            itemView.setOnClickListener { episode?.let { toggle(it) } }
        }

        fun bind(e: Episode) {
            episode = e
            title.text = e.title
            val parts = ArrayList<String>()
            if (e.date > 0) parts += DATE.format(Date(e.date))
            Feeds.length(e.durationSec).takeIf { it.isNotEmpty() }?.let { parts += it }
            val done = Store.heard(e.id)
            if (done >= 1f) parts += "✓ הושמע"
            meta.text = parts.joinToString(" · ")
            heard.visibility = if (done > 0f && done < 1f) View.VISIBLE else View.GONE
            heard.progress = (done * 100).toInt()

            val c = Playback.controller
            val current = c?.currentMediaItem?.mediaId == e.id
            val playing = current && c != null && (c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING))
            play.setImageResource(if (playing) R.drawable.ic_pause_fill else R.drawable.ic_play_arrow_fill)
            play.contentDescription = if (playing) "השהיה" else "ניגון"
            itemView.background = rounded(if (current) color(R.color.o_soft) else Color.WHITE, 16)
        }
    }
}
