package com.opentv.toolbox

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

/**
 * Toolbox — one app, one menu. Every tool from the old standalone
 * apps lives here as a screen you open from this menu and come back
 * from with the Back button — including App Manager, which is now
 * fully embedded (its own list, root toggling, and accessibility
 * service all live in this app).
 */
class MainActivity : AppCompatActivity() {

    /** One menu row: name, one-line description, and what to open. */
    private data class Tool(
        val name: String,
        val description: String,
        val open: () -> Unit
    )

    private fun tools(): List<Tool> = listOf(
        Tool(
            "Settings Launcher",
            "Jump straight to any Settings screen"
        ) { open(com.opentv.toolbox.settings.SettingsLauncherActivity::class.java) },
        Tool(
            "DevOptions",
            "Open Developer options"
        ) { open(com.opentv.toolbox.devoptions.DevOptionsActivity::class.java) },
        Tool(
            "App Manager",
            "Enable or disable apps — root first, big list with search"
        ) { open(com.opentv.toolbox.appmanager.AppManagerActivity::class.java) },
        Tool(
            "Settings Repeater",
            "Open a Settings page X times, Y ms apart"
        ) { open(com.opentv.toolbox.repeater.RepeaterActivity::class.java) },
        Tool(
            "AdbBoot",
            "ADB on port 5555, automatically at boot"
        ) { open(com.opentv.toolbox.adbboot.AdbBootActivity::class.java) },
        Tool(
            "Admin Tools",
            "Device Admin features, one button each"
        ) { open(com.opentv.toolbox.admintools.AdminToolsActivity::class.java) },
        Tool(
            "Notification Spammer",
            "Post X notifications, Y ms apart"
        ) { open(com.opentv.toolbox.notifspammer.NotifSpammerActivity::class.java) },
        Tool(
            "App Killer",
            "Floating overlay that kills the app in front"
        ) { open(com.opentv.toolbox.appkiller.AppKillerActivity::class.java) },
        Tool(
            "Ringtone Spammer",
            "Play a ringtone X times, Y ms apart"
        ) { open(com.opentv.toolbox.ringtonespammer.RingtoneSpammerActivity::class.java) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        list.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.parseColor("#F5F5F7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        list.addView(TextView(this).apply {
            text = getString(R.string.app_tagline)
            setTextColor(Color.parseColor("#B0B0B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(6), 0, dp(8))
        })

        for (tool in tools()) {
            list.addView(menuCard(tool, dp))
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101014"))
            addView(list)
        })
    }

    /** One big menu row: a card with the tool name + description. */
    private fun menuCard(tool: Tool, dp: (Int) -> Int): MaterialCardView {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(Color.parseColor("#1E1E28"))
            radius = dp(16).toFloat()
            strokeColor = Color.parseColor("#3A3A48")
            strokeWidth = dp(1)
            cardElevation = 0f
            isClickable = true
            isFocusable = true
            setOnClickListener { tool.open() }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        inner.addView(TextView(this).apply {
            text = tool.name
            setTextColor(Color.parseColor("#F5F5F7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        inner.addView(TextView(this).apply {
            text = tool.description
            setTextColor(Color.parseColor("#B0B0B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(4), 0, 0)
        })
        card.addView(inner)
        (card.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.topMargin = dp(12)
        } ?: run {
            card.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        return card
    }

    private fun <T> open(activityClass: Class<T>) {
        startActivity(Intent(this, activityClass))
    }
}
