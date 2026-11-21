package app.jubal.otniel

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared HTTP client, plus the special way YouTube media files have to be requested. */
object Net {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    /** What the YouTube extractor presents itself as (the same as NewPipe and JUBAL). */
    const val YT_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    /** YouTube's own "Restricted Mode". */
    const val YOUTUBE_RESTRICTED_COOKIE = "PREF=f2=8000000"

    private val POST_BODY = byteArrayOf(0x78, 0)

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun get(url: String): okhttp3.Response =
        client.newCall(
            Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        ).execute()

    /** The body of [url] as text, or an [IOException] when the site answers with an error. */
    fun text(url: String): String = get(url).use { r ->
        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        r.body?.string() ?: throw IOException("empty response")
    }

    /** Text plus one response header (e.g. WordPress's X-WP-TotalPages). */
    fun textWithHeader(url: String, header: String): Pair<String, String?> = get(url).use { r ->
        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        (r.body?.string() ?: "") to r.header(header)
    }

    fun isYouTubeMedia(url: String): Boolean {
        val uri = Uri.parse(url)
        return (uri.host ?: "").endsWith("googlevideo.com") &&
            (uri.path ?: "").startsWith("/videoplayback")
    }

    /**
     * Asks YouTube for one slice of a media file, the same way NewPipe does
     * (POST, range in the URL, a running request number).
     */
    fun youTubeChunk(url: String, start: Long, endInclusive: Long, requestNumber: Int): okhttp3.Response {
        val full = "$url&rn=$requestNumber&range=$start-$endInclusive"
        val agent = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            YT_AGENT
        }
        val builder = Request.Builder()
            .url(full)
            .post(POST_BODY.toRequestBody(null))
            .header("User-Agent", agent)
            .header("Accept-Encoding", "identity")
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            builder.header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "cross-site")
        }
        return client.newCall(builder.build()).execute()
    }
}
