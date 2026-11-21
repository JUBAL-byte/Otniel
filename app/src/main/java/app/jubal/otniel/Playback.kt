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

/** The app's side of the player: starts things playing and tells screens what is playing. */
object Playback {
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

    fun mediaItem(p: Playable): MediaItem {
        var uri = p.uri
        if (p.isYouTube && (!p.video || Store.audioOnly())) uri = "$uri&a=1"
        val extras = Bundle().apply {
            putString("page", p.page)
            putBoolean("video", p.video)
            putString("uri", p.uri)
        }
        val meta = MediaMetadata.Builder()
            .setTitle(p.title)
            .setArtist(p.subtitle)
            .setDisplayTitle(p.title)
            .setExtras(extras)
        if (p.isYouTube && Store.images()) meta.setArtworkUri(Uri.parse(YouTube.thumbnail(p.videoId)))
        return MediaItem.Builder()
            .setMediaId(p.id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(uri)).build())
            .setMediaMetadata(meta.build())
            .build()
    }

    fun playableOf(item: MediaItem?): Playable? {
        item ?: return null
        val m = item.mediaMetadata
        val extras = m.extras
        val uri = extras?.getString("uri") ?: item.requestMetadata.mediaUri?.toString() ?: return null
        return Playable(
            item.mediaId,
            m.title?.toString() ?: "",
            m.artist?.toString() ?: "",
            uri,
            extras?.getString("page") ?: "",
            extras?.getBoolean("video", true) ?: true,
        )
    }

    fun current(): Playable? = playableOf(controller?.currentMediaItem)

    fun isCurrent(id: String): Boolean = controller?.currentMediaItem?.mediaId == id

    /** Plays [items] from [index], where the listener stopped last time unless [startMs] says otherwise. */
    fun play(context: Context, items: List<Playable>, index: Int = 0, startMs: Long = -1) {
        if (items.isEmpty()) return
        val i = index.coerceIn(0, items.size - 1)
        val first = items[i]
        with(context) { c ->
            if (c.currentMediaItem?.mediaId == first.id && items.size == 1 && startMs < 0) {
                c.play()
                return@with
            }
            val start = if (startMs >= 0) startMs else Store.position(first.id)
            c.setMediaItems(items.map { mediaItem(it) }, i, start)
            c.prepare()
            c.play()
            Store.addRecent(first)
        }
    }

    /** Switches between picture and sound-only for what is playing now (and from now on). */
    fun setAudioOnly(context: Context, on: Boolean) {
        Store.setAudioOnly(on)
        with(context) { c ->
            val p = playableOf(c.currentMediaItem) ?: return@with
            if (!p.isYouTube) return@with
            val index = c.currentMediaItemIndex
            val position = c.currentPosition
            val playing = c.playWhenReady
            c.replaceMediaItem(index, mediaItem(p))
            c.seekTo(index, position)
            c.prepare()
            if (playing) c.play()
        }
    }

    fun setSpeed(context: Context, speed: Float) {
        Store.setSpeed(speed)
        with(context) { it.setPlaybackSpeed(speed) }
    }
}
