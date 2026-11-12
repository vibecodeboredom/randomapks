package com.opentv.toolbox.adbboot

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.opentv.toolbox.databinding.ActivityAdbbootBinding
import rikka.shizuku.Shizuku

/**
 * One screen: a big button that enables ADB on port 5555 right now,
 * and a status block showing whether it is on, whether Shizuku is
 * ready, and whether wireless debugging is on. The same enable runs
 * automatically at every boot (see BootReceiver) — this screen is
 * for the first run and for checking.
 *
 * Everything that touches the network or Shizuku runs off the main
 * thread; results come back to the UI thread.
 */
class AdbBootActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdbbootBinding

    /** The last enable result, kept above the status block so it is
     *  not lost when the status refreshes. */
    private var lastActionDetail: String? = null

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Allowed in Shizuku", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Not allowed — Shizuku cannot run the command without it.",
                    Toast.LENGTH_LONG
                ).show()
            }
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdbbootBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Shizuku.addRequestPermissionResultListener(permissionListener)

        binding.enableButton.setOnClickListener {
            binding.enableButton.isEnabled = false
            binding.statusText.text = "Working…"
            Thread {
                val result = AdbBoot.enableAdb(this)
                runOnUiThread {
                    binding.enableButton.isEnabled = true
                    lastActionDetail = result.detail
                    refreshStatus()
                }
            }.start()
        }

        binding.allowShizukuButton.setOnClickListener {
            // Asks Shizuku to show its authorisation dialog. The
            // result arrives in permissionListener.
            Shizuku.requestPermission(0)
        }

        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    /** Rebuild the status block. The port check is I/O, so it runs
     *  off the main thread. */
    private fun refreshStatus() {
        Thread {
            val on5555 = AdbBoot.isAdbOn5555()
            val shizukuInstalled = AdbBoot.isShizukuInstalled(this)
            val shizukuRunning = AdbBoot.isShizukuRunning()
            val shizukuAllowed = AdbBoot.isShizukuPermissionGranted()
            val wifiDbg = AdbBoot.isWirelessDebuggingOn(this)
            runOnUiThread {
                val lines = StringBuilder()
                lines.append(
                    if (on5555) "ADB on port 5555: ON ✓"
                    else "ADB on port 5555: off"
                )
                lines.append("\nShizuku: ")
                lines.append(
                    when {
                        !shizukuInstalled -> "not installed"
                        !shizukuRunning -> "installed, not running"
                        !shizukuAllowed -> "running, this app is not allowed yet"
                        else -> "running, allowed ✓"
                    }
                )
                lines.append(
                    "\nWireless debugging: " + if (wifiDbg) "on" else "off"
                )
                // Only show the allow button when it can do something.
                binding.allowShizukuButton.visibility =
                    if (shizukuRunning && !shizukuAllowed) View.VISIBLE else View.GONE
                // The last action result stays on top; the status block
                // below it is rebuilt fresh each time (never appended
                // to, or it would grow with every refresh).
                binding.statusText.text = lastActionDetail?.let { "$it\n\n$lines" } ?: lines.toString()
            }
        }.start()
    }
}
