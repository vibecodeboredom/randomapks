package com.opentv.toolbox.appmanager

import java.io.File

/**
 * Root (su) support. App Manager prefers root for toggling apps —
 * it is fast and reliable, and it works for apps whose Disable
 * button Settings greys out:
 *
 * - Disable: `su -c "pm disable-user --user 0 <package>"`
 * - Enable:  `su -c "pm enable <package>"`
 *
 * Root is auto-detected by looking for a su binary at the usual
 * paths, plus `which su`. The full path to the binary that was found
 * is used, because /su/bin is not always on an app's PATH (on the
 * LG V410 the binary lives at /su/bin/su).
 *
 * Note: on some rooted devices root is hidden from apps (the A16's
 * DirtyFrag/KernelSU root hides su from the app UID via mount
 * namespaces), so isRootAvailable() is false there even though the
 * device is rooted — the accessibility-service fallback is used
 * instead. Every root call runs synchronously — call from a
 * background thread, never the main thread.
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
     * Enable or disable a package as root. Returns true when the
     * command ran and exited 0. The first su call can hang on
     * SuperSU's "grant?" prompt — the caller runs this on a
     * background thread with the UI showing a working state.
     */
    fun setPackageEnabled(packageName: String, enabled: Boolean): Boolean {
        val su = findSu() ?: return false
        val command = if (enabled) {
            "pm enable $packageName"
        } else {
            "pm disable-user --user 0 $packageName"
        }
        return try {
            val p = Runtime.getRuntime().exec(arrayOf(su, "-c", command))
            p.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
