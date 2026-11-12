package com.opentv.notifspammer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Posts a bunch of notifications on demand: X of them, Y ms apart,
 * each with its own ID so they stack in the notification shade.
 * Four inputs:
 *   Title — the notification title,
 *   Message — the notification text,
 *   Count (X) — how many notifications to post,
 *   Delay (Y) — ms between each post.
 * Plus a "Use root (su)" option on rooted devices.
 *
 * One screen, big text and buttons, remote / DPAD friendly — same
 * style as the other usefulapks apps. No network.
 *
 * The posting itself runs in SpamService, a foreground service, so
 * Android treats it as work the user can see and doesn't kill it
 * mid-run. The service also asks to ignore battery optimisations,
 * so Doze / App Standby can't throttle it. This Activity only
 * collects the inputs, starts/stops the service, and shows progress
 * from its broadcasts.
 *
 * On Android 13+ the app needs POST_NOTIFICATIONS at runtime; it
 * asks when Start is tapped (and the run starts regardless — the
 * request is what makes the notifications actually show).
 *
 * 1.0.1: the "Use root (su)" option is gone. It used to post each
 * notification via `su -c "cmd notification post …"` to bypass the
 * app's rate limit, but `cmd notification post` takes no channel
 * argument, so those posts landed on the shell's own channel and
 * came out silent and buried — the user heard no sound and saw no
 * notifications. All posts now go through the app's own
 * NotificationManager on a high-importance channel, so they sound
 * and show. Very fast runs (tiny delays) may be throttled by
 * Android's per-app rate limit; that is the trade-off for posts
 * that actually sound.
 *
 * Last inputs (title, message, count, delay) are remembered in
 * SharedPreferences and restored on launch.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var titleInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var countInput: EditText
    private lateinit var delayInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView

    /** Last inputs, so the app reopens with what the user typed
     * before instead of resetting to defaults. */
    private lateinit var prefs: SharedPreferences

    private var running = false

    /** Progress + finished broadcasts from SpamService. */
    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SpamService.BROADCAST_PROGRESS -> {
                    val posted = intent.getIntExtra(SpamService.EXTRA_POSTED, 0)
                    val total = intent.getIntExtra(SpamService.EXTRA_TOTAL, 0)
                    statusText.text = "Posted $posted of $total"
                }
                SpamService.BROADCAST_DONE -> {
                    running = false
                    setIdleUi()
                    statusText.text =
                        intent.getStringExtra(SpamService.EXTRA_MESSAGE_DONE).orEmpty()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        titleInput = findViewById(R.id.title_input)
        messageInput = findViewById(R.id.message_input)
        countInput = findViewById(R.id.count_input)
        delayInput = findViewById(R.id.delay_input)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)
        statusText = findViewById(R.id.status_text)

        prefs = getSharedPreferences("notifspammer", Context.MODE_PRIVATE)
        // Restore the last inputs before wiring the listeners, so the
        // listeners don't save the defaults over them.
        titleInput.setText(prefs.getString("title", "Spam"))
        messageInput.setText(prefs.getString("message", "Hello!"))
        countInput.setText(prefs.getString("count", "10"))
        delayInput.setText(prefs.getString("delay", "500"))

        val saveWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = saveInputs()
        }
        titleInput.addTextChangedListener(saveWatcher)
        messageInput.addTextChangedListener(saveWatcher)
        countInput.addTextChangedListener(saveWatcher)
        delayInput.addTextChangedListener(saveWatcher)

        startButton.setOnClickListener { startRun() }
        stopButton.setOnClickListener { stopRun() }

        setIdleUi()
        // Focus Start so a remote works straight away.
        startButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(SpamService.BROADCAST_PROGRESS)
            addAction(SpamService.BROADCAST_DONE)
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

    private fun startRun() {
        if (running) return
        val title = titleInput.text.toString().trim()
        val message = messageInput.text.toString().trim()
        val count = countInput.text.toString().trim().toIntOrNull()
        val delay = delayInput.text.toString().trim().toLongOrNull()
        if (title.isEmpty() && message.isEmpty()) {
            Toast.makeText(this, "Enter a title or a message", Toast.LENGTH_SHORT).show()
            return
        }
        if (count == null || count < 1) {
            Toast.makeText(this, "Enter how many (X) — a number from 1 up", Toast.LENGTH_SHORT).show()
            return
        }
        if (delay == null || delay < 0) {
            Toast.makeText(this, "Enter the delay (Y) in ms — 0 or more", Toast.LENGTH_SHORT).show()
            return
        }

        // Android 13+ needs POST_NOTIFICATIONS at runtime for the
        // normal path; ask now so the notifications actually show.
        // The run starts regardless — the permission prompt is what
        // unblocks them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
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

        val serviceIntent = Intent(this, SpamService::class.java).apply {
            this.action = SpamService.ACTION_START
            putExtra(SpamService.EXTRA_TITLE, title)
            putExtra(SpamService.EXTRA_MESSAGE, message)
            putExtra(SpamService.EXTRA_COUNT, count)
            putExtra(SpamService.EXTRA_DELAY, delay)
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
        val serviceIntent = Intent(this, SpamService::class.java).apply {
            action = SpamService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    private fun setRunningUi() {
        titleInput.isEnabled = false
        messageInput.isEnabled = false
        countInput.isEnabled = false
        delayInput.isEnabled = false
        startButton.isEnabled = false
        stopButton.isEnabled = true
        stopButton.requestFocus()
    }

    private fun setIdleUi() {
        titleInput.isEnabled = true
        messageInput.isEnabled = true
        countInput.isEnabled = true
        delayInput.isEnabled = true
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    /** Persist the current inputs so the next launch restores them. */
    private fun saveInputs() {
        prefs.edit()
            .putString("title", titleInput.text.toString())
            .putString("message", messageInput.text.toString())
            .putString("count", countInput.text.toString())
            .putString("delay", delayInput.text.toString())
            .apply()
    }
}
