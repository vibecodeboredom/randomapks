package com.opentv.notifspammer

import java.io.File

/**
 * Root (su) support. On a rooted device the spam loop can post each
 * notification from a root shell — `su -c "cmd notification post
 * -t '<title>' '<tag>' '<text>'"` — instead of going through the
 * app's own NotificationManager. Posting as the shell bypasses the
 * per-app notification rate limit, so it can spam faster than the
 * normal path, and it works even while the app sits in the
 * background. SpamService uses this when the "Use root" option is
 * on and a su binary is present; otherwise it falls back to the
 * normal NotificationManager path.
 *
 * `cmd notification post` only exists from Android 8.0 (API 26) —
 * on the LG's Android 7.1.2 it answers "No shell command
 * implementation" and the command fails, so the service falls back
 * to the normal path for that notification instead of crashing.
 * The tag is unique per notification (spam-1, spam-2, …) so the
 * posts stack in the shade instead of replacing each other.
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

    /** Shell-quote one argument: wrap in single quotes, escaping any
     * single quote inside as '\''. User-typed titles and messages
     * can contain anything, so they must never be pasted into a
     * shell command unquoted. */
    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    /**
     * Post one notification via root: `su -c "cmd notification post
     * -t '<title>' '<tag>' '<text>'"`. Returns true if the command
     * ran and exited 0. Runs synchronously — call from a background
     * thread, never the main thread. Returns false on Android 7
     * (no `cmd notification` command) and on any other failure, so
     * the caller can fall back to the normal path.
     */
    fun postNotification(title: String, tag: String, text: String): Boolean {
        val su = findSu() ?: return false
        return try {
            val cmd = "cmd notification post -t ${shellQuote(title)} " +
                "${shellQuote(tag)} ${shellQuote(text)}"
            val p = Runtime.getRuntime().exec(arrayOf(su, "-c", cmd))
            p.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
