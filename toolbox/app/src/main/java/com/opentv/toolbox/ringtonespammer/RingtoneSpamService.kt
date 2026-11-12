package com.opentv.toolbox.ringtonespammer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Plays a ringtone on demand: X times, Y ms apart.
 *
 * Runs as a foreground service, so Android treats it as work the
 * user can see and doesn't kill it mid-run. The service's own
 * notification is low-importance (it just shows progress); the
 * sound itself is the ringtone the user picked, played through
 * RingtoneManager. Each step stops the previous play first, so a
 * long ringtone doesn't stack on top of itself when the delay is
 * short.
 *
 * A play that fails (a sound URI the device can't read any more,
 * for example) doesn't stop the run — it counts as a failed play
 * and the done message says how many failed.
 */
class RingtoneSpamService : Service() {

    companion object {
        const val ACTION_START = "com.opentv.toolbox.ringtonespammer.START"
        const val ACTION_STOP = "com.opentv.toolbox.ringtonespammer.STOP"
        const val EXTRA_SOUND_URI = "soundUri"
        const val EXTRA_COUNT = "count"
        const val EXTRA_DELAY = "delay"

        /** Progress broadcast: count / total. */
        const val BROADCAST_PROGRESS = "com.opentv.toolbox.ringtonespammer.PROGRESS"
        const val EXTRA_PLAYED = "played"
        const val EXTRA_TOTAL = "total"

        /** Finished / stopped broadcast: a human message. */
        const val BROADCAST_DONE = "com.opentv.toolbox.ringtonespammer.DONE"
        const val EXTRA_MESSAGE_DONE = "message"

        private const val SERVICE_CHANNEL_ID = "ringtone-service"
        private const val SERVICE_NOTIF_ID = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var targetCount = 0
    private var playedCount = 0
    private var failedCount = 0
    private var delayMs = 0L
    private var soundUri: Uri? = null
    private var currentRingtone: Ringtone? = null

    // Explicit type (not inferred): the Runnable's run() schedules
    // itself with postDelayed(playStep, …), and letting the compiler
    // infer the property type from its own initializer makes type
    // checking run into a recursive problem.
    private val playStep: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (playedCount >= targetCount) {
                finishRun(doneMessage())
                return
            }
            playOnce()
            playedCount++
            updateProgress()
            if (playedCount >= targetCount) {
                finishRun(doneMessage())
            } else {
                handler.postDelayed(playStep, delayMs)
            }
        }
    }

    /** Play the sound once, stopping the previous play first. A
     * failure counts as a failed play and never stops the run. */
    private fun playOnce() {
        try {
            currentRingtone?.stop()
        } catch (e: Exception) {
            // Nothing playing, or it already stopped.
        }
        currentRingtone = null
        try {
            val uri = soundUri
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(this, uri)
            if (ringtone == null) {
                failedCount++
                return
            }
            currentRingtone = ringtone
            ringtone.play()
        } catch (e: Exception) {
            failedCount++
        }
    }

    private fun doneMessage(): String {
        val base = "Done — played $playedCount of $targetCount"
        return if (failedCount > 0) "$base ($failedCount couldn't play)" else base
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finishRun("Stopped at $playedCount of $targetCount")
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (running) return START_NOT_STICKY
                soundUri = intent.getStringExtra(EXTRA_SOUND_URI)?.let { Uri.parse(it) }
                targetCount = intent.getIntExtra(EXTRA_COUNT, 0)
                delayMs = intent.getLongExtra(EXTRA_DELAY, 0L)
                if (targetCount < 1) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                playedCount = 0
                failedCount = 0
                running = true
                startForeground(SERVICE_NOTIF_ID, buildServiceNotification("Starting…"))
                handler.post(playStep)
            }
        }
        // Don't restart if the system does kill us; the run is over.
        return START_NOT_STICKY
    }

    private fun updateProgress() {
        val text = "Played $playedCount of $targetCount"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(SERVICE_NOTIF_ID, buildServiceNotification(text))
        sendBroadcast(
            Intent(BROADCAST_PROGRESS)
                .putExtra(EXTRA_PLAYED, playedCount)
                .putExtra(EXTRA_TOTAL, targetCount)
        )
    }

    private fun finishRun(message: String) {
        handler.removeCallbacks(playStep)
        running = false
        try {
            currentRingtone?.stop()
        } catch (e: Exception) {
            // Already stopped.
        }
        currentRingtone = null
        sendBroadcast(Intent(BROADCAST_DONE).putExtra(EXTRA_MESSAGE_DONE, message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildServiceNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Ringtone Spammer",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("Ringtone Spammer")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(playStep)
        running = false
        try {
            currentRingtone?.stop()
        } catch (e: Exception) {
            // Already stopped.
        }
        currentRingtone = null
        super.onDestroy()
    }
}
