package com.opentv.toolbox.repeater

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device admin receiver. Chuni asked for the app to be a device
 * admin so it stays "on top" and never goes to the background.
 *
 * Honest note: a device admin does NOT keep an app on top, and it
 * does NOT stop Android killing a background process — the
 * foreground service (RepeatService) is what keeps the repeat
 * running in the background. What being a device admin does add is
 * that the app can't be force-stopped or uninstalled until the
 * admin is removed first. The user enables it from the app's
 * "Enable device admin" button (or Settings → Security → Device
 * admin apps); it cannot be turned on silently.
 */
class RepeaterAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        // Nothing to do — the admin role itself is the point.
    }
}
