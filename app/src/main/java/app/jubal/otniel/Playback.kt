package app.jubal.otniel

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/** The app's side of the player: starts episodes and tells the screens what is playing. */
object Playback {
    const val ACTION_NOW_PLAYING = "app.jubal.otniel.NOW_PLAYING"

    var controller: MediaController? = null
        private set
    private var connecting = false
    private val waiting = ArrayList<(MediaController) -> Unit>()
    private val listeners = LinkedHashSet<Player.Listener>()

    fun connect(context: Context) {
        if (controller != null || connecting) return
        connecting = true
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            listeners.forEach { c.addListener(it) }
            val run = waiting.toList()
            waiting.clear()
            run.forEach { it(c) }
        }, ContextCompat.getMainExecutor(app))
    }

    fun with(context: Context, block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            waiting += block
            connect(context)
        }
    }

    fun addListener(listener: Player.Listener) {
        listeners += listener
        controller?.addListener(listener)
    }

    fun removeListener(listener: Player.Listener) {
        listeners -= listener
        controller?.removeListener(listener)
    }

    private fun item(podcast: PodcastRef, podcastTitle: String, image: String, e: Episode): MediaItem {
        val extras = Bundle().apply {
            putString("feed", podcast.feedUrl)
            putString("title", podcast.title)
            putString("presenter", podcast.presenter)
            putString("image", podcast.image)
            putString("spotify", podcast.spotify)
            putString("apple", podcast.apple)
            putString("youtube", podcast.youtube)
        }
        val meta = MediaMetadata.Builder()
            .setTitle(e.title)
            .setDisplayTitle(e.title)
            .setArtist(podcastTitle)
            .setAlbumTitle(podcastTitle)
            .setExtras(extras)
        if (image.isNotBlank()) meta.setArtworkUri(Uri.parse(image))
        val uri = Uri.parse(e.audioUrl)
        return MediaItem.Builder()
            .setMediaId(e.id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(meta.build())
            .build()
    }

    /** Plays one episode, from where the listener stopped last time. */
    fun play(context: Context, podcast: PodcastRef, podcastTitle: String, image: String, e: Episode) {
        with(context) { c ->
            if (c.currentMediaItem?.mediaId == e.id) {
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
                return@with
            }
            val start = if (Store.heard(e.id) >= 1f) 0L else Store.position(e.id)
            c.setMediaItem(item(podcast, podcastTitle, image, e), start)
            c.setPlaybackSpeed(Store.speed())
            c.prepare()
            c.play()
        }
    }

    /** The podcast of what is playing now (to open its page). */
    fun currentPodcast(): PodcastRef? {
        val extras = controller?.currentMediaItem?.mediaMetadata?.extras ?: return null
        val feed = extras.getString("feed").orEmpty()
        if (feed.isBlank()) return null
        return PodcastRef(
            feed,
            extras.getString("title").orEmpty(),
            extras.getString("presenter").orEmpty(),
            extras.getString("image").orEmpty(),
            extras.getString("spotify").orEmpty(),
            extras.getString("apple").orEmpty(),
            extras.getString("youtube").orEmpty(),
        )
    }

    fun setSpeed(context: Context, speed: Float) {
        Store.setSpeed(speed)
        with(context) { it.setPlaybackSpeed(speed) }
    }

    fun stop() {
        controller?.run {
            stop()
            clearMediaItems()
        }
    }
}
