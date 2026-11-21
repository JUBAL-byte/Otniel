package app.jubal.otniel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TopAction(val icon: Int, val label: String, val onClick: () -> Unit)

/** One page of the app. The activity keeps a stack of these for each bottom tab. */
abstract class Screen(val act: MainActivity) {
    abstract val title: String
    open fun actions(): List<TopAction> = emptyList()

    /** True when this screen shows the picture of [mediaId] itself (no need for the mini player). */
    open fun showsPlayerFor(mediaId: String): Boolean = false

    private var root: View? = null
    val view: View get() = root ?: build().also { root = it }
    val isBuilt: Boolean get() = root != null
    protected abstract fun build(): View

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val ctx: Context get() = act

    open fun onShow() {}
    open fun onHide() {}
    open fun destroy() {
        scope.cancel()
    }

    /** Runs [work] off the main thread, then [done] on it (only while the screen is alive). */
    fun <T> io(work: () -> T, done: (Result<T>) -> Unit): Job = scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { work() } }
        done(r)
    }
}

/** A screen that is a scrolling list of rows, with pull-to-refresh. */
abstract class ListScreen(act: MainActivity) : Screen(act) {
    protected val adapter = RowAdapter()
    protected lateinit var list: RecyclerView
    protected lateinit var refresher: SwipeRefreshLayout

    override fun build(): View {
        refresher = SwipeRefreshLayout(act).apply {
            setColorSchemeColors(act.color(R.color.o_primary))
            setProgressBackgroundColorSchemeColor(act.color(R.color.o_surface_container_high))
        }
        list = RecyclerView(act).apply {
            layoutManager = LinearLayoutManager(act)
            adapter = this@ListScreen.adapter
            clipToPadding = false
            setPadding(0, act.dp(4), 0, act.dp(24))
            itemAnimator = null
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val lm = recyclerView.layoutManager as LinearLayoutManager
                    if (lm.findLastVisibleItemPosition() >= this@ListScreen.adapter.itemCount - 4) onNearEnd()
                }
            })
        }
        refresher.addView(list)
        refresher.setOnRefreshListener { load(force = true) }
        load(force = false)
        return refresher
    }

    abstract fun load(force: Boolean)

    open fun onNearEnd() {}

    fun show(rows: List<Row>) {
        adapter.submit(rows)
    }

    fun doneRefreshing() {
        if (::refresher.isInitialized) refresher.isRefreshing = false
    }

    fun errorRow(e: Throwable?, retry: () -> Unit): Row =
        MessageRow(errorText(e), "לנסות שוב", retry)
}

fun errorText(e: Throwable?): String = when (e) {
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
        "אין חיבור לאינטרנט כרגע. בדוק את החיבור ונסה שוב."
    else -> "לא הצלחנו לטעון מהאתר כרגע. נסה שוב בעוד רגע."
}

/** Everything that leaves the app goes through here, and never to a browser inside the app. */
object Outside {
    /** The phone's own browser (not this app, which also opens otniel.org links), or null. */
    private fun browserPackage(context: Context): String? {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
        val flags = if (Build.VERSION.SDK_INT >= 23) PackageManager.MATCH_ALL else 0
        val all = pm.queryIntentActivities(probe, flags).map { it.activityInfo.packageName }.filter { it != context.packageName }
        val preferred = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return preferred?.takeIf { it in all } ?: all.firstOrNull()
    }

    fun hasBrowser(context: Context): Boolean = browserPackage(context) != null

    /** Opens [url] in the phone's browser, outside the app. False if the phone has no browser. */
    fun browser(context: Context, url: String): Boolean {
        val pkg = browserPackage(context) ?: return false
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    fun copy(context: Context, text: String, toast: String = "הקישור הועתק") {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("link", text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }

    fun share(context: Context, title: String, url: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, if (title.isBlank()) url else "$title\n$url")
        context.startActivity(Intent.createChooser(send, "שיתוף"))
    }

    fun dial(context: Context, uri: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(uri))) }
    }

    fun mail(context: Context, uri: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(uri))) }
            .onFailure { copy(context, uri.removePrefix("mailto:"), "הכתובת הועתקה") }
    }

    fun hasSpotify(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.spotify.music", 0)
        true
    }.getOrDefault(false)

    fun spotify(context: Context, url: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.spotify.music").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
