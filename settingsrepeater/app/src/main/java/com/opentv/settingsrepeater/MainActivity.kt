package com.opentv.settingsrepeater

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Opens a chosen Settings page X times in a row, waiting Y ms between
 * each opening. Three inputs, exactly as Chuni described them:
 *   X = how many times to open the page,
 *   Y = delay in milliseconds between openings,
 *   Z = which Settings page (a picker, plus a "Custom…" action string).
 *
 * One screen, big text and buttons, remote / DPAD friendly — same
 * style as the other usefulapks apps. No permissions, no network.
 *
 * The repeat is a Handler on the main looper: the first page opens
 * immediately, then one every Y ms until X is reached. Stop cancels
 * whatever is left at once. The status line counts the opens as they
 * fire ("Opened 3 of 5").
 */
class MainActivity : AppCompatActivity() {

    /** Label + the Settings action that opens that screen. The list
     * mirrors Settings Launcher's shortcuts (same repo) so the two
     * apps stay consistent, plus Battery, which it doesn't have. */
    private data class Page(val label: String, val action: String?)

    private val pages = listOf(
        Page("Settings home", Settings.ACTION_SETTINGS),
        Page("Wi-Fi", Settings.ACTION_WIFI_SETTINGS),
        Page("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS),
        Page("Network & Internet", Settings.ACTION_WIRELESS_SETTINGS),
        Page("Apps", Settings.ACTION_APPLICATION_SETTINGS),
        Page("Display", Settings.ACTION_DISPLAY_SETTINGS),
        Page("Sound", Settings.ACTION_SOUND_SETTINGS),
        Page("Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        Page("Security", Settings.ACTION_SECURITY_SETTINGS),
        Page("Developer options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Page("About phone", Settings.ACTION_DEVICE_INFO_SETTINGS),
        Page("Date & time", Settings.ACTION_DATE_SETTINGS),
        Page("Language", Settings.ACTION_LOCALE_SETTINGS),
        Page("Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Page("Battery", Settings.ACTION_BATTERY_SETTINGS),
        Page("Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Page("Home app", Settings.ACTION_HOME_SETTINGS),
        // action = null: the custom action field supplies it.
        Page("Custom…", null)
    )

    private lateinit var timesInput: EditText
    private lateinit var delayInput: EditText
    private lateinit var pageSpinner: Spinner
    private lateinit var customInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView

    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var targetCount = 0
    private var openedCount = 0
    private var delayMs = 0L
    private var currentAction = ""
    private var currentLabel = ""

    /** One step of the run: fire the Intent, count it, schedule the
     * next one Y ms later — or finish when X is reached. */
    private val openStep = object : Runnable {
        override fun run() {
            if (!running) return
            if (openedCount >= targetCount) {
                finishRun("Done — opened $openedCount of $targetCount")
                return
            }
            try {
                startActivity(Intent(currentAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                finishRun("Stopped — couldn't open $currentLabel on this device")
                Toast.makeText(
                    this@MainActivity,
                    "Couldn't open $currentLabel on this device",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            openedCount++
            statusText.text = "Opened $openedCount of $targetCount"
            if (openedCount >= targetCount) {
                finishRun("Done — opened $openedCount of $targetCount")
            } else {
                handler.postDelayed(this, delayMs)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        timesInput = findViewById(R.id.times_input)
        delayInput = findViewById(R.id.delay_input)
        pageSpinner = findViewById(R.id.page_spinner)
        customInput = findViewById(R.id.custom_input)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)
        statusText = findViewById(R.id.status_text)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            pages.map { it.label }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        pageSpinner.adapter = adapter
        pageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>, view: View?, position: Int, id: Long
            ) {
                // Only the "Custom…" entry needs the free-text field.
                customInput.visibility =
                    if (pages[position].action == null) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }

        startButton.setOnClickListener { startRun() }
        stopButton.setOnClickListener { stopRun("Stopped at $openedCount of $targetCount") }

        setIdleUi()
        // Focus Start so a remote works straight away.
        startButton.requestFocus()
    }

    private fun startRun() {
        if (running) return
        val times = timesInput.text.toString().trim().toIntOrNull()
        val delay = delayInput.text.toString().trim().toLongOrNull()
        if (times == null || times < 1) {
            Toast.makeText(this, "Enter how many times (X) — a number from 1 up", Toast.LENGTH_SHORT).show()
            return
        }
        if (delay == null || delay < 0) {
            Toast.makeText(this, "Enter the delay (Y) in ms — 0 or more", Toast.LENGTH_SHORT).show()
            return
        }
        val page = pages[pageSpinner.selectedItemPosition]
        val action = page.action ?: customInput.text.toString().trim()
        if (action.isEmpty()) {
            Toast.makeText(this, "Enter a Settings action, e.g. android.settings.WIFI_SETTINGS", Toast.LENGTH_SHORT).show()
            return
        }

        targetCount = times
        delayMs = delay
        currentAction = action
        currentLabel = page.label
        openedCount = 0
        running = true
        setRunningUi()
        statusText.text = "Starting…"
        // First page opens immediately; the rest follow Y ms apart.
        handler.post(openStep)
    }

    /** Stop button: cancel whatever is left at once. */
    private fun stopRun(message: String) {
        if (!running) return
        handler.removeCallbacks(openStep)
        running = false
        setIdleUi()
        statusText.text = message
    }

    /** Run finished on its own (or the page couldn't be opened). */
    private fun finishRun(message: String) {
        handler.removeCallbacks(openStep)
        running = false
        setIdleUi()
        statusText.text = message
    }

    private fun setRunningUi() {
        timesInput.isEnabled = false
        delayInput.isEnabled = false
        pageSpinner.isEnabled = false
        customInput.isEnabled = false
        startButton.isEnabled = false
        stopButton.isEnabled = true
        stopButton.requestFocus()
    }

    private fun setIdleUi() {
        timesInput.isEnabled = true
        delayInput.isEnabled = true
        pageSpinner.isEnabled = true
        customInput.isEnabled = true
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    override fun onDestroy() {
        // Never let a run outlive the screen.
        handler.removeCallbacks(openStep)
        running = false
        super.onDestroy()
    }
}
