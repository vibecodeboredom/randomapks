package com.opentv.settingsrepeater

import java.io.File

/**
 * Root (su) support. On a rooted device the repeat loop can open a
 * Settings page from a root shell — `su -c "am start -a <action>"` —
 * instead of firing an Intent from the app. That launch doesn't
 * depend on the app being in the foreground at all: the root shell
 * starts the Settings page even while the app sits in the
 * background. RepeatService uses this when the "Use root" option is
 * on and a su binary is present; otherwise it falls back to the
 * normal Intent path (and the foreground service keeps that alive).
 *
 * Root is auto-detected by looking for a su binary at the usual
 * paths, plus `which su`. The full path to the binary that was
 * found is used, because /su/bin is not always on an app's PATH.
 */
object RootHelper {

    private val suPaths = listOf(
        "/su/bin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/xbin/su"
    )

    /** Full path to a su binary, or null if none is found. */
    fun findSu(): String? {
        for (path in suPaths) {
            if (File(path).exists()) return path
        }
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val out = p.inputStream.bufferedReader().readLine()?.trim()
            p.waitFor()
            out?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    fun isRootAvailable(): Boolean = findSu() != null

    /**
     * Open a Settings page via root: `su -c "am start -a <action>"`.
     * Returns true if the command ran and exited 0. Runs
     * synchronously — call from a background thread, never the
     * main thread.
     */
    fun openSettingsPage(action: String): Boolean {
        val su = findSu() ?: return false
        return try {
            val p = Runtime.getRuntime().exec(arrayOf(su, "-c", "am start -a $action"))
            p.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
