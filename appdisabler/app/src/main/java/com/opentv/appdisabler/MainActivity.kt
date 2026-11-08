package com.opentv.appdisabler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Lists the system apps on the device and disables the ones the user
 * picks — without ADB, by automating the taps in Settings.
 *
 * Setup: the user must turn on the App Disabler accessibility service
 * (Settings → Accessibility → App Disabler). That is the permission
 * this app needs — it is NOT a Device Admin, because a Device Admin
 * cannot disable other apps. Only the Device Owner can, and that
 * needs ADB or a factory reset, which this box doesn't have.
 */
class MainActivity : AppCompatActivity() {

    private data class AppEntry(
        val label: String,
        val packageName: String,
        val checkBox: CheckBox
    )

    private val entries = mutableListOf<AppEntry>()
    private lateinit var statusView: TextView
    private val results = StringBuilder()

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                DisableService.ACTION_PROGRESS -> {
                    val pkg = intent.getStringExtra(DisableService.EXTRA_PACKAGE) ?: return
                    val status = intent.getStringExtra(DisableService.EXTRA_STATUS) ?: return
                    val detail = intent.getStringExtra(DisableService.EXTRA_DETAIL) ?: ""
                    val label = entries.find { it.packageName == pkg }?.label ?: pkg
                    val line = if (status == DisableService.STATUS_DISABLED) {
                        "✓ Disabled $label"
                    } else {
                        "– Skipped $label${if (detail.isNotEmpty()) " ($detail)" else ""}"
                    }
                    results.append(line).append('\n')
                    statusView.text = results.toString()
                }
                DisableService.ACTION_DONE -> {
                    Toast.makeText(
                        this@MainActivity,
                        "Done — check the list above",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status_text)
        val listLayout = findViewById<LinearLayout>(R.id.app_list)
        val disableButton = findViewById<Button>(R.id.disable_button)
        val accessButton = findViewById<Button>(R.id.accessibility_button)

        loadSystemApps(listLayout)

        accessButton.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(
                    this,
                    "Turn on App Disabler under Accessibility, then come back",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    "Couldn't open Accessibility settings on this device",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        disableButton.setOnClickListener {
            val selected = entries.filter { it.checkBox.isChecked }
                .map { it.packageName }
            if (selected.isEmpty()) {
                Toast.makeText(this, "Pick at least one app first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!DisableService.isEnabled(this)) {
                Toast.makeText(
                    this,
                    "Turn on the App Disabler accessibility service first (button above)",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            results.clear()
            results.append("Disabling ${selected.size} app(s)…\n")
            statusView.text = results.toString()
            DisableService.startDisabling(this, selected)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(DisableService.ACTION_PROGRESS)
            addAction(DisableService.ACTION_DONE)
        }
        // Plain 2-arg registerReceiver: the RECEIVER_NOT_EXPORTED flag
        // is API 33+ and this box is Android 9 — don't use it here.
        registerReceiver(progressReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(progressReceiver)
        } catch (_: IllegalArgumentException) {
            // Not registered — nothing to do.
        }
    }

    /** Lists system apps (the bloatware that needs disabling). */
    private fun loadSystemApps(listLayout: LinearLayout) {
        val pm: PackageManager = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        for (app in apps) {
            val label = app.loadLabel(pm).toString()
            val checkBox = CheckBox(this).apply {
                text = "$label\n${app.packageName}"
                textSize = 17f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            entries.add(AppEntry(label, app.packageName, checkBox))
            listLayout.addView(checkBox)
        }
        if (entries.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "No system apps found."
                textSize = 17f
                setTextColor(0xFFAAAAAA.toInt())
            })
        }
    }
}
