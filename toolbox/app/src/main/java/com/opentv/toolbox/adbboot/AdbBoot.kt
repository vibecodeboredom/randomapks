package com.opentv.toolbox.adbboot

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/**
 * The core of AdbBoot: switch adbd to TCP port 5555 without root.
 *
 * How it works (verified against the Shizuku docs and the ADB property
 * mechanism — see README.md for the full write-up):
 *
 * 1. A normal app cannot run `adb tcpip 5555`: there is no `adb` binary
 *    on the device, and restarting adbd needs more privilege than an app
 *    has. Shizuku fixes that — it runs commands as the *shell* user
 *    (uid 2000), the same identity `adb shell` uses.
 * 2. Through Shizuku the app runs, as shell:
 *        setprop persist.adb.tcp.port 5555
 *        setprop service.adb.tcp.port 5555
 *        setprop ctl.restart adbd
 *    `service.adb.tcp.port` + the restart takes effect immediately for
 *    this boot. `persist.adb.tcp.port` is the permanent one: adbd reads
 *    it at startup, so if the device's SELinux policy lets the shell
 *    user set it, port 5555 comes back on its own after every reboot
 *    and this app becomes a no-op. Some builds refuse the persist
 *    property — that is detected (getprop comes back empty) and the
 *    per-boot restart is what carries it.
 * 3. If Shizuku is not running, the fallback (Android 11+ only) turns
 *    wireless debugging back on via WRITE_SECURE_SETTINGS, so the
 *    device is at least reachable on a TCP port (system-assigned, not
 *    5555) and the other end can run `adb tcpip 5555` itself.
 *
 * All functions here do I/O and block — never call them on the main
 * thread. They are safe to call repeatedly (idempotent): if adbd is
 * already listening on 5555, [enableAdb] does nothing.
 */
object AdbBoot {

    private const val TAG = "AdbBoot"
    const val PORT = 5555
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** The Global setting key for wireless debugging (Android 11+). */
    private const val KEY_ADB_WIFI_ENABLED = "adb_wifi_enabled"

    data class EnableResult(
        /** True if adbd is listening on 5555 after this call. */
        val nowListening: Boolean,
        /** True if `persist.adb.tcp.port` reads back as 5555 — the
         *  permanent fix, so future boots need nothing. */
        val persistSet: Boolean,
        /** Which path actually ran: "shizuku", "wireless-debugging",
         *  "already-on", or "none". */
        val method: String,
        /** Human-readable detail for the status line. */
        val detail: String
    )

    /** Is adbd currently listening on TCP 5555 on this device? A plain
     *  localhost connect — no Shizuku needed. */
    fun isAdbOn5555(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", PORT), 800)
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    /** Is the Shizuku app installed? */
    fun isShizukuInstalled(context: Context): Boolean {
        return context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) != null
    }

    /** Is Shizuku's server running (binder alive)? */
    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.w(TAG, "pingBinder failed", e)
            false
        }
    }

    /** Has the user authorised this app inside Shizuku? */
    fun isShizukuPermissionGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "checkSelfPermission failed", e)
            false
        }
    }

    /** Run one shell command through Shizuku and return its trimmed
     *  stdout (stderr appended on failure). Null if Shizuku is not
     *  usable. Runs as the shell user (uid 2000). */
    private fun runViaShizuku(command: String): String? {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val finished = process.waitFor(20, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return null
            }
            val out = process.inputStream.bufferedReader().readText().trim()
            val err = process.errorStream.bufferedReader().readText().trim()
            if (process.exitValue() != 0 && err.isNotEmpty()) "$out\n$err".trim() else out
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku command failed: $command", e)
            null
        }
    }

    /** Read a system property through Shizuku. Empty string if it is
     *  unset or unreadable. */
    private fun getPropViaShizuku(name: String): String {
        return runViaShizuku("getprop $name") ?: ""
    }

    /**
     * The main entry point: make adbd listen on TCP 5555.
     *
     * 1. Already listening → nothing to do.
     * 2. Shizuku running + authorised → set the persist property (the
     *    permanent fix), set the live property, restart adbd, verify.
     *    If the restart did not take, one retry with an explicit
     *    `stop adbd` / `start adbd` (some builds answer to that better
     *    than `ctl.restart`).
     * 3. No Shizuku → wireless-debugging fallback (Android 11+ only).
     */
    fun enableAdb(context: Context): EnableResult {
        if (isAdbOn5555()) {
            val persist = getPropViaShizuku("persist.adb.tcp.port") == PORT.toString()
            return EnableResult(
                nowListening = true,
                persistSet = persist,
                method = "already-on",
                detail = "ADB is already listening on port 5555."
            )
        }

        if (isShizukuRunning() && isShizukuPermissionGranted()) {
            runViaShizuku(
                "setprop persist.adb.tcp.port $PORT;" +
                    " setprop service.adb.tcp.port $PORT;" +
                    " setprop ctl.restart adbd"
            )
            Thread.sleep(2500)
            if (isAdbOn5555()) {
                return finishWithPersistCheck("shizuku")
            }
            // One retry with an explicit stop/start — some builds do
            // not honour ctl.restart from the shell user.
            Log.i(TAG, "ctl.restart did not take, retrying with stop/start")
            runViaShizuku("stop adbd; start adbd")
            Thread.sleep(2500)
            if (isAdbOn5555()) {
                return finishWithPersistCheck("shizuku")
            }
            return EnableResult(
                nowListening = false,
                persistSet = getPropViaShizuku("persist.adb.tcp.port") == PORT.toString(),
                method = "shizuku",
                detail = "Shizuku ran the commands but port 5555 is still not listening."
            )
        }

        // No Shizuku — try the wireless-debugging fallback.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (enableWirelessDebugging(context)) {
                return EnableResult(
                    nowListening = false,
                    persistSet = false,
                    method = "wireless-debugging",
                    detail = "Shizuku is not running, so wireless debugging was turned back on instead. ADB is reachable on its wireless-debugging port (not fixed 5555) — run `adb tcpip 5555` from a computer once it is connected."
                )
            }
        }
        return EnableResult(
            nowListening = false,
            persistSet = false,
            method = "none",
            detail = "Shizuku is not running. Start Shizuku, allow this app in it, and try again."
        )
    }

    /** After a successful switch, check whether the persist property
     *  stuck — that decides if future boots need this app at all. */
    private fun finishWithPersistCheck(method: String): EnableResult {
        val persist = getPropViaShizuku("persist.adb.tcp.port") == PORT.toString()
        val detail = if (persist) {
            "ADB is now listening on port 5555, and the setting is permanent — it will come back on its own after a reboot."
        } else {
            "ADB is now listening on port 5555 for this boot. (This device would not keep the permanent setting, so the app will run again at the next boot.)"
        }
        return EnableResult(
            nowListening = true,
            persistSet = persist,
            method = method,
            detail = detail
        )
    }

    /** Turn wireless debugging back on (Android 11+). Needs
     *  WRITE_SECURE_SETTINGS, granted once from a PC
     *  (`adb shell pm grant com.opentv.toolbox
     *  android.permission.WRITE_SECURE_SETTINGS`). Returns false if
     *  the permission is not granted. */
    fun enableWirelessDebugging(context: Context): Boolean {
        return try {
            Settings.Global.putInt(
                context.contentResolver, KEY_ADB_WIFI_ENABLED, 1
            )
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted", e)
            false
        }
    }

    /** Is wireless debugging currently on? (Reading the setting needs
     *  no special permission.) False on Android 10 and older, which
     *  do not have the feature. */
    fun isWirelessDebuggingOn(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            Settings.Global.getInt(context.contentResolver, KEY_ADB_WIFI_ENABLED, 0) == 1
        } catch (e: Exception) {
            false
        }
    }
}
