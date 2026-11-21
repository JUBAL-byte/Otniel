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
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import coil.load
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
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

    /** What is on screen, like the pages of a website: the home page at the bottom. */
    private val stack = ArrayList<Screen>()
    private var shown: Screen? = null

    private lateinit var content: FrameLayout
    private lateinit var actionsBox: LinearLayout
    private lateinit var pill: com.google.android.material.button.MaterialButton
    private lateinit var mini: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniSubtitle: TextView
    private lateinit var miniPlay: ImageView
    private lateinit var fullscreen: FrameLayout
    private lateinit var fullPlayer: PlayerView
    private lateinit var drawer: FrameLayout
    private lateinit var drawerPanel: LinearLayout

    private var activePlayerView: PlayerView? = null
    private var beforeFullscreen: PlayerView? = null
    private var isFullscreen = false
    private var drawerOpen = false

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateMini()
            keepScreenOn()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            (shown as? PlayerAware)?.onMediaChanged()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val now = System.currentTimeMillis()
            if (now - lastErrorShown < 4_000) return
            lastErrorShown = now
            val youtube = Playback.current()?.isYouTube == true
            val text = if (youtube) "לא הצלחנו לנגן את הסרטון כרגע (יוטיוב לא ענה)." else "לא הצלחנו לנגן את זה כרגע."
            Snackbar.make(content, text, Snackbar.LENGTH_LONG)
                .setAction("לנסות שוב") {
                    Playback.controller?.run {
                        prepare()
                        play()
                    }
                }
                .show()
        }
    }
    private var lastErrorShown = 0L

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

        stack += HomeScreen(this)
        val handled = savedInstanceState == null && handleIntent(intent)
        if (!handled) display(stack.last())

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
        stack.forEach { it.destroy() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ layout (the site's header, menu and pages)

    private fun buildLayout(): View {
        val root = FrameLayout(this)
        val main = vertical()
        main.setBackgroundColor(color(R.color.o_page))
        root.addView(main, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // The site's header: menu, logo, and the light cyan button
        val bar = horizontal()
        bar.setBackgroundColor(color(R.color.o_bar))
        bar.elevation = dp(3).toFloat()
        bar.setPadding(dp(4), 0, dp(10), 0)
        val menuButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_menu)
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.o_teal))
            background = pressable(24)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "תפריט"
            setOnClickListener { openDrawer() }
        }
        bar.addView(menuButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.add(logoView(), ViewGroup.LayoutParams.WRAP_CONTENT)
        bar.add(View(this), 0, dp(1), weight = 1f)
        actionsBox = horizontal()
        bar.add(actionsBox, ViewGroup.LayoutParams.WRAP_CONTENT)
        pill = button("חיפוש") { onPill() }.apply {
            cornerRadius = dp(22)
            minHeight = dp(40)
            minWidth = 0
            minimumWidth = 0
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(14), 0, dp(14), 0)
        }
        bar.add(pill, ViewGroup.LayoutParams.WRAP_CONTENT)
        main.add(bar, height = dp(64))

        content = FrameLayout(this)
        main.add(content, height = 0, weight = 1f)

        // Mini player (white, with the site's cyan play mark)
        mini = horizontal()
        mini.setBackgroundColor(color(R.color.o_bar))
        mini.elevation = dp(6).toFloat()
        mini.setPadding(dp(12), dp(6), dp(8), dp(6))
        miniPlay = circleIcon(R.drawable.ic_play_arrow_fill, 42).apply {
            contentDescription = "נגן או השהה"
            isFocusable = true
            isClickable = true
            setOnClickListener {
                val c = Playback.controller ?: return@setOnClickListener
                if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
            }
        }
        mini.addView(miniPlay, LinearLayout.LayoutParams(dp(42), dp(42)))
        val miniTexts = vertical()
        miniTexts.setPadding(dp(10), dp(2), dp(10), dp(2))
        miniTitle = text(15f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        miniSubtitle = text(12.5f, R.color.o_on_surface_variant).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        miniTexts.add(miniTitle)
        miniTexts.add(miniSubtitle)
        mini.add(miniTexts, 0, weight = 1f)
        miniTexts.clickable(8) { push(PlayerScreen(this)) }
        val miniClose = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.o_text))
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

        // The site's side menu
        drawer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(color(R.color.o_scrim))
            isClickable = true
            setOnClickListener { closeDrawer() }
        }
        drawerPanel = vertical().apply {
            setBackgroundColor(color(R.color.o_card))
            isClickable = true
        }
        val panelWidth = minOf(dp(330), (resources.displayMetrics.widthPixels * 0.86f).toInt())
        drawer.addView(drawerPanel, FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        root.addView(drawer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildMenu()

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

    /** The site's logo (from the site itself), with its name in letters until it arrives. */
    private fun logoView(): View {
        val frame = FrameLayout(this)
        val words = vertical().apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        words.add(display(23f).apply { text = "ישיבת עתניאל" }, ViewGroup.LayoutParams.WRAP_CONTENT)
        words.add(text(10.5f, bold = true).apply { text = "בית ועד הר חברון" }, ViewGroup.LayoutParams.WRAP_CONTENT)
        frame.addView(words, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "ישיבת עתניאל"
            visibility = View.INVISIBLE
        }
        // The logo's own proportions are 384 × 120
        frame.addView(img, FrameLayout.LayoutParams(dp(141), dp(44), Gravity.CENTER_VERTICAL))
        lifecycleScope.launch {
            val logo = withContext(Dispatchers.IO) { Site.logo() } ?: return@launch
            img.setImageDrawable(logo)
            img.visibility = View.VISIBLE
            words.visibility = View.GONE
        }
        frame.clickable(10) { goHome() }
        frame.setPadding(dp(6), dp(4), dp(6), dp(4))
        return frame
    }

    private fun buildMenu() {
        val panel = drawerPanel
        panel.removeAllViews()
        val top = horizontal()
        top.setPadding(dp(8), dp(8), dp(8), dp(4))
        top.add(ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.o_teal))
            background = pressable(24)
            contentDescription = "סגירת התפריט"
            setOnClickListener { closeDrawer() }
        }, dp(48), dp(48))
        panel.add(top)
        val scroll = ScrollView(this)
        val list = vertical()
        list.setPadding(dp(22), 0, dp(22), dp(24))
        scroll.addView(list)
        panel.add(scroll, height = 0, weight = 1f)

        fun section(title: String) {
            list.add(display(30f, R.color.o_teal).apply { text = title }, top = dp(16), bottom = dp(2))
        }
        fun item(title: String, sub: Boolean = false, action: () -> Unit) {
            val t = text(if (sub) 16f else 18.5f, bold = !sub).apply {
                text = title
                setPadding(dp(if (sub) 14 else 6), dp(if (sub) 8 else 11), dp(6), dp(if (sub) 8 else 11))
            }
            t.clickable(10) {
                closeDrawer()
                action()
            }
            list.add(t)
        }
        item("דף הבית") { goHome() }
        section("על הישיבה")
        item("על הישיבה") { go(PageScreen(this, "about", "על הישיבה")) }
        item("רבני הישיבה") { go(RabbisScreen(this)) }
        section("בית מדרש")
        item("שיעורים") { selectTab(Links.TAB_LESSONS) }
        for ((name, slug) in listOf(
            "תנ\"ך" to "tanach", "הלכה" to "halacha", "מדרש, משנה ותלמוד" to "toshbah", "מעגל השנה" to "year-circle",
            "חסידות וקבלה" to "kabala", "מחשבה" to "philosophy", "שונות" to "others", "ימי עיון ואירועים" to "iyun",
            "דפי מקורות ללימוד עצמי" to "self-study-pages",
        )) {
            item(name, sub = true) { go(LessonsScreen.forTopicSlug(this, slug, name)) }
        }
        item("פודקאסטים") { selectTab(Links.TAB_PODCASTS) }
        item("סדרות שיעורים מוסרטים") { selectTab(Links.TAB_SERIES) }
        section("עוד")
        item("שו\"ת") { go(FaqListScreen(this)) }
        item("חנות ספרים") { go(StoreScreen(this)) }
        item("צור קשר") { go(PageScreen(this, "contact", "צור קשר", contact = true)) }
        item("תרומה לישיבה") {
            confirmBrowser("https://otniel.org/donations/", "התרומה נעשית באתר הישיבה, בדפדפן של הטלפון ולא בתוך היישומון.")
        }
        list.add(View(this).apply { setBackgroundColor(color(R.color.o_line)) }, height = dp(1), top = dp(18), bottom = dp(6))
        item("הגדרות היישומון", sub = true) { go(SettingsScreen(this)) }
        item("פתיחת קישורים של האתר ביישומון", sub = true) { openLinkSettings() }
    }

    fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        drawer.visibility = View.VISIBLE
        drawer.alpha = 0f
        drawer.animate().alpha(1f).setDuration(180).start()
        val w = drawerPanel.layoutParams.width.toFloat()
        drawerPanel.translationX = w // RTL: the panel comes in from the right
        drawerPanel.animate().translationX(0f).setDuration(220).start()
        drawerPanel.requestFocus()
    }

    fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        val w = drawerPanel.layoutParams.width.toFloat()
        drawerPanel.animate().translationX(w).setDuration(180).start()
        drawer.animate().alpha(0f).setDuration(180).withEndAction { drawer.visibility = View.GONE }.start()
    }

    // ------------------------------------------------------------------ navigation (like moving between the site's pages)

    /** The site's sections: home, lessons, video series, podcasts (and the menu). */
    fun selectTab(t: Int) {
        when (t) {
            Links.TAB_LESSONS -> go(LessonsScreen(this, Site.Query(), "שיעורים", isRoot = true))
            Links.TAB_SERIES -> go(SeriesScreen(this))
            Links.TAB_PODCASTS -> go(PodcastsScreen(this))
            Links.TAB_MORE -> openDrawer()
            else -> goHome()
        }
    }

    fun goHome() {
        while (stack.size > 1) stack.removeAt(stack.size - 1).destroy()
        display(stack.first())
    }

    /** Opens a main page from the menu: back from it leads home. */
    fun go(screen: Screen) {
        val old = ArrayList(stack.drop(1))
        while (stack.size > 1) stack.removeAt(stack.size - 1)
        stack += screen
        display(screen)
        old.forEach { it.destroy() }
    }

    fun push(screen: Screen) {
        stack += screen
        display(screen)
    }

    fun back() {
        if (drawerOpen) {
            closeDrawer()
            return
        }
        if (isFullscreen) {
            exitFullscreen()
            return
        }
        if (stack.size > 1) {
            val gone = stack.removeAt(stack.size - 1)
            display(stack.last())
            gone.destroy()
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

    /** The cyan button: "חיפוש" on the home page, "חזרה" everywhere else. */
    private fun onPill() {
        if (stack.size > 1) back() else push(LessonsScreen(this, Site.Query(), "חיפוש שיעורים", isRoot = false, searchBox = true))
    }

    fun refreshBar() {
        val screen = shown ?: return
        pill.text = if (stack.size > 1) "חזרה" else "חיפוש"
        actionsBox.removeAllViews()
        for (a in screen.actions()) {
            val b = ImageButton(this).apply {
                setImageResource(a.icon)
                imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.o_teal))
                background = pressable(24)
                contentDescription = a.label
                if (Build.VERSION.SDK_INT >= 26) tooltipText = a.label
                setOnClickListener { a.onClick() }
            }
            actionsBox.addView(b, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
    }

    // ------------------------------------------------------------------ links

    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val url = intent.dataString ?: return false
                openFromOutside(url)
                return true
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return false
                val url = Regex("https?://\\S+").find(text)?.value ?: return false
                openFromOutside(url)
                return true
            }
            ACTION_NOW_PLAYING -> {
                if (shown !is PlayerScreen) push(PlayerScreen(this))
                return true
            }
        }
        return false
    }

    /** A link from outside the app (a message, a notification): opened over the home page. */
    private fun openFromOutside(url: String) {
        if (isFullscreen) exitFullscreen()
        closeDrawer()
        val page = when (Links.target(url)) {
            is Target.Video, is Target.Media, is Target.Phone, is Target.Mail, is Target.External, is Target.Browser -> false
            else -> true
        }
        if (page) {
            while (stack.size > 1) stack.removeAt(stack.size - 1).destroy()
            if (shown == null) display(stack.first())
        } else if (shown == null) {
            display(stack.first())
        }
        open(url)
    }

    /** Opens any link: the yeshiva's content inside the app, anything else only after asking. */
    fun open(url: String) {
        when (val t = Links.target(url)) {
            Target.Home -> goHome()
            is Target.Tab -> selectTab(t.tab)
            is Target.LessonSlug -> push(LessonScreen(this, slug = t.slug))
            is Target.Topic -> push(LessonsScreen.forTopicSlug(this, t.slug, "נושא"))
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
        (stack.firstOrNull() as? HomeScreen)?.refreshSoon()
        val first = found.first()
        val text = if (found.size == 1) "${first.kind}: ${first.title}" else "נוספו ${found.size} פריטים חדשים באתר"
        Snackbar.make(content, text, Snackbar.LENGTH_LONG)
            .setAction("לצפייה") { if (found.size == 1) open(first.link) else goHome() }
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
