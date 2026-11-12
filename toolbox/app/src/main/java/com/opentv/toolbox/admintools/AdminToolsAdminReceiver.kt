package com.opentv.toolbox.admintools

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device admin receiver for Admin Tools. The app is its own device
 * admin so it can use the DevicePolicyManager features it lists
 * (lock, password rules, camera, keyguard, encryption, permitted
 * accessibility services). The user enables it from the app's
 * "Enable device admin" button (or Settings → Security → Device
 * admin apps); it cannot be turned on silently. Once on, the app
 * can't be force-stopped or uninstalled until the admin is removed
 * first.
 *
 * This receiver deliberately declares no reset/erase policy and the
 * app has no way to erase the device at all.
 */
class AdminToolsAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        // Nothing to do — the admin role itself is the point.
    }
}
