package app.jubal.otniel

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The yeshiva's site, otniel.org, in its own phone layout, in a window locked to the site.
 * Only otniel.org opens here. Other websites, the bookstore, payment and donations open
 * outside the app (in their own app or in the phone's browser), and only after asking.
 */
class MainActivity : AppCompatActivity() {
    companion object {
        private const val POLL_MS = 2 * 60_000L
        private const val WEB_STATE = "web"
        private const val LAST_URL = "url"
    }

    private lateinit var root: FrameLayout
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var web: WebView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var offline: LinearLayout
    private lateinit var offlineTitle: TextView
    private lateinit var fullscreen: FrameLayout

    private var custom: View? = null
    private var customDone: WebChromeClient.CustomViewCallback? = null
    private var failed = false
    private var webGone = false
    private var lastUrl = Routes.HOME
    private var filePick: ValueCallback<Array<Uri>>? = null
    private var network: ConnectivityManager.NetworkCallback? = null

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        filePick?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        filePick = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        setUpWeb()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    custom != null -> exitFullscreen()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })

        val restored = savedInstanceState?.getBundle(WEB_STATE)?.let { web.restoreState(it) } != null
        if (!restored) {
            val opened = savedInstanceState == null && handleIntent(intent)
            if (!opened) load(savedInstanceState?.getString(LAST_URL) ?: Routes.HOME)
        }

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
                    val found = withContext(Dispatchers.IO) {
                        runCatching { Updates.check(this@MainActivity, notify = false) }.getOrDefault(emptyList())
                    }
                    if (found.isNotEmpty()) announce(found)
                }
            }
        }
        watchNetwork()
        checkForUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (custom != null) exitFullscreen()
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        if (!webGone) web.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!webGone) web.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webGone) outState.putBundle(WEB_STATE, Bundle().also { web.saveState(it) })
        outState.putString(LAST_URL, lastUrl)
    }

    override fun onDestroy() {
        network?.let { runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        if (!webGone) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ layout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildLayout(): View {
        val teal = ContextCompat.getColor(this, R.color.o_teal)
        val ink = ContextCompat.getColor(this, R.color.o_text)
        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        web = WebView(this)
        refresh = SwipeRefreshLayout(this).apply {
            addView(web, ViewGroup.LayoutParams(-1, -1))
            setColorSchemeColors(teal)
            setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 || custom != null }
            setOnRefreshListener {
                failed = false
                prepareCache()
                web.reload()
            }
        }
        root.addView(refresh, FrameLayout.LayoutParams(-1, -1))

        progress = LinearProgressIndicator(this).apply {
            max = 100
            trackThickness = dp(3)
            setIndicatorColor(teal)
            trackColor = Color.TRANSPARENT
            visibility = View.GONE
        }
        root.addView(progress, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        offlineTitle = TextView(this).apply {
            textSize = 20f
            setTextColor(ink)
            gravity = Gravity.CENTER
            paint.isFakeBoldText = true
        }
        val offlineBody = TextView(this).apply {
            text = "כשהחיבור יחזור, הדף ייטען מעצמו."
            textSize = 15f
            setTextColor(ink)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        }
        val retryButton = MaterialButton(this).apply {
            this.text = "לנסות שוב"
            setBackgroundColor(teal)
            setTextColor(Color.WHITE)
            setOnClickListener { retry() }
        }
        offline = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(32), dp(32), dp(32), dp(32))
            isClickable = true
            visibility = View.GONE
            addView(offlineTitle)
            addView(offlineBody)
            addView(retryButton, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(offline, FrameLayout.LayoutParams(-1, -1))

        fullscreen = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
        }
        root.addView(fullscreen, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    // ------------------------------------------------------------------ the site

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWeb() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            // Lets the site know it is shown in the yeshiva's app (in its statistics, say)
            userAgentString = "$userAgentString OtnielApp/${appVersion()}"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.isVerticalScrollBarEnabled = false

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Pictures, videos and players inside a page load as usual; only whole pages are checked
                if (!request.isForMainFrame) return false
                return !mayOpenHere(request.url.toString())
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                failed = false
                if (Routes.of(url) is Route.Site) lastUrl = url
            }

            override fun onPageFinished(view: WebView, url: String) {
                refresh.isRefreshing = false
                progress.visibility = View.GONE
                if (!failed) offline.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showOffline()
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // The page's engine stopped (Android freed memory): start the window again on the same page
                if (view !== web) return true
                webGone = true
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
                recreate()
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.setProgressCompat(newProgress, true)
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) = enterFullscreen(view, callback)

            override fun onHideCustomView() = exitFullscreen()

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: WebChromeClient.FileChooserParams,
            ): Boolean {
                filePick?.onReceiveValue(null)
                filePick = callback
                return try {
                    pickFiles.launch(params.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    filePick = null
                    false
                }
            }

            // The site needs no camera, microphone or location
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
                callback.invoke(origin, false, false)

            // Without this, a video shows Android's grey placeholder before it starts
            override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        web.setDownloadListener { url, _, disposition, mime, _ ->
            val name = URLUtil.guessFileName(url, disposition, mime)
            when {
                mime.equals("application/pdf", true) || name.endsWith(".pdf", true) ->
                    PdfActivity.open(this, url, name.removeSuffix(".pdf").removeSuffix(".PDF"))
                url.startsWith("https://") || url.startsWith("http://") -> Outside.file(this, url, name)
                else -> Toast.makeText(this, "את הקובץ הזה אי אפשר להוריד מכאן", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** True for pages of otniel.org; anything else is sent on its way and stays out of the window. */
    private fun mayOpenHere(url: String): Boolean {
        val route = Routes.of(url)
        if (route is Route.Site) {
            if (route.url != url) {
                // http:// → https://
                load(route.url)
                return false
            }
            prepareCache()
            return true
        }
        if (url.startsWith("about:")) return url == "about:blank"
        send(route)
        return false
    }

    private fun send(route: Route) {
        when (route) {
            is Route.Site -> load(route.url)
            is Route.Pdf -> PdfActivity.open(this, route.url, PdfActivity.nameOf(route.url))
            is Route.Store -> Outside.store(this, route.url)
            is Route.Phone -> Outside.dial(this, route.uri)
            is Route.Mail -> Outside.mail(this, route.uri)
            is Route.App -> Outside.app(this, route.uri)
            is Route.External -> Outside.website(this, route.url)
            Route.None -> Unit
        }
    }

    private fun load(url: String) {
        failed = false
        lastUrl = url
        prepareCache()
        web.loadUrl(url)
    }

    /** Without internet, pages already seen are shown from the phone. */
    private fun prepareCache() {
        web.settings.cacheMode = if (online()) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
    }

    private fun online(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showOffline() {
        failed = true
        refresh.isRefreshing = false
        progress.visibility = View.GONE
        offlineTitle.text = if (online()) "האתר לא נטען כרגע" else "אין חיבור לאינטרנט"
        offline.visibility = View.VISIBLE
    }

    private fun retry() {
        failed = false
        prepareCache()
        if (web.url.isNullOrBlank() || web.url == "about:blank") load(lastUrl) else web.reload()
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(net: Network) {
                runOnUiThread { if (failed && !isFinishing && !webGone) retry() }
            }
        }
        if (runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess) network = callback
    }

    // ------------------------------------------------------------------ links from outside

    /** A link opened from another app, a notification or shared text. True if a page was loaded. */
    private fun handleIntent(intent: Intent?): Boolean {
        val url = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                Routes.siteLinkIn(text) ?: run {
                    Toast.makeText(this, "אפשר לשתף לכאן קישורים לאתר הישיבה", Toast.LENGTH_LONG).show()
                    null
                }
            }
            else -> null
        } ?: return false
        val route = Routes.of(url)
        if (route is Route.Site) {
            load(route.url)
            return true
        }
        send(route)
        return false
    }

    // ------------------------------------------------------------------ full-screen video

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (custom != null) {
            callback.onCustomViewHidden()
            return
        }
        custom = view
        customDone = callback
        fullscreen.addView(view, FrameLayout.LayoutParams(-1, -1))
        fullscreen.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun exitFullscreen() {
        val view = custom ?: return
        custom = null
        fullscreen.removeView(view)
        fullscreen.visibility = View.GONE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        customDone?.onCustomViewHidden()
        customDone = null
    }

    // ------------------------------------------------------------------ new content and new versions

    private fun announce(found: List<NewItem>) {
        val first = found.first()
        val text = if (found.size == 1) "${first.kind}: ${first.title}" else "נוספו ${found.size} פריטים חדשים באתר"
        Snackbar.make(root, text, Snackbar.LENGTH_LONG)
            .setAction("לצפייה") { send(Routes.of(if (found.size == 1) first.link else Routes.HOME)) }
            .show()
    }

    private fun checkForUpdate() {
        lifecycleScope.launch {
            val release = runCatching { Updater.newer(this@MainActivity, force = false) }.getOrNull() ?: return@launch
            Snackbar.make(root, "יש גרסה חדשה של היישומון", Snackbar.LENGTH_INDEFINITE)
                .setAction("עדכן") { installUpdate(release) }
                .show()
        }
    }

    private fun installUpdate(release: Updater.Release) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "מוריד את הגרסה החדשה…", Toast.LENGTH_SHORT).show()
            val file = runCatching { Updater.download(this@MainActivity, release) {} }.getOrNull()
            if (file == null) {
                Toast.makeText(this@MainActivity, "ההורדה לא הצליחה, נסו שוב מאוחר יותר", Toast.LENGTH_LONG).show()
                return@launch
            }
            Updater.install(this@MainActivity, file)
        }
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }.getOrDefault("")
}
