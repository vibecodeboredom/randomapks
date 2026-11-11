package com.opentv.settingsrepeater

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
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
 * Plus a "Random page each time" checkbox.
 *
 * One screen, big text and buttons, remote / DPAD friendly — same
 * style as the other usefulapks apps. No network.
 *
 * The repeat itself runs in RepeatService, a foreground service, so
 * Android treats it as work the user can see and doesn't kill it
 * mid-run — that's what keeps it alive while a Settings page is on
 * screen. (A Device Admin privilege on its own would NOT stop
 * Android killing a background process.) The service also asks to
 * ignore battery optimisations, so Doze / App Standby can't throttle
 * it. This Activity only collects the inputs, starts/stops the
 * service, and shows progress from its broadcasts.
 *
 * Last inputs (times, delay, page, custom action, random) are
 * remembered in SharedPreferences and restored on launch.
 */
class MainActivity : AppCompatActivity() {

    private val pages get() = SettingsPages.pages

    private lateinit var timesInput: EditText
    private lateinit var delayInput: EditText
    private lateinit var pageSpinner: Spinner
    private lateinit var customInput: EditText
    private lateinit var randomCheckbox: CheckBox
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var adminButton: Button
    private lateinit var statusText: TextView

    /** Last inputs, so the app reopens with what the user typed
     * before instead of resetting to defaults. */
    private lateinit var prefs: SharedPreferences

    private var running = false

    /** Progress + finished broadcasts from RepeatService. */
    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RepeatService.BROADCAST_PROGRESS -> {
                    val count = intent.getIntExtra(RepeatService.EXTRA_COUNT, 0)
                    val total = intent.getIntExtra(RepeatService.EXTRA_TOTAL, 0)
                    val label = intent.getStringExtra(RepeatService.EXTRA_PAGE_LABEL).orEmpty()
                    statusText.text = "Opened $count of $total — $label"
                }
                RepeatService.BROADCAST_DONE -> {
                    running = false
                    setIdleUi()
                    statusText.text =
                        intent.getStringExtra(RepeatService.EXTRA_MESSAGE).orEmpty()
                }
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
        randomCheckbox = findViewById(R.id.random_checkbox)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)
        adminButton = findViewById(R.id.admin_button)
        statusText = findViewById(R.id.status_text)

        prefs = getSharedPreferences("settingsrepeater", Context.MODE_PRIVATE)
        // Restore the last inputs before wiring the listeners, so the
        // listeners don't save the defaults over them.
        timesInput.setText(prefs.getString("times", "5"))
        delayInput.setText(prefs.getString("delay", "500"))
        customInput.setText(prefs.getString("custom", ""))
        randomCheckbox.isChecked = prefs.getBoolean("random", false)

        val saveWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = saveInputs()
        }
        timesInput.addTextChangedListener(saveWatcher)
        delayInput.addTextChangedListener(saveWatcher)
        customInput.addTextChangedListener(saveWatcher)
        randomCheckbox.setOnCheckedChangeListener { _, _ -> saveInputs() }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            pages.map { it.label }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        pageSpinner.adapter = adapter
        pageSpinner.setSelection(
            prefs.getInt("page", 0).coerceIn(0, pages.size - 1),
            false
        )
        pageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>, view: View?, position: Int, id: Long
            ) {
                // Only the "Custom…" entry needs the free-text field.
                customInput.visibility =
                    if (pages[position].action == null) View.VISIBLE else View.GONE
                saveInputs()
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }

        startButton.setOnClickListener { startRun() }
        stopButton.setOnClickListener { stopRun() }
        adminButton.setOnClickListener { enableAdmin() }

        setIdleUi()
        // Focus Start so a remote works straight away.
        startButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(RepeatService.BROADCAST_PROGRESS)
            addAction(RepeatService.BROADCAST_DONE)
        }
        registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        // Safety net: save even if a listener missed a change.
        saveInputs()
        try {
            unregisterReceiver(progressReceiver)
        } catch (e: IllegalArgumentException) {
            // Wasn't registered.
        }
        super.onPause()
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
        // Random mode ignores the page picker — a different page is
        // picked from the built-in list on every step.
        val randomMode = randomCheckbox.isChecked
        val page = pages[pageSpinner.selectedItemPosition]
        val action = if (randomMode) {
            pages.first { it.action != null }.action!!
        } else {
            page.action ?: customInput.text.toString().trim()
        }
        if (action.isEmpty()) {
            Toast.makeText(this, "Enter a Settings action, e.g. android.settings.WIFI_SETTINGS", Toast.LENGTH_SHORT).show()
            return
        }

        // Ask to ignore battery optimisations once, so Doze / App
        // Standby can't throttle the run. The run starts regardless.
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                // Some builds refuse the prompt; the foreground
                // service is the main protection anyway.
            }
        }

        val serviceIntent = Intent(this, RepeatService::class.java).apply {
            this.action = RepeatService.ACTION_START
            putExtra(RepeatService.EXTRA_TIMES, times)
            putExtra(RepeatService.EXTRA_DELAY, delay)
            putExtra(RepeatService.EXTRA_RANDOM, randomMode)
            putExtra(RepeatService.EXTRA_ACTION, action)
            putExtra(RepeatService.EXTRA_LABEL, if (randomMode) "random page" else page.label)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        running = true
        setRunningUi()
        statusText.text = "Starting…"
    }

    /** Stop button: tell the service to cancel whatever is left. */
    private fun stopRun() {
        if (!running) return
        val serviceIntent = Intent(this, RepeatService::class.java).apply {
            action = RepeatService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    /** Device admin: opens the system prompt to make this app a
     * device admin. It cannot be turned on silently — the user has
     * to tap Activate. Once on, the app can't be force-stopped or
     * uninstalled until the admin is removed first. It does not
     * keep the app on top; the foreground service does the
     * background work. */
    private fun enableAdmin() {
        val admin = ComponentName(this, AdminReceiver::class.java)
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(admin)) {
            Toast.makeText(this, "Already a device admin", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "So Settings Repeater can't be force-stopped or uninstalled by accident."
            )
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open the device admin prompt", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setRunningUi() {
        timesInput.isEnabled = false
        delayInput.isEnabled = false
        pageSpinner.isEnabled = false
        customInput.isEnabled = false
        randomCheckbox.isEnabled = false
        startButton.isEnabled = false
        stopButton.isEnabled = true
        stopButton.requestFocus()
    }

    private fun setIdleUi() {
        timesInput.isEnabled = true
        delayInput.isEnabled = true
        pageSpinner.isEnabled = true
        customInput.isEnabled = true
        randomCheckbox.isEnabled = true
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    /** Persist the current inputs so the next launch restores them. */
    private fun saveInputs() {
        prefs.edit()
            .putString("times", timesInput.text.toString())
            .putString("delay", delayInput.text.toString())
            .putString("custom", customInput.text.toString())
            .putInt("page", pageSpinner.selectedItemPosition)
            .putBoolean("random", randomCheckbox.isChecked)
            .apply()
    }
}
