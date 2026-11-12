package com.opentv.toolbox.appkiller

import java.io.File

/**
 * Root (su) support. App Killer needs root for everything it does:
 *
 * - Finding the foreground app: `su -c "dumpsys activity activities"`
 *   and parsing the resumed / focused ActivityRecord for its package.
 *   (dumpsys without root only shows the caller's own activities.)
 * - Killing the foreground app: `su -c "am force-stop <package>"`.
 *
 * Root is auto-detected by looking for a su binary at the usual
 * paths, plus `which su`. The full path to the binary that was found
 * is used, because /su/bin is not always on an app's PATH (on the
 * LG V410 the binary lives at /su/bin/su).
 *
 * Every root call runs synchronously — call from a background thread,
 * never the main thread.
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
     * Run one shell command as root and return its stdout (or null on
     * failure). The first su call can hang on SuperSU's "grant?"
     * prompt, so the caller should run this on a thread with a
     * timeout if it must not block.
     */
    fun runRootCommand(command: String): String? {
        val su = findSu() ?: return null
        return try {
            val p = Runtime.getRuntime().exec(arrayOf(su, "-c", command))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            if (p.exitValue() == 0) out else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Force-stop a package as root: `su -c "am force-stop <package>"`.
     * Returns true if the command ran and exited 0.
     */
    fun forceStop(packageName: String): Boolean {
        val su = findSu() ?: return false
        return try {
            val p = Runtime.getRuntime()
                .exec(arrayOf(su, "-c", "am force-stop $packageName"))
            p.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The package of the app currently in the foreground, via root
     * dumpsys. Parses the resumed / focused ActivityRecord out of
     * `dumpsys activity activities`:
     *
     *   ResumedActivity: ActivityRecord{... u0 com.example.app/.Main t12}
     *   mFocusedActivity: ActivityRecord{... u0 com.example.app/.Main t12}
     *
     * (Older Androids spell it mResumedActivity.) Returns null when
     * dumpsys fails or nothing parses — the caller falls back to
     * UsageStatsManager in that case.
     */
    fun getForegroundPackage(): String? {
        val out = runRootCommand("dumpsys activity activities") ?: return null
        for (line in out.lines()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("ResumedActivity:") &&
                !trimmed.startsWith("mFocusedActivity:") &&
                !trimmed.startsWith("mResumedActivity:")
            ) continue
            // ActivityRecord{token u0 package/class ...} — the package
            // is the token right after the "u0 " user marker.
            val record = trimmed.substringAfter("ActivityRecord{", "")
            if (record.isEmpty()) continue
            val afterUser = record.substringAfter(" u0 ", "")
            if (afterUser.isEmpty() || afterUser == record) continue
            val pkg = afterUser.substringBefore(" ").substringBefore("/")
            if (pkg.isNotEmpty() && pkg.contains(".")) return pkg
        }
        return null
    }
}
