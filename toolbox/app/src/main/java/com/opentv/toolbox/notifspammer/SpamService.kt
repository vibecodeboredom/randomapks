package com.opentv.toolbox.notifspammer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Posts a bunch of notifications on demand: X of them, Y ms apart,
 * each with its own ID so they stack in the notification shade
 * instead of replacing each other.
 *
 * Runs as a foreground service, so Android treats it as work the
 * user can see and doesn't kill it mid-run. The service's own
 * notification is low-importance (it just shows progress); the spam
 * notifications themselves go on a high-importance channel
 * ("spam-v2") so they actually show, peek, and make sound.
 *
 * 1.0.1: the spam posts used to go out via root (`su -c "cmd
 * notification post …"`) when "Use root" was on, to bypass the
 * app's notification rate limit. That path is gone: `cmd
 * notification post` takes no channel argument (verified live on
 * the A16 — `-c` is the content-intent flag, not a channel), so
 * its posts always land on the shell's own channel and come out
 * silent and buried in the shade — the user heard no sound and
 * saw no notifications. Every post now goes through the app's own
 * NotificationManager on the high-importance channel, so they
 * sound and show. The channel is created when a run starts, so it
 * exists before the first post.
 */
class SpamService : Service() {

    companion object {
        const val ACTION_START = "com.opentv.toolbox.notifspammer.START"
        const val ACTION_STOP = "com.opentv.toolbox.notifspammer.STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_COUNT = "count"
        const val EXTRA_DELAY = "delay"

        /** Progress broadcast: count / total. */
        const val BROADCAST_PROGRESS = "com.opentv.toolbox.notifspammer.PROGRESS"
        const val EXTRA_POSTED = "posted"
        const val EXTRA_TOTAL = "total"

        /** Finished / stopped broadcast: a human message. */
        const val BROADCAST_DONE = "com.opentv.toolbox.notifspammer.DONE"
        const val EXTRA_MESSAGE_DONE = "message"

        /**
         * Spam posts channel. "spam-v2" (not "spam"): once a
         * channel exists Android won't let the app change its
         * importance, so a new ID is the only way to get a fresh
         * high-importance channel on a device that already has the
         * old one. HIGH = makes sound and peeks/shows.
         */
        private const val CHANNEL_ID = "spam-v2"
        private const val SERVICE_CHANNEL_ID = "spam-service"
        private const val SERVICE_NOTIF_ID = 1

        /** Spam notifications start here so they never collide with
         * the service's own notification (id 1). */
        private const val SPAM_ID_BASE = 1000
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var targetCount = 0
    private var postedCount = 0
    private var delayMs = 0L
    private var title = ""
    private var message = ""

    // Explicit type (not inferred): the Runnable's run() schedules
    // itself with postDelayed(postStep, …), and letting the compiler
    // infer the property type from its own initializer makes type
    // checking run into a recursive problem.
    private val postStep: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (postedCount >= targetCount) {
                finishRun("Done — posted $postedCount of $targetCount")
                return
            }
            val index = postedCount + 1
            postNormal(index)
            postedCount++
            updateProgress()
            if (postedCount >= targetCount) {
                finishRun("Done — posted $postedCount of $targetCount")
            } else {
                handler.postDelayed(postStep, delayMs)
            }
        }
    }

    /** Post notification number [index] (1-based) on the
     * high-importance channel, so it makes sound and shows. Each
     * gets its own ID so the posts stack in the shade. */
    private fun postNormal(index: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureSpamChannel(nm)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(SPAM_ID_BASE + index, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finishRun("Stopped at $postedCount of $targetCount")
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (running) return START_NOT_STICKY
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
                targetCount = intent.getIntExtra(EXTRA_COUNT, 0)
                delayMs = intent.getLongExtra(EXTRA_DELAY, 0L)
                if (targetCount < 1 || title.isEmpty() && message.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                postedCount = 0
                running = true
                // Create the spam channel up front, before the
                // first post, so it exists no matter what.
                ensureSpamChannel(
                    getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                )
                startForeground(SERVICE_NOTIF_ID, buildServiceNotification("Starting…"))
                handler.post(postStep)
            }
        }
        // Don't restart if the system does kill us; the run is over.
        return START_NOT_STICKY
    }

    private fun updateProgress() {
        val text = "Posted $postedCount of $targetCount"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(SERVICE_NOTIF_ID, buildServiceNotification(text))
        sendBroadcast(
            Intent(BROADCAST_PROGRESS)
                .putExtra(EXTRA_POSTED, postedCount)
                .putExtra(EXTRA_TOTAL, targetCount)
        )
    }

    private fun finishRun(message: String) {
        handler.removeCallbacks(postStep)
        running = false
        sendBroadcast(Intent(BROADCAST_DONE).putExtra(EXTRA_MESSAGE_DONE, message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureSpamChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Notification Spam",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                // Explicitly the default notification sound (and
                // vibration): HIGH on its own should already sound,
                // but a channel created without a sound set can end
                // up silent on some builds.
                setSound(
                    android.media.RingtoneManager.getDefaultUri(
                        android.media.RingtoneManager.TYPE_NOTIFICATION
                    ),
                    null
                )
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildServiceNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Notification Spammer",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("Notification Spammer")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(postStep)
        running = false
        super.onDestroy()
    }
}
