package com.opentv.notifspammer

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
 * notifications themselves go on a high-importance channel so they
 * actually show up.
 *
 * Two paths per notification:
 * - Normal: NotificationManager.notify(id, notification) from the
 *   app. Works everywhere, but Android rate-limits how fast one
 *   app can post, so very fast / very large runs may get throttled.
 * - Root (when "Use root" is on and a su binary is present): each
 *   post goes through `su -c "cmd notification post …"`, which
 *   posts as the shell and bypasses the app's rate limit, so it can
 *   spam faster. Each root post gets its own tag (spam-1, spam-2,
 *   …) so they stack. If a root post fails — e.g. Android 7 has no
 *   `cmd notification` command at all — that notification falls
 *   back to the normal path instead of stopping the run.
 */
class SpamService : Service() {

    companion object {
        const val ACTION_START = "com.opentv.notifspammer.START"
        const val ACTION_STOP = "com.opentv.notifspammer.STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_COUNT = "count"
        const val EXTRA_DELAY = "delay"

        /** Root mode: post each notification via `su -c "cmd
         * notification post …"` instead of NotificationManager.
         * Set by MainActivity when the user turns "Use root" on
         * (and a su binary is present). */
        const val EXTRA_USE_ROOT = "useRoot"

        /** Progress broadcast: count / total. */
        const val BROADCAST_PROGRESS = "com.opentv.notifspammer.PROGRESS"
        const val EXTRA_POSTED = "posted"
        const val EXTRA_TOTAL = "total"

        /** Finished / stopped broadcast: a human message. */
        const val BROADCAST_DONE = "com.opentv.notifspammer.DONE"
        const val EXTRA_MESSAGE_DONE = "message"

        private const val CHANNEL_ID = "spam"
        private const val SERVICE_CHANNEL_ID = "spam-service"
        private const val SERVICE_NOTIF_ID = 1

        /** Spam notifications start here so they never collide with
         * the service's own notification (id 1). */
        private const val SPAM_ID_BASE = 1000

        private const val ROOT_TAG_PREFIX = "spam"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var targetCount = 0
    private var postedCount = 0
    private var delayMs = 0L
    private var title = ""
    private var message = ""
    private var useRoot = false

    private val postStep = object : Runnable {
        override fun run() {
            if (!running) return
            if (postedCount >= targetCount) {
                finishRun("Done — posted $postedCount of $targetCount")
                return
            }
            val index = postedCount + 1
            postOne(index)
            postedCount++
            updateProgress()
            if (postedCount >= targetCount) {
                finishRun("Done — posted $postedCount of $targetCount")
            } else {
                handler.postDelayed(postStep, delayMs)
            }
        }
    }

    /** Post notification number [index] (1-based). Root first if
     * enabled; fall back to the normal path when the root post
     * fails, so one bad post never kills the run. */
    private fun postOne(index: Int) {
        if (useRoot) {
            // Root post runs on a background thread so the su exec
            // can't block the main thread for long. Join with a
            // timeout: the first su call can hang on SuperSU's
            // "grant?" prompt, and an unbounded join would freeze
            // the app. On timeout (or any failure) fall back to the
            // normal path for this notification.
            var ok = false
            val t = Thread {
                ok = RootHelper.postNotification(title, "$ROOT_TAG_PREFIX-$index", message)
            }
            t.start()
            t.join(5000)
            if (ok) return
            // Root post failed or timed out (e.g. no `cmd
            // notification` on Android 7) — fall through to the
            // normal path.
        }
        postNormal(index)
    }

    private fun postNormal(index: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureSpamChannel(nm)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher)
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
                // Root mode only actually runs as root when a su
                // binary is present; otherwise fall back to the
                // normal path for this run.
                useRoot = intent.getBooleanExtra(EXTRA_USE_ROOT, false) &&
                    RootHelper.isRootAvailable()
                if (targetCount < 1 || title.isEmpty() && message.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                postedCount = 0
                running = true
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
            )
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
