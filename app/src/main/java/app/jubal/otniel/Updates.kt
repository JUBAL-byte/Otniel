package app.jubal.otniel

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Something that was just added to the site or the channel. */
data class NewItem(val key: String, val kind: String, val title: String, val link: String)

/**
 * Finds what is new on otniel.org and on the yeshiva's YouTube channel, and tells about it.
 * Android lets an app look in the background at most every 15 minutes; while the app is
 * open it looks every two minutes, and every list is fresh from the site when opened.
 */
object Updates {
    const val CHANNEL = "new_content"
    private const val WORK = "otniel_new_content"
    private const val PAGES_EVERY_MS = 6 * 3600_000L

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL, "תוכן חדש באתר", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "שיעור, סרטון, סדרה או פודקאסט חדשים מישיבת עתניאל"
        }
        manager.createNotificationChannel(channel)
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<NewContentWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Looks for new items. The first time it only remembers what is there, so nothing old is announced.
     * Blocks; run on Dispatchers.IO.
     */
    fun check(context: Context, notify: Boolean): List<NewItem> {
        val found = ArrayList<NewItem>()
        val coveredVideos = HashSet<String>()

        runCatching {
            val lessons = Site.newestLessons(8)
            val newest = lessons.maxOfOrNull { it.id } ?: 0
            val last = Store.lastLessonId()
            if (last > 0) {
                for (l in lessons.filter { it.id > last }.sortedBy { it.id }) {
                    val who = Site.rabbiNames(l.rabbis)
                    found += NewItem("l:${l.id}", "שיעור חדש", if (who.isEmpty()) l.title else "${l.title} · $who", l.link)
                    runCatching { Site.lessonMedia(l.link).youtubeId }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { coveredVideos += it }
                }
            }
            if (newest > last) Store.setLastLessonId(newest)
        }

        runCatching {
            val videos = YouTube.rss()
            if (videos.isNotEmpty()) {
                if (Store.hasSeen("videos")) {
                    val seen = Store.seen("videos")
                    for (v in videos.filter { it.id !in seen }.reversed()) {
                        if (v.id in coveredVideos) continue
                        found += NewItem("v:${v.id}", "סרטון חדש בערוץ", v.title, YouTube.watchUrl(v.id))
                    }
                    Store.setSeen("videos", seen + videos.map { it.id })
                } else {
                    Store.setSeen("videos", videos.map { it.id }.toSet())
                }
            }
        }

        if (System.currentTimeMillis() - Store.lastPagesCheck() > PAGES_EVERY_MS) {
            runCatching {
                val series = Site.series()
                if (series.isNotEmpty()) {
                    if (Store.hasSeen("series")) {
                        val seen = Store.seen("series")
                        series.filter { it.key !in seen }.forEach {
                            found += NewItem("s:${it.key}", "סדרה חדשה", it.title, it.playlistUrl.ifBlank { "https://otniel.org/all-vid-series/" })
                        }
                    }
                    Store.setSeen("series", series.map { it.key }.toSet())
                }
            }
            runCatching {
                val shows = Site.shows()
                if (shows.isNotEmpty()) {
                    if (Store.hasSeen("shows")) {
                        val seen = Store.seen("shows")
                        shows.filter { it.key !in seen }.forEach {
                            found += NewItem("p:${it.key}", "פודקאסט חדש", it.title.ifBlank { "הסכת חדש" }, "https://otniel.org/all-podcasts/")
                        }
                    }
                    Store.setSeen("shows", shows.map { it.key }.toSet())
                }
            }
            Store.setLastPagesCheck(System.currentTimeMillis())
        }

        if (notify && Store.notifications()) show(context, found)
        return found
    }

    private fun show(context: Context, items: List<NewItem>) {
        if (items.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = NotificationManagerCompat.from(context)
        for (item in items.take(5)) {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(item.link))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(
                context, item.key.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(item.kind)
                .setContentText(item.title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.title))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setGroup("new")
                .build()
            try {
                manager.notify(item.key.hashCode(), n)
            } catch (e: SecurityException) {
                return
            }
        }
    }
}

class NewContentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { Updates.check(applicationContext, notify = true) }
        Result.success()
    }
}
