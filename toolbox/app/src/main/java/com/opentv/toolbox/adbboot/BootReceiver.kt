package com.opentv.toolbox.adbboot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-enables ADB on TCP 5555 after a reboot.
 *
 * Shizuku itself may not be up yet when BOOT_COMPLETED fires (on
 * Android 13+ it auto-starts on a trusted Wi-Fi network, but not
 * instantly), so this retries for a few minutes: if adbd is already
 * on 5555 it stops at once; otherwise it tries the Shizuku switch
 * each round and the wireless-debugging fallback in between. It is
 * idempotent — running it when everything is already on is a no-op.
 *
 * On Android 11/12 Shizuku does not auto-start without root, and on
 * Android 9 there is no wireless debugging at all, so on those
 * devices this receiver can only help once the one-time
 * `persist.adb.tcp.port` setting has been applied (see README.md).
 * It still runs harmlessly and exits quickly in that case.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AdbBoot"
        private const val MAX_ATTEMPTS = 12
        private const val RETRY_DELAY_MS = 15_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
            try {
                runWithRetries(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun runWithRetries(context: Context) {
        repeat(MAX_ATTEMPTS) { attempt ->
            if (AdbBoot.isAdbOn5555()) {
                Log.i(TAG, "Boot: ADB already on 5555, nothing to do.")
                return
            }
            val result = AdbBoot.enableAdb(context)
            Log.i(TAG, "Boot: attempt ${attempt + 1}: ${result.method} — ${result.detail}")
            if (result.nowListening) return
            // Shizuku may still be starting — wait and try again.
            // (On the last attempt there is no point sleeping.)
            if (attempt < MAX_ATTEMPTS - 1) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
        Log.i(TAG, "Boot: gave up after $MAX_ATTEMPTS attempts.")
    }
}
