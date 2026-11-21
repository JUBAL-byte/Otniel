package app.jubal.otniel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {
    companion object {
        const val ACTION_NOW_PLAYING = "app.jubal.otniel.NOW_PLAYING"
        private const val POLL_MS = 2 * 60_000L
    }

    private val stacks = Array(5) { ArrayList<Screen>() }
    private var tab = Links.TAB_HOME
    private var shown: Screen? = null

    private lateinit var content: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var backButton: ImageButton
    private lateinit var actionsBox: LinearLayout
    private lateinit var nav: BottomNavigationView
    private lateinit var mini: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniSubtitle: TextView
    private lateinit var miniPlay: ImageButton
    private lateinit var fullscreen: FrameLayout
    private lateinit var fullPlayer: PlayerView

    private var activePlayerView: PlayerView? = null
    private var beforeFullscreen: PlayerView? = null
    private var isFullscreen = false

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateMini()
            keepScreenOn()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            (shown as? PlayerAware)?.onMediaChanged()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Settings.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        Playback.connect(this)
        Playback.addListener(playerListener)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })

        val handled = savedInstanceState == null && handleIntent(intent)
        if (!handled) selectTab(Links.TAB_HOME)

        if (Build.VERSION.SDK_INT >= 33 && !Store.askedNotifications() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Store.setAskedNotifications()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // While the app is open, look for new content every two minutes
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(POLL_MS)
                    val found = withContext(Dispatchers.IO) { runCatching { Updates.check(this@MainActivity, notify = false) }.getOrDefault(emptyList()) }
                    if (found.isNotEmpty()) announce(found)
                }
            }
        }
        checkForUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        Playback.removeListener(playerListener)
        activePlayerView?.player = null
        stacks.forEach { s -> s.forEach { it.destroy() } }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ layout

    private fun buildLayout(): View {
        val root = FrameLayout(this)
        val main = vertical()
        root.addView(main, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top bar
        val bar = horizontal()
        bar.setBackgroundColor(color(R.color.o_bar))
        bar.setPadding(dp(4), 0, dp(4), 0)
        backButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.o_on_bar))
            background = pressable(24)
            contentDescription = "חזרה"
            setOnClickListener { back() }
        }
        bar.addView(backButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        titleView = text(18f, R.color.o_on_bar, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
        }
        bar.add(titleView, 0, weight = 1f)
        actionsBox = horizontal()
        bar.add(actionsBox, ViewGroup.LayoutParams.WRAP_CONTENT)
        main.add(bar, height = dp(56))

        content = FrameLayout(this)
        main.add(content, height = 0, weight = 1f)

        // Mini player
        mini = horizontal()
        mini.setBackgroundColor(color(R.color.o_surface_container_high))
        mini.setPadding(dp(8), dp(4), dp(8), dp(4))
        miniPlay = ImageButton(this).apply {
            setImageResource(R.drawable.ic_play_arrow_fill)
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.o_primary))
            background = pressable(24)
            contentDescription = "נגן או השהה"
            setOnClickListener {
                val c = Playback.controller ?: return@setOnClickListener
                if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
            }
        }
        mini.addView(miniPlay, LinearLayout.LayoutParams(dp(48), dp(48)))
        val miniTexts = vertical()
        miniTexts.setPadding(dp(8), 0, dp(8), 0)
        miniTitle = text(14.5f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        miniSubtitle = text(12.5f, R.color.o_on_surface_variant).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        miniTexts.add(miniTitle)
        miniTexts.add(miniSubtitle)
        mini.add(miniTexts, 0, weight = 1f)
        miniTexts.clickable(8) { push(PlayerScreen(this)) }
        val miniClose = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.o_on_surface_variant))
            background = pressable(24)
            contentDescription = "סגור נגן"
            setOnClickListener {
                Playback.controller?.run { stop(); clearMediaItems() }
                updateMini()
            }
        }
        mini.addView(miniClose, LinearLayout.LayoutParams(dp(44), dp(44)))
        mini.visibility = View.GONE
        main.add(mini)

        nav = BottomNavigationView(this).apply {
            menu.add(0, R.id.nav_home, 0, "בית").setIcon(R.drawable.ic_home)
            menu.add(0, R.id.nav_lessons, 1, "שיעורים").setIcon(R.drawable.ic_lesson)
            menu.add(0, R.id.nav_series, 2, "סדרות").setIcon(R.drawable.ic_smart_display)
            menu.add(0, R.id.nav_podcasts, 3, "הסכתים").setIcon(R.drawable.ic_podcasts)
            menu.add(0, R.id.nav_more, 4, "עוד").setIcon(R.drawable.ic_menu)
            labelVisibilityMode = com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
            setBackgroundColor(color(R.color.o_surface_container))
            setOnItemSelectedListener { item ->
                selectTab(tabOf(item.itemId))
                true
            }
            setOnItemReselectedListener { item -> popToRoot(tabOf(item.itemId)) }
        }
        main.add(nav)

        // Full screen video
        fullscreen = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
        }
        fullPlayer = PlayerView(this).apply {
            setShowNextButton(true)
            setShowPreviousButton(true)
        }
        fullscreen.addView(fullPlayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val exit = ImageButton(this).apply {
            setImageResource(R.drawable.ic_fullscreen_exit)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setBackgroundColor(0x55000000)
            contentDescription = "יציאה ממסך מלא"
            setOnClickListener { exitFullscreen() }
        }
        fullscreen.addView(exit, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        root.addView(fullscreen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun tabOf(itemId: Int): Int = when (itemId) {
        R.id.nav_lessons -> Links.TAB_LESSONS
        R.id.nav_series -> Links.TAB_SERIES
        R.id.nav_podcasts -> Links.TAB_PODCASTS
        R.id.nav_more -> Links.TAB_MORE
        else -> Links.TAB_HOME
    }

    private fun itemOf(tab: Int): Int = when (tab) {
        Links.TAB_LESSONS -> R.id.nav_lessons
        Links.TAB_SERIES -> R.id.nav_series
        Links.TAB_PODCASTS -> R.id.nav_podcasts
        Links.TAB_MORE -> R.id.nav_more
        else -> R.id.nav_home
    }

    private fun rootScreen(tab: Int): Screen = when (tab) {
        Links.TAB_LESSONS -> LessonsScreen(this, Site.Query(), "שיעורים", isRoot = true)
        Links.TAB_SERIES -> SeriesScreen(this)
        Links.TAB_PODCASTS -> PodcastsScreen(this)
        Links.TAB_MORE -> MoreScreen(this)
        else -> HomeScreen(this)
    }

    // ------------------------------------------------------------------ navigation

    fun selectTab(t: Int) {
        tab = t
        if (stacks[t].isEmpty()) stacks[t] += rootScreen(t)
        if (nav.selectedItemId != itemOf(t)) {
            nav.setOnItemSelectedListener(null)
            nav.selectedItemId = itemOf(t)
            nav.setOnItemSelectedListener { item -> selectTab(tabOf(item.itemId)); true }
        }
        display(stacks[t].last())
    }

    private fun popToRoot(t: Int) {
        val s = stacks[t]
        while (s.size > 1) s.removeAt(s.size - 1).destroy()
        if (t == tab && s.isNotEmpty()) display(s.last())
    }

    fun push(screen: Screen) {
        if (stacks[tab].isEmpty()) stacks[tab] += rootScreen(tab)
        stacks[tab] += screen
        display(screen)
    }

    /** Opens [screen] in [inTab] (used for links that point to a whole section). */
    fun pushIn(inTab: Int, screen: Screen) {
        selectTab(inTab)
        push(screen)
    }

    fun back() {
        if (isFullscreen) {
            exitFullscreen()
            return
        }
        val s = stacks[tab]
        if (s.size > 1) {
            val gone = s.removeAt(s.size - 1)
            display(s.last())
            gone.destroy()
        } else if (tab != Links.TAB_HOME) {
            selectTab(Links.TAB_HOME)
        } else {
            moveTaskToBack(true)
        }
    }

    private fun display(screen: Screen) {
        val previous = shown
        if (previous !== screen) previous?.onHide()
        shown = screen
        val v = screen.view
        (v.parent as? ViewGroup)?.removeView(v)
        content.removeAllViews()
        content.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        refreshBar()
        screen.onShow()
        updateMini()
    }

    fun refreshBar() {
        val screen = shown ?: return
        titleView.text = screen.title
        backButton.visibility = if (stacks[tab].size > 1) View.VISIBLE else View.GONE
        titleView.setPadding(dp(if (stacks[tab].size > 1) 4 else 12), 0, dp(8), 0)
        actionsBox.removeAllViews()
        for (a in screen.actions()) {
            val b = ImageButton(this).apply {
                setImageResource(a.icon)
                imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.o_on_bar))
                background = pressable(24)
                contentDescription = a.label
                if (Build.VERSION.SDK_INT >= 26) tooltipText = a.label
                setOnClickListener { a.onClick() }
            }
            actionsBox.addView(b, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
    }

    // ------------------------------------------------------------------ links

    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val url = intent.dataString ?: return false
                if (stacks[tab].isEmpty()) selectTab(Links.TAB_HOME)
                open(url)
                return true
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return false
                val url = Regex("https?://\\S+").find(text)?.value ?: return false
                if (stacks[tab].isEmpty()) selectTab(Links.TAB_HOME)
                open(url)
                return true
            }
            ACTION_NOW_PLAYING -> {
                if (stacks[tab].isEmpty()) selectTab(Links.TAB_HOME)
                if (shown !is PlayerScreen) push(PlayerScreen(this))
                return true
            }
        }
        return false
    }

    /** Opens any link: the yeshiva's content inside the app, anything else only after asking. */
    fun open(url: String) {
        when (val t = Links.target(url)) {
            Target.Home -> selectTab(Links.TAB_HOME)
            is Target.Tab -> {
                selectTab(t.tab)
                popToRoot(t.tab)
            }
            is Target.LessonSlug -> push(LessonScreen(this, slug = t.slug))
            is Target.Topic -> push(LessonsScreen.forTopicSlug(this, t.slug))
            is Target.Rabbi -> push(LessonsScreen.forRabbiSlug(this, t.slug))
            is Target.FaqSlug -> push(FaqScreen(this, slug = t.slug))
            Target.FaqList -> push(FaqListScreen(this))
            Target.Rabbis -> push(RabbisScreen(this))
            Target.Contact -> push(PageScreen(this, "contact", "צור קשר", contact = true))
            Target.About -> push(PageScreen(this, "about", "על הישיבה"))
            Target.Store -> push(StoreScreen(this))
            is Target.Product -> push(BookScreen(this, slug = t.slug))
            is Target.Page -> push(PageScreen(this, t.slug, "", url = t.url))
            is Target.Browser -> confirmBrowser(t.url)
            is Target.Video -> playVideo(t.id, t.startSec)
            is Target.Playlist -> push(PlaylistScreen(this, t.url, ""))
            is Target.Spotify -> openShow { it.spotify.isNotBlank() && t.url.contains(it.spotify.substringAfterLast('/')) }
                ?: externalDialog(url)
            is Target.Apple -> openShow { Links.appleId(it.apple) == t.id }
                ?: push(ShowScreen(this, Show("", "", "", "", t.url, "")))
            is Target.Pdf -> push(PdfScreen(this, t.url, Uri.decode(t.url.substringAfterLast('/')).substringBeforeLast('.')))
            is Target.Media -> {
                val name = Uri.decode(t.url.substringAfterLast('/'))
                val video = Regex("\\.(webm|mp4|m4v|mov)$", RegexOption.IGNORE_CASE).containsMatchIn(t.url)
                playNow(listOf(Playable.file(t.url, name, "", video = video)))
            }
            is Target.Phone -> Outside.dial(this, t.uri)
            is Target.Mail -> Outside.mail(this, t.uri)
            is Target.External -> externalDialog(t.url)
        }
    }

    private fun openShow(match: (Show) -> Boolean): Unit? {
        val show = Site.cachedShows().firstOrNull(match) ?: return null
        push(ShowScreen(this, show))
        return Unit
    }

    /** Plays a YouTube video by id (from a link), then shows the player. */
    private fun playVideo(id: String, startSec: Long) {
        val placeholder = Playable.youtube(id, "סרטון", "")
        playNow(listOf(placeholder), startMs = if (startSec > 0) startSec * 1000 else -1)
        lifecycleScope.launch {
            val v = withContext(Dispatchers.IO) { runCatching { YouTube.video(id) }.getOrNull() } ?: return@launch
            val c = Playback.controller ?: return@launch
            if (c.currentMediaItem?.mediaId == placeholder.id) {
                val real = Playable.youtube(id, v.title, v.uploader)
                c.replaceMediaItem(c.currentMediaItemIndex, Playback.mediaItem(real))
                Store.addRecent(real)
            }
        }
    }

    /** Starts playing and shows the "now playing" screen (unless the current screen shows it). */
    fun playNow(items: List<Playable>, index: Int = 0, startMs: Long = -1, video: Boolean? = null) {
        val list = if (video == null) items else items.map { it.copy(video = video) }
        Playback.play(this, list, index, startMs)
        val id = list.getOrNull(index)?.id ?: return
        if (shown?.showsPlayerFor(id) != true && shown !is PlayerScreen) push(PlayerScreen(this))
    }

    fun externalDialog(url: String) {
        val hasBrowser = Outside.hasBrowser(this)
        val b = MaterialAlertDialogBuilder(this)
            .setTitle("קישור מחוץ לתוכן הישיבה")
            .setMessage("הקישור הזה לא נפתח בתוך היישומון:\n\n$url")
            .setNegativeButton("סגור", null)
            .setNeutralButton("העתק קישור") { _, _ -> Outside.copy(this, url) }
        if (hasBrowser) b.setPositiveButton("פתח בדפדפן") { _, _ -> Outside.browser(this, url) }
        b.show()
    }

    /** Cart, payment, account and donations open only in the phone's browser, outside the app. */
    fun confirmBrowser(url: String, message: String = "הדף הזה נפתח בדפדפן של הטלפון, מחוץ ליישומון.") {
        if (!Outside.hasBrowser(this)) {
            Outside.copy(this, url)
            MaterialAlertDialogBuilder(this)
                .setTitle("אין דפדפן בטלפון")
                .setMessage("לא מצאנו דפדפן בטלפון הזה, אז העתקנו את הקישור:\n\n$url\n\nאפשר לפתוח אותו במחשב או בטלפון אחר.")
                .setPositiveButton("הבנתי", null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("מעבר לדפדפן")
            .setMessage(message)
            .setPositiveButton("פתח בדפדפן") { _, _ -> Outside.browser(this, url) }
            .setNegativeButton("ביטול", null)
            .show()
    }

    /** Android's screen for "open supported links" of this app. */
    fun openLinkSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 31) {
            Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:$packageName"))
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
    }

    /** Whether otniel.org links already open here (known only on Android 12 and newer). */
    fun linksEnabled(): Boolean? {
        if (Build.VERSION.SDK_INT < 31) return null
        return runCatching {
            val m = getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
            val state = m.getDomainVerificationUserState(packageName) ?: return null
            val map = state.hostToStateMap
            val s = map["otniel.org"] ?: map["www.otniel.org"] ?: return false
            s != android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_NONE
        }.getOrNull()
    }

    // ------------------------------------------------------------------ player

    fun attachPlayer(view: PlayerView) {
        if (activePlayerView === view && view.player != null) return
        Playback.with(this) { c ->
            val old = activePlayerView
            if (old != null && old !== view) PlayerView.switchTargetView(c, old, view) else view.player = c
            activePlayerView = view
            keepScreenOn()
        }
    }

    fun detachPlayer(view: PlayerView) {
        if (activePlayerView === view) {
            view.player = null
            activePlayerView = null
            keepScreenOn()
        }
    }

    fun enterFullscreen() {
        val c = Playback.controller ?: return
        beforeFullscreen = activePlayerView
        fullscreen.visibility = View.VISIBLE
        val old = activePlayerView
        if (old != null) PlayerView.switchTargetView(c, old, fullPlayer) else fullPlayer.player = c
        activePlayerView = fullPlayer
        isFullscreen = true
        val size = c.videoSize
        requestedOrientation = if (size.width >= size.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        fullPlayer.requestFocus()
    }

    fun exitFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false
        val c = Playback.controller
        val back = beforeFullscreen?.takeIf { it.isAttachedToWindow }
        if (c != null && back != null) PlayerView.switchTargetView(c, fullPlayer, back) else fullPlayer.player = null
        activePlayerView = back
        beforeFullscreen = null
        fullscreen.visibility = View.GONE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        keepScreenOn()
    }

    private fun keepScreenOn() {
        val c = Playback.controller
        val watching = c != null && c.isPlaying && activePlayerView != null && Playback.current()?.video == true && !Store.audioOnly()
        if (watching) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun updateMini() {
        if (!::mini.isInitialized) return
        val c = Playback.controller
        val item = c?.currentMediaItem
        val screen = shown
        val hide = item == null || screen is PlayerScreen || (screen?.showsPlayerFor(item.mediaId) == true)
        mini.visibility = if (hide) View.GONE else View.VISIBLE
        if (item == null || c == null) return
        miniTitle.text = item.mediaMetadata.title ?: ""
        miniSubtitle.text = item.mediaMetadata.artist ?: ""
        val playing = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)
        miniPlay.setImageResource(if (playing) R.drawable.ic_pause_fill else R.drawable.ic_play_arrow_fill)
    }

    // ------------------------------------------------------------------ new content and updates

    private fun announce(found: List<NewItem>) {
        (stacks[Links.TAB_HOME].firstOrNull() as? HomeScreen)?.refreshSoon()
        val first = found.first()
        val text = if (found.size == 1) "${first.kind}: ${first.title}" else "נוספו ${found.size} פריטים חדשים באתר"
        Snackbar.make(content, text, Snackbar.LENGTH_LONG)
            .setAction("לצפייה") { if (found.size == 1) open(first.link) else selectTab(Links.TAB_HOME) }
            .show()
    }

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val release = runCatching { Updater.newer(this@MainActivity, force = false) }.getOrNull() ?: return@launch
            Snackbar.make(content, "יש גרסה חדשה של היישומון", Snackbar.LENGTH_INDEFINITE)
                .setAction("עדכן") { installUpdate(release) }
                .show()
        }
    }

    fun installUpdate(release: Updater.Release) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "מוריד את הגרסה החדשה…", Toast.LENGTH_SHORT).show()
            val file = runCatching { Updater.download(this@MainActivity, release) {} }.getOrNull()
            if (file == null) {
                Toast.makeText(this@MainActivity, "ההורדה לא הצליחה, נסה שוב מאוחר יותר", Toast.LENGTH_LONG).show()
                return@launch
            }
            Updater.install(this@MainActivity, file)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Keypad phones: the media keys and the number 5 control the player when it is showing
        if (event.action == KeyEvent.ACTION_DOWN && isFullscreen && event.keyCode == KeyEvent.KEYCODE_5) {
            Playback.controller?.let { if (it.isPlaying) it.pause() else it.play() }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/** A screen that wants to know when the playing item changes. */
interface PlayerAware {
    fun onMediaChanged()
}
