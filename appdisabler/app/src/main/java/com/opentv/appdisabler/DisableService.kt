package com.opentv.appdisabler

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
 * Disables apps without ADB, using an accessibility service.
 *
 * How it works: for each package the user picked, it opens that app's
 * App Info page directly (Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
 * and taps the "Disable" button, then confirms the dialog. This only
 * works where the Settings screen itself offers Disable — if an app's
 * Disable button is greyed out (system-critical), it stays enabled and
 * is reported as skipped.
 *
 * What this is NOT: a Device Admin. A normal app cannot gain the power
 * to disable other apps just by asking — that power belongs to the
 * Device Owner, which can only be set via ADB or a factory reset. This
 * service just automates the taps the user would make by hand.
 */
class DisableService : AccessibilityService() {

    companion object {
        const val ACTION_PROGRESS = "com.opentv.appdisabler.PROGRESS"
        const val ACTION_DONE = "com.opentv.appdisabler.DONE"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_STATUS = "status"
        const val EXTRA_DETAIL = "detail"

        const val STATUS_DISABLED = "disabled"
        const val STATUS_SKIPPED = "skipped"

        private val queue = ArrayDeque<String>()
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
         * Starts disabling [packages]. Call from MainActivity. If a run
         * is already in progress, the new packages are appended to it.
         */
        fun startDisabling(context: Context, packages: List<String>) {
            synchronized(queue) {
                queue.addAll(packages)
            }
            if (!running) {
                running = true
                instance?.beginNext()
                    ?: Toast.makeText(
                        context,
                        "Turn on the App Disabler accessibility service first",
                        Toast.LENGTH_LONG
                    ).show().also { running = false }
            }
        }

        private var instance: DisableService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var currentPackage: String? = null
    private var attempts = 0

    private enum class State {
        IDLE, OPENING_APP_INFO, WAITING_DISABLE_BUTTON, WAITING_DIALOG
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
        currentPackage = next
        attempts = 0
        state = State.OPENING_APP_INFO
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$next")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        state = State.WAITING_DISABLE_BUTTON
    }

    private fun processCurrentWindow() {
        if (!running) return
        val root = rootInActiveWindow ?: return
        when (state) {
            State.WAITING_DISABLE_BUTTON -> handleAppInfoPage(root)
            State.WAITING_DIALOG -> handleDialog(root)
            else -> Unit
        }
    }

    /** On the App Info page: tap Disable, or skip if already disabled. */
    private fun handleAppInfoPage(root: AccessibilityNodeInfo) {
        val pkg = currentPackage ?: return
        // Already disabled — the button reads "Enable" instead.
        if (findButton(root, "Enable") != null) {
            report(pkg, STATUS_SKIPPED, "already disabled")
            beginNext()
            return
        }
        val disable = findButton(root, "Disable")
        if (disable != null && disable.isClickable && disable.isEnabled) {
            disable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            state = State.WAITING_DIALOG
            attempts = 0
            handler.postDelayed(::processCurrentWindow, 700)
            return
        }
        // Button not there yet (page still loading) — retry a few times.
        attempts++
        if (attempts < 6) {
            handler.postDelayed(::processCurrentWindow, 600)
        } else {
            report(pkg, STATUS_SKIPPED, "no Disable button (system app?)")
            beginNext()
        }
    }

    /** After tapping Disable: confirm the dialog, or note it worked. */
    private fun handleDialog(root: AccessibilityNodeInfo) {
        val pkg = currentPackage ?: return
        // Some apps disable with no dialog — the button flips to Enable.
        if (findButton(root, "Enable") != null) {
            report(pkg, STATUS_DISABLED, "")
            beginNext()
            return
        }
        val confirm = findButton(root, "Disable") ?: findButton(root, "OK")
        if (confirm != null && confirm.isClickable && confirm.isEnabled) {
            confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            report(pkg, STATUS_DISABLED, "")
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
