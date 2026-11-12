package com.opentv.appkiller

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Setup screen for App Killer. One screen:
 *
 * - What the app does, in plain words.
 * - Root status: the app NEEDS root (su) — without it the kill
 *   buttons do nothing, so Start stays disabled and the screen says
 *   so plainly.
 * - "Draw over other apps" button: opens the system overlay
 *   permission screen (ACTION_MANAGE_OVERLAY_PERMISSION). The
 *   overlay can't appear until this is allowed.
 * - "Usage access" button (optional fallback): the foreground
 *   detector falls back to UsageStatsManager if the root dumpsys
 *   path ever fails; that needs usage access granted in Settings.
 * - Start / Stop overlay buttons.
 *
 * The overlay itself lives in OverlayService, a foreground service,
 * so Android doesn't kill it while it sits on top of other apps.
 * This Activity only sets it up and starts/stops it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rootStatusText: TextView
    private lateinit var overlayStatusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootStatusText = findViewById(R.id.root_status_text)
        overlayStatusText = findViewById(R.id.overlay_status_text)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)

        findViewById<Button>(R.id.overlay_permission_button).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open the overlay settings", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.usage_access_button).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open the usage access settings", Toast.LENGTH_SHORT).show()
            }
        }

        startButton.setOnClickListener { startOverlay() }
        stopButton.setOnClickListener { stopOverlay() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val rooted = RootHelper.isRootAvailable()
        rootStatusText.text = if (rooted) {
            "Root: found (${RootHelper.findSu()}) — kills will work"
        } else {
            "Root: NOT found — this app needs root (su) to kill anything"
        }
        startButton.isEnabled = rooted

        val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        overlayStatusText.text = if (canDraw) {
            "Draw over apps: allowed"
        } else {
            "Draw over apps: NOT allowed yet — tap the button above"
        }
    }

    private fun startOverlay() {
        if (!RootHelper.isRootAvailable()) {
            Toast.makeText(this, "Root (su) is required", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            Toast.makeText(this, "Allow draw over other apps first", Toast.LENGTH_SHORT).show()
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                // The toast already said what to do.
            }
            return
        }
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Overlay started — switch to any app", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlay() {
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        startService(serviceIntent)
    }
}
