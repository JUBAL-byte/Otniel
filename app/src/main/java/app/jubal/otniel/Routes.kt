package app.jubal.otniel

import java.net.URLDecoder

/** Where a link goes. The app shows only the yeshiva's site; everything else leaves it. */
sealed interface Route {
    /** A page of otniel.org (always as https): shown in the app, exactly as on the site. */
    data class Site(val url: String) : Route

    /** A PDF (a source sheet, a lesson summary): shown page by page in the app. */
    data class Pdf(val url: String) : Route

    /** The bookstore, cart, payment, account and donations: only in the phone's own browser. */
    data class Store(val url: String) : Route

    data class Phone(val uri: String) : Route
    data class Mail(val uri: String) : Route

    /** Another app on the phone (WhatsApp, SMS, maps, an app store). */
    data class App(val uri: String) : Route

    /** Any other website: outside the app, and only after asking. */
    data class External(val url: String) : Route

    /** Nothing to open (javascript:, about:blank, blob:...). */
    data object None : Route
}

object Routes {
    const val HOME = "https://otniel.org/"

    val SITE_HOSTS = setOf("otniel.org", "www.otniel.org")

    /** The site keeps its pictures and files on this address (vangus-cdn.com/otniel.org/...). */
    private const val CDN = "vangus-cdn.com"

    /** Pages that sell, take payment or log in: never inside the app. */
    private val STORE_PAGES = setOf(
        "bookstore", "shop", "product", "product-category", "cart", "checkout", "my-account",
        "donations", "donate", "shvooshim", "wp-login.php", "wp-admin",
    )

    private val APP_SCHEMES = setOf("sms", "smsto", "mms", "whatsapp", "geo", "waze", "market", "intent", "tg")

    // A backslash ends the address part too (as browsers read it), so "evil.example\@otniel.org" is not the site
    private val URL = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(?://([^/?#\\\\]*))?([^?#]*)(?:\\?([^#]*))?(?:#(.*))?$")
    private val LINK = Regex("(?:https?://)?(?:www\\.)?otniel\\.org(?:/[^\\s\"'<>]*)?", RegexOption.IGNORE_CASE)

    fun host(url: String): String? =
        URL.find(url.trim())?.groupValues?.get(2)?.lowercase()?.substringAfterLast('@')?.substringBefore(':')

    fun isSite(url: String): Boolean = host(url) in SITE_HOSTS

    private fun decode(segment: String): String = try {
        URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
        segment
    }

    /** Decides where [raw] goes. Relative links are taken as links on otniel.org. */
    fun of(raw: String): Route {
        var url = raw.trim()
        if (url.isEmpty()) return Route.None
        if (url.startsWith("//")) url = "https:$url"
        if (url.startsWith("/")) url = "https://otniel.org$url"
        val m = URL.find(url) ?: return Route.None
        val scheme = m.groupValues[1].lowercase()
        when (scheme) {
            "http", "https" -> Unit
            "tel" -> return Route.Phone(url)
            "mailto" -> return Route.Mail(url)
            in APP_SCHEMES -> return Route.App(url)
            else -> return Route.None
        }
        val host = m.groupValues[2].lowercase().substringAfterLast('@').substringBefore(':')
        val path = m.groupValues[3]
        val query = m.groupValues[4]
        val secure = if (scheme == "http") "https" + url.substring(4) else url

        if (path.lowercase().endsWith(".pdf")) return Route.Pdf(secure)
        if (host == CDN && path.startsWith("/otniel.org/")) return Route.Site(secure)
        if (host !in SITE_HOSTS) return Route.External(url)

        val first = path.split('/').firstOrNull { it.isNotEmpty() }?.let { decode(it).lowercase() } ?: ""
        if (first in STORE_PAGES || query.contains("add-to-cart")) return Route.Store(secure)
        return Route.Site(secure)
    }

    /** The first link to otniel.org in shared text (a WhatsApp message, say), as https. */
    fun siteLinkIn(text: String): String? {
        val found = LINK.find(text)?.value?.trimEnd('.', ',', ')', '!', '?', ':', ';') ?: return null
        val withScheme = if (found.startsWith("http", ignoreCase = true)) found else "https://$found"
        return withScheme.replaceFirst(Regex("^http:", RegexOption.IGNORE_CASE), "https:")
    }
}
