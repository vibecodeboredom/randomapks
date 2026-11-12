package com.opentv.toolbox.devoptions

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.opentv.toolbox.databinding.ActivityDevoptionsBinding

/**
 * A tiny launcher: opening this app jumps straight to the system
 * Developer options screen, then closes itself. If Developer options
 * are not enabled yet (or the screen can't be opened), it shows a
 * fallback screen with instructions instead of doing nothing.
 */
class DevOptionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDevoptionsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDevoptionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.openSettingsButton.setOnClickListener {
            openSettingsHome()
        }

        // Try to jump straight to Developer options.
        if (!openDeveloperOptions()) {
            // Couldn't open it — show the fallback screen so the user
            // can enable Developer options and try again.
            binding.fallbackLayout.visibility = View.VISIBLE
        } else {
            // We're handing off to Settings; close ourselves so the
            // back button returns to the launcher, not a blank screen.
            finish()
        }
    }

    /** Opens the Developer options screen. Returns false if it can't. */
    private fun openDeveloperOptions(): Boolean {
        return try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun openSettingsHome() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Couldn't open Settings", Toast.LENGTH_SHORT).show()
        }
    }
}
