package com.opentv.settingsrepeater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlin.random.Random

/**
 * Runs the repeat loop as a foreground service, so Android treats it
 * as work the user can see and doesn't kill it mid-run. That's the
 * part that keeps the app alive: a Device Admin (or any other
 * privilege) on its own does NOT stop Android killing a background
 * process, but a foreground service with a visible notification
 * does. The service also asks to ignore battery optimisations, so
 * Doze / App Standby can't throttle it either.
 *
 * The loop is the same as it was in MainActivity: fire the Settings
 * Intent X times, Y ms apart, picking a random page each step in
 * random mode. Progress goes back to the Activity as a broadcast,
 * and into the notification so the user can see it while a Settings
 * page is on screen.
 */
class RepeatService : Service() {

    companion object {
        const val ACTION_START = "com.opentv.settingsrepeater.START"
        const val ACTION_STOP = "com.opentv.settingsrepeater.STOP"
        const val EXTRA_TIMES = "times"
        const val EXTRA_DELAY = "delay"
        const val EXTRA_RANDOM = "random"
        const val EXTRA_ACTION = "action"
        const val EXTRA_LABEL = "label"

        /** Root mode: open each page via `su -c "am start …"` instead
         * of a normal Intent. Set by MainActivity when the user
         * turns "Use root" on (and a su binary is present). */
        const val EXTRA_USE_ROOT = "useRoot"

        /** Progress broadcast: count / total / page label. */
        const val BROADCAST_PROGRESS = "com.opentv.settingsrepeater.PROGRESS"
        const val EXTRA_COUNT = "count"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_PAGE_LABEL = "pageLabel"

        /** Finished / stopped broadcast: a human message. */
        const val BROADCAST_DONE = "com.opentv.settingsrepeater.DONE"
        const val EXTRA_MESSAGE = "message"

        private const val CHANNEL_ID = "repeat"
        private const val NOTIF_ID = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var targetCount = 0
    private var openedCount = 0
    private var delayMs = 0L
    private var randomMode = false
    private var currentAction = ""
    private var currentLabel = ""
    private var useRoot = false

    private val openStep = object : Runnable {
        override fun run() {
            if (!running) return
            if (openedCount >= targetCount) {
                finishRun("Done — opened $openedCount of $targetCount")
                return
            }
            if (randomMode) {
                val page = SettingsPages.openablePages.let {
                    it[Random.nextInt(it.size)]
                }
                currentAction = page.action!!
                currentLabel = page.label
            }
            if (useRoot) {
                // Root launch: `su -c "am start -a <action>"`, on a
                // background thread so the su exec can't block the
                // main thread. The count advances back on the main
                // thread when it finishes. A root shell starts the
                // Settings page even while the app is in the
                // background — the launch doesn't depend on the app
                // being in the foreground at all.
                Thread {
                    val ok = RootHelper.openSettingsPage(currentAction)
                    handler.post {
                        if (!running) return@post
                        if (!ok) {
                            finishRun("Stopped — root couldn't open $currentLabel")
                            return@post
                        }
                        afterOpen()
                    }
                }.start()
            } else {
                try {
                    startActivity(
                        Intent(currentAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: ActivityNotFoundException) {
                    finishRun("Stopped — couldn't open $currentLabel on this device")
                    return
                }
                afterOpen()
            }
        }
    }

    /** Shared tail of one open: count it, report progress, and
     * either finish or schedule the next step after the delay. */
    private fun afterOpen() {
        openedCount++
        updateProgress()
        if (openedCount >= targetCount) {
            finishRun("Done — opened $openedCount of $targetCount")
        } else {
            handler.postDelayed(openStep, delayMs)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finishRun("Stopped at $openedCount of $targetCount")
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (running) return START_NOT_STICKY
                targetCount = intent.getIntExtra(EXTRA_TIMES, 0)
                delayMs = intent.getLongExtra(EXTRA_DELAY, 0L)
                randomMode = intent.getBooleanExtra(EXTRA_RANDOM, false)
                currentAction = intent.getStringExtra(EXTRA_ACTION).orEmpty()
                currentLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty()
                // Root mode only actually runs as root when a su
                // binary is present; otherwise fall back to the
                // normal Intent path for this run.
                useRoot = intent.getBooleanExtra(EXTRA_USE_ROOT, false) &&
                    RootHelper.isRootAvailable()
                if (targetCount < 1 || currentAction.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                openedCount = 0
                running = true
                startForeground(NOTIF_ID, buildNotification("Starting…"))
                handler.post(openStep)
            }
        }
        // Don't restart if the system does kill us; the run is over.
        return START_NOT_STICKY
    }

    private fun updateProgress() {
        val text = "Opened $openedCount of $targetCount — $currentLabel"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
        sendBroadcast(
            Intent(BROADCAST_PROGRESS)
                .putExtra(EXTRA_COUNT, openedCount)
                .putExtra(EXTRA_TOTAL, targetCount)
                .putExtra(EXTRA_PAGE_LABEL, currentLabel)
        )
    }

    private fun finishRun(message: String) {
        handler.removeCallbacks(openStep)
        running = false
        sendBroadcast(Intent(BROADCAST_DONE).putExtra(EXTRA_MESSAGE, message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Settings Repeater",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Settings Repeater")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(openStep)
        running = false
        super.onDestroy()
    }
}
