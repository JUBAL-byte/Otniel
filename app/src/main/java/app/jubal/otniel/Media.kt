package app.jubal.otniel

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Builds the player's sources. A YouTube video (otniel://yt?v=ID) gets a [YouTubeMediaSource],
 * everything else (podcast episodes, files on the site) plays straight from its address.
 */
@OptIn(UnstableApi::class)
class OtnielSources(context: Context) : MediaSource.Factory {
    private val dataSources = DefaultDataSource.Factory(context, RoutingHttpDataSource.Factory())
    private val fallback = DefaultMediaSourceFactory(dataSources)
    private val progressive = ProgressiveMediaSource.Factory(dataSources)

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        fallback.setDrmSessionManagerProvider(drmSessionManagerProvider)
        progressive.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        fallback.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        progressive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = fallback.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        if (uri != null && uri.scheme == "otniel" && uri.host == "yt") {
            val id = uri.getQueryParameter("v") ?: ""
            val audioOnly = uri.getQueryParameter("a") == "1"
            return YouTubeMediaSource(mediaItem, id, audioOnly, progressive)
        }
        return fallback.createMediaSource(mediaItem)
    }
}

/**
 * Plays one YouTube video. The stream addresses are looked up only when the player gets to it
 * (they expire after a few hours), then played as one file, or as picture + sound together.
 */
@OptIn(UnstableApi::class)
class YouTubeMediaSource(
    private val item: MediaItem,
    private val videoId: String,
    private val audioOnly: Boolean,
    private val progressive: ProgressiveMediaSource.Factory,
) : CompositeMediaSource<Int>() {

    private var handler: Handler? = null
    private var child: MediaSource? = null
    @Volatile private var error: IOException? = null
    private var generation = 0

    override fun getMediaItem(): MediaItem = item

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        val h = Util.createHandlerForCurrentLooper()
        handler = h
        error = null
        val gen = ++generation
        LOOKUP.execute {
            try {
                val streams = YouTube.streams(videoId, audioOnly)
                h.post { if (handler != null && gen == generation) attach(build(streams)) }
            } catch (e: Exception) {
                error = e as? IOException ?: IOException(e)
            }
        }
    }

    private fun source(url: String): MediaSource = progressive.createMediaSource(MediaItem.fromUri(url))

    private fun build(s: YtStreams): MediaSource = when {
        s.muxed != null -> source(s.muxed)
        s.video != null && s.audio != null -> MergingMediaSource(source(s.video), source(s.audio))
        s.audio != null -> source(s.audio)
        else -> throw IllegalStateException("no stream")
    }

    private fun attach(source: MediaSource) {
        child = source
        prepareChildSource(0, source)
    }

    override fun onChildSourceInfoRefreshed(childSourceId: Int, mediaSource: MediaSource, newTimeline: Timeline) {
        refreshSourceInfo(Labeled(newTimeline, item))
    }

    override fun maybeThrowSourceInfoRefreshError() {
        error?.let { throw it }
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        (child ?: throw IllegalStateException("not prepared")).createPeriod(id, allocator, startPositionUs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        child?.releasePeriod(mediaPeriod)
    }

    override fun releaseSourceInternal() {
        super.releaseSourceInternal()
        handler = null
        child = null
        error = null
    }

    /** Keeps the item's own title and id on the timeline (the inner sources only know stream addresses). */
    private class Labeled(timeline: Timeline, private val label: MediaItem) : ForwardingTimeline(timeline) {
        override fun getWindow(windowIndex: Int, window: Timeline.Window, defaultPositionProjectionUs: Long): Timeline.Window {
            super.getWindow(windowIndex, window, defaultPositionProjectionUs)
            window.mediaItem = label
            return window
        }
    }

    companion object {
        private val LOOKUP = Executors.newFixedThreadPool(2)
    }
}
