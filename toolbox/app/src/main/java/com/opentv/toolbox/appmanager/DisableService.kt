package com.opentv.toolbox.appmanager

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Enables / disables apps without ADB, using an accessibility service.
 *
 * This is the FALLBACK path — App Manager prefers root (`su -c "pm
 * disable-user --user 0 <pkg>"` / `su -c "pm enable <pkg>"`) whenever
 * a su binary is visible to the app, and only uses this service when
 * there is genuinely no root (e.g. the A16, where KernelSU hides su
 * from apps). AppManagerActivity decides which path to take per toggle.
 *
 * How the fallback works: for each job it opens that app's App Info
 * page directly (Settings.ACTION_APPLICATION_DETAILS_SETTINGS) and
 * taps the "Disable" or "Enable" button, then confirms the dialog.
 * This only works where the Settings screen itself offers the button
 * — if an app's Disable button is greyed out (system-critical), it
 * stays enabled and is reported as skipped.
 *
 * What this is NOT: a Device Admin. A normal app cannot gain the
 * power to enable/disable other apps just by asking — that power
 * belongs to the Device Owner, which can only be set via ADB or a
 * factory reset. This service just automates the taps the user would
 * make by hand.
 */
class DisableService : AccessibilityService() {

    /** One enable/disable job for the queue. */
    data class Job(val packageName: String, val enable: Boolean)

    companion object {
        const val ACTION_PROGRESS = "com.opentv.toolbox.appmanager.PROGRESS"
        const val ACTION_DONE = "com.opentv.toolbox.appmanager.DONE"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DETAIL = "detail"

        const val STATUS_DISABLED = "disabled"
        const val STATUS_ENABLED = "enabled"
        const val STATUS_SKIPPED = "skipped"

        private val queue = ArrayDeque<Job>()
        private var running = false

        /** True when the user has turned this service on. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.contains(context.packageName)
        }

        /**
         * Starts enabling/disabling [jobs]. Call from AppManagerActivity.
         * If a run is already in progress, the new jobs are appended.
         */
        fun startJobs(context: Context, jobs: List<Job>) {
            synchronized(queue) {
                queue.addAll(jobs)
            }
            if (!running) {
                running = true
                instance?.beginNext()
                    ?: Toast.makeText(
                        context,
                        "Turn on the Toolbox accessibility service first",
                        Toast.LENGTH_LONG
                    ).show().also { running = false }
            }
        }

        /** Starts disabling [packages] via the accessibility fallback. */
        fun startDisabling(context: Context, packages: List<String>) {
            startJobs(context, packages.map { Job(it, enable = false) })
        }

        /** Starts enabling [packages] via the accessibility fallback. */
        fun startEnabling(context: Context, packages: List<String>) {
            startJobs(context, packages.map { Job(it, enable = true) })
        }

        private var instance: DisableService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var currentJob: Job? = null
    private var attempts = 0

    private enum class State {
        IDLE, OPENING_APP_INFO, WAITING_BUTTON, WAITING_DIALOG
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (running) beginNext()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!running) return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        // Let the UI settle, then act on the current window.
        handler.removeCallbacks(::processCurrentWindow)
        handler.postDelayed(::processCurrentWindow, 500)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(::processCurrentWindow)
    }

    private fun beginNext() {
        val next = synchronized(queue) {
            if (queue.isEmpty()) null else queue.removeFirst()
        }
        if (next == null) {
            running = false
            state = State.IDLE
            sendBroadcast(Intent(ACTION_DONE))
            return
        }
        currentJob = next
        attempts = 0
        state = State.OPENING_APP_INFO
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${next.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            report(next.packageName, STATUS_SKIPPED, "couldn't open App Info on this device")
            beginNext()
            return
        }
        state = State.WAITING_BUTTON
    }

    private fun processCurrentWindow() {
        if (!running) return
        val root = rootInActiveWindow ?: return
        when (state) {
            State.WAITING_BUTTON -> handleAppInfoPage(root)
            State.WAITING_DIALOG -> handleDialog(root)
            else -> Unit
        }
    }

    /**
     * On the App Info page: tap Disable (disable job) or Enable
     * (enable job), or report the app as already in the wanted state
     * (the button reads the other way round when it is).
     */
    private fun handleAppInfoPage(root: AccessibilityNodeInfo) {
        val job = currentJob ?: return
        val pkg = job.packageName
        val targetButton = if (job.enable) "Enable" else "Disable"
        val currentButton = if (job.enable) "Disable" else "Enable"
        val target = findButton(root, targetButton)
        val current = findButton(root, currentButton)
        if (target == null && current == null) {
            // Neither button yet (page still loading) — retry a few times.
            attempts++
            if (attempts < 6) {
                handler.postDelayed(::processCurrentWindow, 600)
            } else {
                report(pkg, STATUS_SKIPPED, "no Disable/Enable button (system app?)")
                beginNext()
            }
            return
        }
        if (target == null) {
            // Only the opposite button is showing — the app is already
            // in the wanted state, so report its true state.
            val doneStatus = if (job.enable) STATUS_ENABLED else STATUS_DISABLED
            report(pkg, doneStatus, "")
            beginNext()
            return
        }
        if (target.isClickable && target.isEnabled) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            state = State.WAITING_DIALOG
            attempts = 0
            handler.postDelayed(::processCurrentWindow, 700)
            return
        }
        attempts++
        if (attempts < 6) {
            handler.postDelayed(::processCurrentWindow, 600)
        } else {
            report(pkg, STATUS_SKIPPED, "no $targetButton button (system app?)")
            beginNext()
        }
    }

    /**
     * After tapping Disable/Enable: confirm the dialog, or note that
     * the button already flipped to the wanted state (some apps
     * toggle with no dialog).
     */
    private fun handleDialog(root: AccessibilityNodeInfo) {
        val job = currentJob ?: return
        val pkg = job.packageName
        val doneButton = if (job.enable) "Disable" else "Enable"
        val doneStatus = if (job.enable) STATUS_ENABLED else STATUS_DISABLED
        // The button already flipped to the post-toggle state.
        if (findButton(root, doneButton) != null) {
            report(pkg, doneStatus, "")
            beginNext()
            return
        }
        val targetButton = if (job.enable) "Enable" else "Disable"
        val confirm = findButton(root, targetButton) ?: findButton(root, "OK")
        if (confirm != null && confirm.isClickable && confirm.isEnabled) {
            confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            report(pkg, doneStatus, "")
            beginNext()
            return
        }
        attempts++
        if (attempts < 6) {
            handler.postDelayed(::processCurrentWindow, 600)
        } else {
            // Couldn't confirm — check whether it still took effect.
            report(pkg, STATUS_SKIPPED, "couldn't confirm the dialog")
            beginNext()
        }
    }

    /** Finds a clickable Button with the exact [text] in this window. */
    private fun findButton(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(text) ?: return null
        for (node in nodes) {
            if (node.className == "android.widget.Button" && node.text?.toString() == text) {
                return node
            }
        }
        return null
    }

    private fun report(pkg: String, status: String, detail: String) {
        val intent = Intent(ACTION_PROGRESS).apply {
            putExtra(EXTRA_PACKAGE, pkg)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_DETAIL, detail)
        }
        sendBroadcast(intent)
    }
}
