package app.jubal.otniel

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The app's own requests to the site (the "new content" check, PDFs, new versions). */
object Net {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36 OtnielApp"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun get(url: String): okhttp3.Response =
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()

    /** The body of [url] as text, or an [IOException] when the site answers with an error. */
    fun text(url: String): String = get(url).use { r ->
        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        r.body?.string() ?: throw IOException("empty response")
    }
}
