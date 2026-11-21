package app.jubal.otniel

import org.jsoup.parser.Parser
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class YtVideo(val id: String, val title: String, val uploader: String, val durationSec: Long, val date: String = "")

/** Where a video's picture and sound are. [muxed] has both; otherwise [video] and [audio] are played together. */
data class YtStreams(val muxed: String?, val video: String?, val audio: String?)

/** The yeshiva's YouTube videos, through NewPipe's extractor: played inside the app, no YouTube app or browser. */
object YouTube {
    const val CHANNEL = "https://www.youtube.com/user/mediaotniel"

    /** The tallest picture worth fetching on this phone (set from the screen size). */
    @Volatile var maxHeight = 720

    private class Cached(val info: StreamInfo, val time: Long)

    private val infos = ConcurrentHashMap<String, Cached>()
    private val lists = ConcurrentHashMap<String, Pair<Long, Pair<String, List<YtVideo>>>>()
    private const val CACHE_MS = 2 * 3600_000L
    private const val LIST_MS = 10 * 60_000L

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    fun thumbnail(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    private fun info(id: String): StreamInfo {
        infos[id]?.let { if (System.currentTimeMillis() - it.time < CACHE_MS) return it.info }
        val info = StreamInfo.getInfo(ServiceList.YouTube, watchUrl(id))
        infos[id] = Cached(info, System.currentTimeMillis())
        return info
    }

    fun invalidate(id: String) {
        infos.remove(id)
    }

    /** YouTube refused an old link: forget it, so the retry asks for a fresh one. */
    fun invalidateUrl(url: String) {
        infos.entries.removeAll { (_, c) ->
            c.info.audioStreams.any { it.content == url } ||
                c.info.videoStreams.any { it.content == url } ||
                c.info.videoOnlyStreams.any { it.content == url }
        }
    }

    private fun convert(items: List<InfoItem>): List<YtVideo> = items.mapNotNull { item ->
        val s = item as? StreamInfoItem ?: return@mapNotNull null
        if (s.streamType == StreamType.LIVE_STREAM || s.streamType == StreamType.AUDIO_LIVE_STREAM) return@mapNotNull null
        val id = Links.youtubeId(s.url ?: "") ?: return@mapNotNull null
        YtVideo(id, s.name ?: "", s.uploaderName ?: "", s.duration, s.textualUploadDate ?: "")
    }

    /** A playlist's name and videos (a few hundred at most). */
    fun playlist(url: String): Pair<String, List<YtVideo>> {
        lists[url]?.let { if (System.currentTimeMillis() - it.first < LIST_MS) return it.second }
        val service = ServiceList.YouTube
        val info = PlaylistInfo.getInfo(service, url)
        val out = ArrayList<YtVideo>()
        out += convert(info.relatedItems)
        var page = info.nextPage
        var hasNext = info.hasNextPage()
        var guard = 0
        while (hasNext && page != null && guard++ < 8) {
            val more = PlaylistInfo.getMoreItems(service, url, page)
            out += convert(more.items)
            page = more.nextPage
            hasNext = more.hasNextPage()
        }
        val result = (info.name ?: "") to out.distinctBy { it.id }
        lists[url] = System.currentTimeMillis() to result
        return result
    }

    /** The newest videos on the yeshiva's channel. */
    fun channelLatest(): List<YtVideo> {
        lists[CHANNEL]?.let { if (System.currentTimeMillis() - it.first < LIST_MS) return it.second.second }
        val service = ServiceList.YouTube
        val info = ChannelInfo.getInfo(service, CHANNEL)
        if (!info.id.isNullOrBlank()) Store.setChannelId(info.id)
        val tab = info.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) }
            ?: throw IOException("No videos tab")
        val videos = convert(ChannelTabInfo.getInfo(service, tab).relatedItems)
        lists[CHANNEL] = System.currentTimeMillis() to ("" to videos)
        return videos
    }

    /** The channel's id (UC...), needed for its light RSS feed. */
    fun channelId(): String {
        Store.channelId().takeIf { it.isNotBlank() }?.let { return it }
        val id = ChannelInfo.getInfo(ServiceList.YouTube, CHANNEL).id ?: ""
        if (id.isNotBlank()) Store.setChannelId(id)
        return id
    }

    private val ENTRY = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
    private val VID = Regex("<yt:videoId>([\\w-]{11})</yt:videoId>")
    private val TITLE = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)

    /** The channel's newest videos from its RSS feed: a few KB, good for background checks. */
    fun rss(): List<YtVideo> {
        val id = channelId()
        if (id.isBlank()) return emptyList()
        val xml = Net.text("https://www.youtube.com/feeds/videos.xml?channel_id=$id")
        return ENTRY.findAll(xml).mapNotNull { e ->
            val body = e.groupValues[1]
            val vid = VID.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = Parser.unescapeEntities(TITLE.find(body)?.groupValues?.get(1)?.trim() ?: "", false)
            YtVideo(vid, title, "", 0)
        }.toList()
    }

    fun video(id: String): YtVideo {
        val i = info(id)
        return YtVideo(id, i.name ?: "", i.uploaderName ?: "", i.duration, i.textualUploadDate ?: "")
    }

    fun description(id: String): String {
        val d: Description = info(id).description ?: return ""
        val text = d.content ?: return ""
        return if (d.type == Description.Type.HTML) SiteParse.clean(text.replace("<br>", "\n")) else text.trim()
    }

    /** Picks the streams to play: sound only, one file with both, or picture + sound side by side. */
    fun streams(id: String, audioOnly: Boolean): YtStreams {
        val info = info(id)
        val audio = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .maxByOrNull { score(it) }?.content
        if (audioOnly) {
            if (audio != null) return YtStreams(null, null, audio)
            throw IOException("No audio stream")
        }
        val limit = maxHeight.coerceIn(240, 1080)
        val muxed = info.videoStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && height(it) in 1..limit }
            .sortedWith(compareByDescending<VideoStream> { height(it) }.thenByDescending { if (it.format == MediaFormat.MPEG_4) 1 else 0 })
            .firstOrNull()?.content
        val videoOnly = info.videoOnlyStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && height(it) in 1..limit }
            .sortedWith(compareByDescending<VideoStream> { height(it) }.thenByDescending { if (it.format == MediaFormat.MPEG_4) 1 else 0 })
            .firstOrNull()?.content
        // One file with picture and sound is the most reliable; use it unless it is much smaller than the screen
        val muxedHeight = muxed?.let { heightOf(info.videoStreams, it) } ?: 0
        if (muxed != null && (muxedHeight >= minOf(360, limit) || videoOnly == null || audio == null)) {
            return YtStreams(muxed, null, null)
        }
        if (videoOnly != null && audio != null) return YtStreams(null, videoOnly, audio)
        if (muxed != null) return YtStreams(muxed, null, null)
        if (audio != null) return YtStreams(null, null, audio)
        throw IOException("No playable stream")
    }

    private fun heightOf(streams: List<VideoStream>, url: String): Int = streams.firstOrNull { it.content == url }?.let { height(it) } ?: 0

    /** "720p", "480p60" → 720, 480 */
    private fun height(s: VideoStream): Int =
        Regex("(\\d{3,4})p").find(s.resolution ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun score(stream: AudioStream): Int {
        var score = 0
        val type = stream.audioTrackType
        if (type == null || type == AudioTrackType.ORIGINAL) score += 1000
        if (stream.format == MediaFormat.M4A) score += 100
        val bitrate = stream.averageBitrate
        if (bitrate in 1..170) score += bitrate / 10
        return score
    }
}
