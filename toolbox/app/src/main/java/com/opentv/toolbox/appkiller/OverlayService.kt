package com.opentv.toolbox.appkiller

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * The floating killer overlay. Runs as a foreground service so
 * Android doesn't kill it while it sits on top of other apps.
 *
 * What it does:
 * - Shows the app currently in the foreground (label + package),
 *   refreshed about once a second from a background poll thread.
 * - "Kill" force-stops that app once, as root.
 * - "Spam Kill x100" force-stops it 100 times in a row (~50 ms
 *   apart) so it stays dead even if it tries to restart itself.
 * - The whole overlay is draggable by its header, so it can be
 *   moved out of the way of whatever is underneath.
 *
 * Foreground detection, primary path: root dumpsys
 * (`su -c "dumpsys activity activities"`, parsed in RootHelper).
 * Fallback: UsageStatsManager, if the user granted usage access
 * (Settings > Apps > Special access > Usage access) — the root
 * path is the main one, since the app needs root anyway.
 *
 * Safety: it never kills its own package — that would kill the
 * overlay itself. Everything else is fair game, including system
 * apps; that is the point of the app.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.opentv.toolbox.appkiller.START"
        const val ACTION_STOP = "com.opentv.toolbox.appkiller.STOP"
        private const val SERVICE_CHANNEL_ID = "appkiller-service"
        private const val SERVICE_NOTIF_ID = 1
        private const val SELF_PACKAGE = "com.opentv.toolbox"
        private const val SPAM_KILLS = 100
        private const val SPAM_DELAY_MS = 50L
        private const val POLL_INTERVAL_MS = 1000L
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private lateinit var appLabelText: TextView
    private lateinit var packageText: TextView
    private lateinit var statusText: TextView
    private lateinit var killButton: Button
    private lateinit var spamButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val labelCache = mutableMapOf<String, String>()

    @Volatile private var polling = false
    private var pollThread: Thread? = null
    @Volatile private var spamRunning = false

    /** Package currently shown on the overlay (updated by the poll). */
    @Volatile private var currentPackage: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopOverlay()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (overlayView != null) return START_NOT_STICKY
                startForeground(SERVICE_NOTIF_ID, buildServiceNotification())
                showOverlay()
                startPolling()
            }
        }
        return START_NOT_STICKY
    }

    // ---- Overlay construction ------------------------------------------------

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Compact overlay: small on purpose, so it stays out of the
        // way of whatever is underneath while still showing the
        // foreground app and the Kill / Spam Kill / close buttons.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E61A1A20"))
                cornerRadius = dp(10).toFloat()
            }
        }

        val header = TextView(this).apply {
            text = "☠ App Killer — drag me"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(header)

        appLabelText = TextView(this).apply {
            text = "Detecting…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(appLabelText)

        packageText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        root.addView(packageText)

        statusText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#AAAAAA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, dp(2), 0, 0)
        }
        root.addView(statusText)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }

        killButton = Button(this).apply {
            text = "Kill"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minimumHeight = dp(36)
            minWidth = 0
            setPadding(0, 0, 0, 0)
            background = redButtonBg()
            setOnClickListener { killOnce() }
        }
        spamButton = Button(this).apply {
            text = "Spam ×100"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minimumHeight = dp(36)
            minWidth = 0
            setPadding(0, 0, 0, 0)
            background = redButtonBg()
            setOnClickListener { spamKill() }
        }
        val closeButton = Button(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minimumHeight = dp(36)
            minWidth = 0
            setPadding(0, 0, 0, 0)
            background = greyButtonBg()
            setOnClickListener { stopOverlay() }
        }
        buttonRow.addView(killButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttonRow.addView(spamButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f).apply { leftMargin = dp(6) })
        buttonRow.addView(closeButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        root.addView(buttonRow)

        // Dragging: the header is the handle. Track the finger's raw
        // position and move the window with it.
        var startX = 0
        var startY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = overlayParams?.x ?: 0
                    startY = overlayParams?.y ?: 0
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    overlayParams?.let { params ->
                        params.x = startX + (event.rawX - startTouchX).toInt()
                        params.y = startY + (event.rawY - startTouchY).toInt()
                        try {
                            windowManager.updateViewLayout(root, params)
                        } catch (e: Exception) {
                            // Window went away; nothing to move.
                        }
                    }
                    true
                }
                else -> false
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            // TYPE_APPLICATION_OVERLAY only exists from Android 8 (API
            // 26); on Android 7.1.2 the overlay type is TYPE_PHONE.
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        overlayParams = WindowManager.LayoutParams(
            dp(200),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // NOT_FOCUSABLE: touches pass through to the app underneath
            // except on the overlay's own views, so its buttons still
            // work. NOT_TOUCH_MODAL lets touches outside the overlay
            // reach the app below it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }

        overlayView = root
        windowManager.addView(root, overlayParams)
    }

    private fun redButtonBg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#C62828"))
        cornerRadius = dp(7).toFloat()
    }

    private fun greyButtonBg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#555555"))
        cornerRadius = dp(7).toFloat()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    // ---- Foreground polling --------------------------------------------------

    private fun startPolling() {
        polling = true
        pollThread = Thread {
            while (polling) {
                val pkg = detectForegroundPackage()
                if (pkg != null && pkg != currentPackage) {
                    currentPackage = pkg
                    val label = appLabel(pkg)
                    val isSelf = pkg == SELF_PACKAGE
                    mainHandler.post {
                        appLabelText.text = label
                        packageText.text = pkg
                        killButton.isEnabled = !isSelf
                        spamButton.isEnabled = !isSelf
                        if (isSelf) {
                            statusText.text = "That's me — can't kill myself"
                        } else if (statusText.text == "That's me — can't kill myself") {
                            // Don't leave the "that's me" note up once
                            // a different app is in front; kill results
                            // ("Killed X") stay until the next result.
                            statusText.text = ""
                        }
                    }
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.apply { start() }
    }

    /** Root dumpsys first; UsageStatsManager if that comes back empty. */
    private fun detectForegroundPackage(): String? {
        return RootHelper.getForegroundPackage() ?: foregroundViaUsageStats()
    }

    private fun foregroundViaUsageStats(): String? {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName
            )
            if (mode != AppOpsManager.MODE_ALLOWED) return null
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 10_000, now
            ) ?: return null
            var best: String? = null
            var bestTime = 0L
            for (entry in stats) {
                if (entry.lastTimeUsed > bestTime) {
                    bestTime = entry.lastTimeUsed
                    best = entry.packageName
                }
            }
            best
        } catch (e: Exception) {
            null
        }
    }

    private fun appLabel(packageName: String): String {
        labelCache[packageName]?.let { return it }
        val label = try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
        labelCache[packageName] = label
        return label
    }

    // ---- Killing --------------------------------------------------------------

    private fun killOnce() {
        val pkg = currentPackage ?: run {
            setStatus("No foreground app detected yet")
            return
        }
        if (pkg == SELF_PACKAGE) return
        Thread {
            val ok = RootHelper.forceStop(pkg)
            mainHandler.post {
                setStatus(if (ok) "Killed ${appLabel(pkg)}" else "Kill failed — root?")
            }
        }.start()
    }

    /** Force-stop the foreground app 100 times, ~50 ms apart, so it
     * stays dead even if it tries to restart itself. */
    private fun spamKill() {
        if (spamRunning) return
        val pkg = currentPackage ?: run {
            setStatus("No foreground app detected yet")
            return
        }
        if (pkg == SELF_PACKAGE) return
        val label = appLabel(pkg)
        spamRunning = true
        killButton.isEnabled = false
        spamButton.isEnabled = false
        Thread {
            var kills = 0
            for (i in 1..SPAM_KILLS) {
                if (!spamRunning) break
                if (RootHelper.forceStop(pkg)) kills++
                if (i % 10 == 0) {
                    val done = i
                    mainHandler.post { setStatus("Spam killing $label… $done/$SPAM_KILLS") }
                }
                try {
                    Thread.sleep(SPAM_DELAY_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
            mainHandler.post {
                setStatus("Spam killed $label $kills/$SPAM_KILLS times")
                killButton.isEnabled = true
                spamButton.isEnabled = true
                spamRunning = false
            }
        }.start()
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    // ---- Service plumbing ------------------------------------------------------

    private fun buildServiceNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "App Killer",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("App Killer")
            .setContentText("Overlay running — tap an app, then Kill")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun stopOverlay() {
        polling = false
        spamRunning = false
        pollThread?.interrupt()
        pollThread = null
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Already gone.
            }
        }
        overlayView = null
        overlayParams = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        polling = false
        spamRunning = false
        pollThread?.interrupt()
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Already gone.
            }
        }
        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
