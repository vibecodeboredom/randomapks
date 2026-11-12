package com.opentv.toolbox.repeater

import android.provider.Settings

/** The Settings pages the app can open. Shared by MainActivity (the
 * picker) and RepeatService (the random pick), so the two stay in
 * sync. Mirrors Settings Launcher's shortcuts (same repo), plus
 * Battery saver, which it doesn't have. */
object SettingsPages {
    data class Page(val label: String, val action: String?)

    val pages = listOf(
        Page("Settings home", Settings.ACTION_SETTINGS),
        Page("Wi-Fi", Settings.ACTION_WIFI_SETTINGS),
        Page("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS),
        Page("Network & Internet", Settings.ACTION_WIRELESS_SETTINGS),
        Page("Apps", Settings.ACTION_APPLICATION_SETTINGS),
        Page("Display", Settings.ACTION_DISPLAY_SETTINGS),
        Page("Sound", Settings.ACTION_SOUND_SETTINGS),
        Page("Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        Page("Security", Settings.ACTION_SECURITY_SETTINGS),
        Page("Developer options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Page("About phone", Settings.ACTION_DEVICE_INFO_SETTINGS),
        Page("Date & time", Settings.ACTION_DATE_SETTINGS),
        Page("Language", Settings.ACTION_LOCALE_SETTINGS),
        Page("Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Page("Battery saver", Settings.ACTION_BATTERY_SAVER_SETTINGS),
        Page("Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Page("Home app", Settings.ACTION_HOME_SETTINGS),
        // action = null: the custom action field supplies it.
        Page("Custom…", null)
    )

    /** Pages that can actually be opened (excludes "Custom…"). */
    val openablePages: List<Page> get() = pages.filter { it.action != null }
}
