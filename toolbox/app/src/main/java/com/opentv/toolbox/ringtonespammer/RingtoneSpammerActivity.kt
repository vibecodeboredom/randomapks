package com.opentv.toolbox.ringtonespammer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Plays a ringtone on demand: X times, Y ms apart. Two inputs plus
 * a sound picker:
 *   Sound — pick any ringtone / notification sound (defaults to the
 *   system notification sound),
 *   Count (X) — how many times to play it,
 *   Delay (Y) — ms between each play.
 *
 * Same shape as Notification Spammer: the playing runs in
 * RingtoneSpamService, a foreground service, so Android treats it
 * as work the user can see and doesn't kill it mid-run. This
 * Activity only collects the inputs, starts/stops the service, and
 * shows progress from its broadcasts.
 *
 * Last inputs (sound, count, delay) are remembered in
 * SharedPreferences and restored on launch.
 */
class RingtoneSpammerActivity : AppCompatActivity() {

    private lateinit var soundLabel: TextView
    private lateinit var pickSoundButton: Button
    private lateinit var countInput: EditText
    private lateinit var delayInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView

    /** Last inputs, so the app reopens with what the user picked
     * before instead of resetting to defaults. */
    private lateinit var prefs: SharedPreferences

    private var running = false

    /** The sound to play, as a URI string. */
    private var soundUri: String =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString()

    /** The sound picker: returns the picked URI, or null if the
     * user cancelled. */
    private val pickSoundLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val picked = result.data
            ?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            ?: return@registerForActivityResult
        soundUri = picked.toString()
        prefs.edit().putString("soundUri", soundUri).apply()
        soundLabel.text = getString(R.string.rs_sound_picked)
    }

    /** Progress + finished broadcasts from RingtoneSpamService. */
    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RingtoneSpamService.BROADCAST_PROGRESS -> {
                    val played = intent.getIntExtra(RingtoneSpamService.EXTRA_PLAYED, 0)
                    val total = intent.getIntExtra(RingtoneSpamService.EXTRA_TOTAL, 0)
                    statusText.text = "Played $played of $total"
                }
                RingtoneSpamService.BROADCAST_DONE -> {
                    running = false
                    setIdleUi()
                    statusText.text =
                        intent.getStringExtra(RingtoneSpamService.EXTRA_MESSAGE_DONE).orEmpty()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ringtonespammer)

        soundLabel = findViewById(R.id.sound_label)
        pickSoundButton = findViewById(R.id.pick_sound_button)
        countInput = findViewById(R.id.count_input)
        delayInput = findViewById(R.id.delay_input)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)
        statusText = findViewById(R.id.status_text)

        prefs = getSharedPreferences("ringtonespammer", Context.MODE_PRIVATE)
        // Restore the last inputs before wiring the listeners, so the
        // listeners don't save the defaults over them.
        soundUri = prefs.getString(
            "soundUri",
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString()
        ) ?: soundUri
        soundLabel.text = if (prefs.contains("soundUri")) {
            getString(R.string.rs_sound_picked)
        } else {
            getString(R.string.rs_sound_default_notification)
        }
        countInput.setText(prefs.getString("count", "10"))
        delayInput.setText(prefs.getString("delay", "500"))

        val saveWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = saveInputs()
        }
        countInput.addTextChangedListener(saveWatcher)
        delayInput.addTextChangedListener(saveWatcher)

        pickSoundButton.setOnClickListener { openSoundPicker() }
        startButton.setOnClickListener { startRun() }
        stopButton.setOnClickListener { stopRun() }

        setIdleUi()
        // Focus Start so a remote works straight away.
        startButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(RingtoneSpamService.BROADCAST_PROGRESS)
            addAction(RingtoneSpamService.BROADCAST_DONE)
        }
        // The 3-arg registerReceiver(receiver, filter, flags) only
        // exists from Android 13 (API 33); on older versions the
        // 2-arg call is the only one, and calling the 3-arg one
        // would crash with NoSuchMethodError. (This is what lets the
        // app run on Android 7.1.2 / API 25.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(progressReceiver, filter)
        }
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

    /** System sound picker: any ringtone / notification / alarm. */
    private fun openSoundPicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Pick a sound")
            try {
                putExtra(
                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                    Uri.parse(soundUri)
                )
            } catch (e: Exception) {
                // Bad stored URI — the picker opens without a
                // pre-selection instead of crashing.
            }
        }
        try {
            pickSoundLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open the sound picker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRun() {
        if (running) return
        val count = countInput.text.toString().trim().toIntOrNull()
        val delay = delayInput.text.toString().trim().toLongOrNull()
        if (count == null || count < 1) {
            Toast.makeText(this, "Enter how many (X) — a number from 1 up", Toast.LENGTH_SHORT).show()
            return
        }
        if (delay == null || delay < 0) {
            Toast.makeText(this, "Enter the delay (Y) in ms — 0 or more", Toast.LENGTH_SHORT).show()
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

        val serviceIntent = Intent(this, RingtoneSpamService::class.java).apply {
            this.action = RingtoneSpamService.ACTION_START
            putExtra(RingtoneSpamService.EXTRA_SOUND_URI, soundUri)
            putExtra(RingtoneSpamService.EXTRA_COUNT, count)
            putExtra(RingtoneSpamService.EXTRA_DELAY, delay)
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
        val serviceIntent = Intent(this, RingtoneSpamService::class.java).apply {
            action = RingtoneSpamService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    private fun setRunningUi() {
        pickSoundButton.isEnabled = false
        countInput.isEnabled = false
        delayInput.isEnabled = false
        startButton.isEnabled = false
        stopButton.isEnabled = true
        stopButton.requestFocus()
    }

    private fun setIdleUi() {
        pickSoundButton.isEnabled = true
        countInput.isEnabled = true
        delayInput.isEnabled = true
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    /** Persist the current inputs so the next launch restores them. */
    private fun saveInputs() {
        prefs.edit()
            .putString("soundUri", soundUri)
            .putString("count", countInput.text.toString())
            .putString("delay", delayInput.text.toString())
            .apply()
    }
}
