package app.jubal.otniel

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Everything that leaves the app goes through here. The app itself shows only otniel.org;
 * other websites, the store and payment open outside it, in the phone's own apps.
 */
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

    /**
     * Opens [url] in an app made for it (YouTube, WhatsApp, Spotify...), if the phone has one.
     * False when only a browser could open it.
     */
    private fun nonBrowserApp(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        if (Build.VERSION.SDK_INT >= 30) {
            return try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER or Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
        }
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
        val browsers = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.toSet()
        val app = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .firstOrNull { it != context.packageName && it !in browsers } ?: return false
        return runCatching { context.startActivity(intent.setPackage(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    /** Another website: straight to its app if there is one, otherwise ask before the browser. */
    fun website(activity: Activity, url: String) {
        if (nonBrowserApp(activity, url)) return
        val b = MaterialAlertDialogBuilder(activity)
            .setTitle("קישור לאתר אחר")
            .setMessage("הקישור הזה מוביל אל מחוץ לאתר הישיבה, ולכן ייפתח בדפדפן של הטלפון:\n\n${shown(url)}")
            .setNegativeButton("ביטול", null)
            .setNeutralButton("העתק קישור") { _, _ -> copy(activity, url) }
        if (browserPackage(activity) != null) b.setPositiveButton("פתח בדפדפן") { _, _ -> browser(activity, url) }
        b.show()
    }

    /** A file the app does not show itself (not a PDF): only outside, after asking. */
    fun file(activity: Activity, url: String, name: String) {
        val b = MaterialAlertDialogBuilder(activity)
            .setTitle("הורדת קובץ")
            .setMessage("את הקובץ \"$name\" אפשר להוריד בדפדפן של הטלפון, מחוץ ליישומון.")
            .setNegativeButton("ביטול", null)
            .setNeutralButton("העתק קישור") { _, _ -> copy(activity, url) }
        if (browserPackage(activity) != null) b.setPositiveButton("פתח בדפדפן") { _, _ -> browser(activity, url) }
        b.show()
    }

    /** The bookstore, cart, payment, account and donations open only in the phone's browser. */
    fun store(activity: Activity, url: String) {
        if (browserPackage(activity) == null) {
            copy(activity, url)
            MaterialAlertDialogBuilder(activity)
                .setTitle("אין דפדפן בטלפון")
                .setMessage("חנות הספרים והתשלום נפתחים רק בדפדפן. לא מצאנו דפדפן בטלפון הזה, אז העתקנו את הקישור:\n\n${shown(url)}")
                .setPositiveButton("הבנתי", null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("מעבר לדפדפן")
            .setMessage("חנות הספרים, התשלום והתרומות נפתחים בדפדפן של הטלפון, מחוץ ליישומון, כדי שפרטי התשלום יישארו רק שם.")
            .setPositiveButton("פתח בדפדפן") { _, _ -> browser(activity, url) }
            .setNegativeButton("ביטול", null)
            .show()
    }

    fun dial(context: Context, uri: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(uri))) }
    }

    fun mail(context: Context, uri: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(uri))) }
            .onFailure { copy(context, Uri.decode(uri.removePrefix("mailto:").substringBefore('?')), "הכתובת הועתקה") }
    }

    /** WhatsApp, SMS, maps and other apps. An intent: link may only reach an app that is meant to be opened from links. */
    fun app(activity: Activity, uri: String) {
        val intent = if (uri.startsWith("intent:", ignoreCase = true)) {
            runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()?.apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        }
        if (intent != null && runCatching { activity.startActivity(intent); true }.getOrDefault(false)) return
        val fallback = intent?.getStringExtra("browser_fallback_url")
        if (!fallback.isNullOrBlank() && fallback.startsWith("https://")) website(activity, fallback)
        else Toast.makeText(activity, "אין בטלפון יישומון שפותח את הקישור הזה", Toast.LENGTH_SHORT).show()
    }

    fun copy(context: Context, text: String, toast: String = "הקישור הועתק") {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("link", text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }

    /** An address as people read it (Hebrew letters instead of %D7%...). */
    private fun shown(url: String): String = runCatching { Uri.decode(url) }.getOrDefault(url).let {
        if (it.length > 120) it.take(117) + "…" else it
    }
}
