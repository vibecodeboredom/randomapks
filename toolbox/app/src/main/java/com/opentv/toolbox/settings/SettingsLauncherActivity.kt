package com.opentv.toolbox.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.GridLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Quick-launches specific Android Settings screens. Built for a plain
 * Android device that hides most of its Settings options — instead of
 * digging through menus, one big button jumps straight to the screen.
 *
 * Buttons are large, focusable, and spaced for remote / DPAD navigation
 * as well as touch. A screen that can't be opened shows a toast instead
 * of crashing.
 */
class SettingsLauncherActivity : AppCompatActivity() {

    /** Label + the Settings action that opens that screen. */
    private data class Shortcut(val label: String, val action: String)

    private val shortcuts = listOf(
        Shortcut("About", Settings.ACTION_DEVICE_INFO_SETTINGS),
        Shortcut("Developer Options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Shortcut("Wi-Fi", Settings.ACTION_WIFI_SETTINGS),
        Shortcut("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS),
        Shortcut("Network & Internet", Settings.ACTION_WIRELESS_SETTINGS),
        Shortcut("Display", Settings.ACTION_DISPLAY_SETTINGS),
        Shortcut("Sound", Settings.ACTION_SOUND_SETTINGS),
        Shortcut("Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Shortcut("Apps", Settings.ACTION_APPLICATION_SETTINGS),
        Shortcut("Security", Settings.ACTION_SECURITY_SETTINGS),
        Shortcut("Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        Shortcut("Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Shortcut("Date & Time", Settings.ACTION_DATE_SETTINGS),
        Shortcut("Language", Settings.ACTION_LOCALE_SETTINGS),
        Shortcut("Home App", Settings.ACTION_HOME_SETTINGS),
        Shortcut("Settings Home", Settings.ACTION_SETTINGS)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settingslauncher)

        val grid = findViewById<GridLayout>(R.id.shortcut_grid)
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        for (shortcut in shortcuts) {
            // MaterialButton, filled primary — same look as the buttons
            // in the other apps, big enough for touch and remote.
            val button = MaterialButton(this).apply {
                text = shortcut.label
                textSize = 18f
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3B82F6"))
                cornerRadius = dp(14)
                minHeight = dp(64)
                isFocusable = true
                isFocusableInTouchMode = true
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(16), dp(16), dp(16))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                }
                setOnClickListener { openShortcut(shortcut) }
            }
            grid.addView(button)
        }
        // Focus the first button so a remote works straight away.
        grid.post { grid.getChildAt(0)?.requestFocus() }
    }

    private fun openShortcut(shortcut: Shortcut) {
        try {
            startActivity(Intent(shortcut.action))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "Couldn't open ${shortcut.label} on this device",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
