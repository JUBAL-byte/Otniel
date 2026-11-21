package app.jubal.otniel

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Plays lessons, videos and podcast episodes, also in the background with the screen off.
 * The app's screens control it through a MediaController (see [Playback]).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private var retriedId: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Start after one second of media instead of the default two and a half
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_500)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(OtnielSources(this))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player.setPlaybackSpeed(Store.speed())

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                savePosition()
                handler.removeCallbacks(ticker)
                if (isPlaying) handler.postDelayed(ticker, 3_000)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                retriedId = null
                val p = Playback.playableOf(mediaItem) ?: return
                Store.addRecent(p)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() }?.let { Store.markDone(it) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // A YouTube link can expire mid-way. Try once more with a fresh one.
                val item = player.currentMediaItem ?: return
                val id = item.mediaId
                if (id.startsWith("yt:") && retriedId != id) {
                    retriedId = id
                    YouTube.invalidate(id.removePrefix("yt:"))
                    val position = player.currentPosition
                    player.prepare()
                    player.seekTo(position)
                    player.play()
                }
            }
        })

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_NOW_PLAYING)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val built = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>,
                ): ListenableFuture<List<MediaItem>> {
                    // The address travels in requestMetadata (the session drops the uri on the way)
                    val resolved = mediaItems.map { item ->
                        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                        if (uri == null) item else item.buildUpon().setUri(uri).build()
                    }
                    return Futures.immediateFuture(resolved)
                }
            })
            .build()
        session = built
        addSession(built)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private fun savePosition() {
        val id = player.currentMediaItem?.mediaId ?: return
        if (id.isEmpty()) return
        val position = player.currentPosition
        val duration = player.duration
        if (duration > 0 && position > duration - 15_000) {
            Store.markDone(id)
        } else if (position > 5_000) {
            Store.savePosition(id, position, if (duration > 0) duration else 0)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
