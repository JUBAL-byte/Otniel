package app.jubal.otniel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A PDF from the site (a source sheet, a lesson summary), shown page by page inside the app. */
class PdfActivity : AppCompatActivity() {
    companion object {
        private const val URL = "url"
        private const val NAME = "name"

        fun open(context: Context, url: String, name: String) {
            context.startActivity(Intent(context, PdfActivity::class.java).putExtra(URL, url).putExtra(NAME, name))
        }

        /** "שיעור-1.pdf" → "שיעור 1" */
        fun nameOf(url: String): String = runCatching { android.net.Uri.decode(url) }.getOrDefault(url)
            .substringBefore('?').substringAfterLast('/').removeSuffix(".pdf").removeSuffix(".PDF")
            .replace('-', ' ').replace('_', ' ').trim()
    }

    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var file: File? = null
    private lateinit var list: RecyclerView
    private lateinit var loading: CircularProgressIndicator
    private lateinit var message: TextView
    private val pages = PageAdapter()

    private val url: String get() = intent.getStringExtra(URL) ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val name = intent.getStringExtra(NAME)?.takeIf { it.isNotBlank() } ?: nameOf(url).ifBlank { "קובץ" }

        val toolbar = MaterialToolbar(this).apply {
            title = name
            setBackgroundColor(Color.WHITE)
            setTitleTextColor(ContextCompat.getColor(context, R.color.o_text))
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = "חזרה"
            setNavigationOnClickListener { finish() }
        }
        setSupportActionBar(toolbar)

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = pages
            setBackgroundColor(ContextCompat.getColor(context, R.color.o_soft))
            clipToPadding = false
            setPadding(0, 0, 0, dp(6))
        }
        loading = CircularProgressIndicator(this).apply {
            isIndeterminate = true
            setIndicatorColor(ContextCompat.getColor(context, R.color.o_teal))
        }
        message = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.o_text))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
        }
        val body = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1))
            addView(loading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(message, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(-1, -2))
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        load()
    }

    private fun load() {
        loading.visibility = View.VISIBLE
        message.visibility = View.GONE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(cacheDir, "pdf").apply { mkdirs() }
                    val f = File(dir, "${url.hashCode().toUInt()}.pdf")
                    if (!f.exists() || f.length() == 0L) {
                        val part = File(dir, f.name + ".part")
                        Net.get(url).use { r ->
                            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                            val b = r.body ?: throw IOException("empty")
                            part.outputStream().use { out -> b.byteStream().copyTo(out) }
                        }
                        if (!part.renameTo(f)) throw IOException("rename")
                    }
                    f
                }
            }
            loading.visibility = View.GONE
            result.onSuccess { f ->
                file = f
                runCatching {
                    val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                    descriptor = d
                    renderer = PdfRenderer(d)
                    pages.notifyDataSetChanged()
                    invalidateOptionsMenu()
                }.onFailure {
                    f.delete()
                    show("לא הצלחנו לפתוח את הקובץ.")
                }
            }.onFailure {
                show(if (it is IOException && it.message?.startsWith("HTTP") != true) "אין חיבור לאינטרנט. נסו שוב כשהחיבור יחזור." else "לא הצלחנו להוריד את הקובץ.")
            }
        }
    }

    private fun show(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (file != null) {
            menu.add(0, 1, 0, "שיתוף").setIcon(R.drawable.ic_share).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, 2, 1, "פתיחה ביישומון אחר").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val f = file ?: return super.onOptionsItemSelected(item)
        val uri = runCatching { FileProvider.getUriForFile(this, "$packageName.files", f) }.getOrNull()
            ?: return super.onOptionsItemSelected(item)
        val intent = when (item.itemId) {
            1 -> Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "שיתוף הקובץ",
            )
            2 -> Intent.createChooser(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "פתיחה ביישומון אחר",
            )
            else -> return super.onOptionsItemSelected(item)
        }
        runCatching { startActivity(intent) }
        return true
    }

    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = renderer?.pageCount ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val img = ImageView(parent.context).apply {
                adjustViewBounds = true
                setBackgroundColor(Color.WHITE)
                contentDescription = "עמוד"
                layoutParams = ViewGroup.MarginLayoutParams(-1, -2).apply { setMargins(dp(6), dp(6), dp(6), 0) }
            }
            return object : RecyclerView.ViewHolder(img) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val img = holder.itemView as ImageView
            img.setImageBitmap(null)
            img.minimumHeight = (list.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) * 4 / 3
            img.contentDescription = "עמוד ${position + 1}"
            val width = (list.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) * 3 / 2
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { render(position, width) } ?: return@launch
                if (holder.bindingAdapterPosition == position) {
                    img.minimumHeight = 0
                    img.setImageBitmap(bmp)
                }
            }
        }
    }

    @Synchronized
    private fun render(index: Int, width: Int): Bitmap? = runCatching {
        val r = renderer ?: return null
        r.openPage(index).use { page ->
            val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }.getOrNull()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        synchronized(this) {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
            renderer = null
        }
        super.onDestroy()
    }
}
