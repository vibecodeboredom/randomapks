package com.opentv.toolbox.admintools

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Lists the Device Admin features and gives each one a button to
 * trigger it. One scrollable screen, big text and buttons, remote /
 * DPAD friendly — same style as the other usefulapks apps. No ads,
 * no tracking, no network.
 *
 * The app is its own device admin (AdminReceiver). Every feature
 * button stays disabled until the app is an active admin — the
 * "Enable device admin" button opens the system Activate prompt,
 * which cannot be turned on silently. Once on, the app can't be
 * force-stopped or uninstalled until the admin is removed first.
 *
 * Every admin call is guarded with a try/catch and shows a toast on
 * failure instead of crashing — many of these are deprecated or
 * restricted on newer Android and may throw SecurityException.
 *
 * Deliberately NOT here: there is no way to erase the device in
 * this app at all.
 */
class AdminToolsActivity : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName

    private lateinit var statusText: TextView
    private lateinit var adminButton: Button
    private lateinit var lockButton: Button
    private lateinit var passwordInput: EditText
    private lateinit var passwordButton: Button
    private lateinit var qualitySpinner: Spinner
    private lateinit var qualityButton: Button
    private lateinit var lengthInput: EditText
    private lateinit var lengthButton: Button
    private lateinit var maxLockInput: EditText
    private lateinit var maxLockButton: Button
    private lateinit var expiryInput: EditText
    private lateinit var expiryButton: Button
    private lateinit var cameraSwitch: SwitchCompat
    private lateinit var keyguardWidgets: CheckBox
    private lateinit var keyguardCamera: CheckBox
    private lateinit var keyguardFingerprint: CheckBox
    private lateinit var keyguardFace: CheckBox
    private lateinit var keyguardTrust: CheckBox
    private lateinit var keyguardButton: Button
    private lateinit var encryptionButton: Button
    private lateinit var a11yInput: EditText
    private lateinit var a11yApplyButton: Button
    private lateinit var a11yClearButton: Button

    /** True while refreshAdminState is setting control values, so
     * the camera switch listener doesn't fire on a programmatic set. */
    private var updatingUi = false

    /** Password quality choices, in spinner order. */
    private val qualities = listOf(
        "None" to DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED,
        "Numeric" to DevicePolicyManager.PASSWORD_QUALITY_NUMERIC,
        "Alphabetic" to DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC,
        "Alphanumeric" to DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC,
        "Complex" to DevicePolicyManager.PASSWORD_QUALITY_COMPLEX
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admintools)

        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, AdminToolsAdminReceiver::class.java)

        statusText = findViewById(R.id.status_text)
        adminButton = findViewById(R.id.admin_button)
        lockButton = findViewById(R.id.lock_button)
        passwordInput = findViewById(R.id.password_input)
        passwordButton = findViewById(R.id.password_button)
        qualitySpinner = findViewById(R.id.quality_spinner)
        qualityButton = findViewById(R.id.quality_button)
        lengthInput = findViewById(R.id.length_input)
        lengthButton = findViewById(R.id.length_button)
        maxLockInput = findViewById(R.id.max_lock_input)
        maxLockButton = findViewById(R.id.max_lock_button)
        expiryInput = findViewById(R.id.expiry_input)
        expiryButton = findViewById(R.id.expiry_button)
        cameraSwitch = findViewById(R.id.camera_switch)
        keyguardWidgets = findViewById(R.id.keyguard_widgets)
        keyguardCamera = findViewById(R.id.keyguard_camera)
        keyguardFingerprint = findViewById(R.id.keyguard_fingerprint)
        keyguardFace = findViewById(R.id.keyguard_face)
        keyguardTrust = findViewById(R.id.keyguard_trust)
        keyguardButton = findViewById(R.id.keyguard_button)
        encryptionButton = findViewById(R.id.encryption_button)
        a11yInput = findViewById(R.id.a11y_input)
        a11yApplyButton = findViewById(R.id.a11y_apply_button)
        a11yClearButton = findViewById(R.id.a11y_clear_button)

        val qualityAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            qualities.map { it.first }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        qualitySpinner.adapter = qualityAdapter

        adminButton.setOnClickListener { enableAdmin() }
        lockButton.setOnClickListener { lockNow() }
        passwordButton.setOnClickListener { setPassword() }
        qualityButton.setOnClickListener { setPasswordQuality() }
        lengthButton.setOnClickListener { setPasswordLength() }
        maxLockButton.setOnClickListener { setMaxTimeToLock() }
        expiryButton.setOnClickListener { setPasswordExpiry() }
        cameraSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) setCameraDisabled(checked)
        }
        keyguardButton.setOnClickListener { setKeyguardFeatures() }
        encryptionButton.setOnClickListener { requireEncryption() }
        a11yApplyButton.setOnClickListener { setPermittedA11y() }
        a11yClearButton.setOnClickListener { clearPermittedA11y() }

        refreshAdminState()
        // Focus the admin button so a remote works straight away.
        adminButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // The user may have just come back from the Activate prompt.
        refreshAdminState()
    }

    /** Device admin: opens the system prompt to make this app a
     * device admin. It cannot be turned on silently — the user has
     * to tap Activate. */
    private fun enableAdmin() {
        if (dpm.isAdminActive(admin)) {
            Toast.makeText(this, "Already a device admin", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "So Admin Tools can use the Device Admin features it lists."
            )
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("Couldn't open the device admin prompt")
        }
    }

    /** Enable/disable the feature controls from the admin state, and
     * prefill the current values where the device lets us read them. */
    private fun refreshAdminState() {
        val isAdmin = try {
            dpm.isAdminActive(admin)
        } catch (e: Exception) {
            false
        }
        statusText.text =
            if (isAdmin) getString(R.string.at_status_admin)
            else getString(R.string.at_status_not_admin)

        val featureControls = listOf(
            lockButton, passwordInput, passwordButton,
            qualitySpinner, qualityButton,
            lengthInput, lengthButton,
            maxLockInput, maxLockButton,
            expiryInput, expiryButton,
            cameraSwitch,
            keyguardWidgets, keyguardCamera, keyguardFingerprint,
            keyguardFace, keyguardTrust, keyguardButton,
            encryptionButton,
            a11yInput, a11yApplyButton, a11yClearButton
        )
        featureControls.forEach { it.isEnabled = isAdmin }

        if (!isAdmin) return
        // Prefill current values — best effort, never fatal.
        try {
            updatingUi = true
            val length = dpm.getPasswordMinimumLength(admin)
            if (length > 0) lengthInput.setText(length.toString())
            val maxLockMs = dpm.getMaximumTimeToLock(admin)
            if (maxLockMs > 0) maxLockInput.setText((maxLockMs / 1000).toString())
            val expiryMs = dpm.getPasswordExpirationTimeout(admin)
            if (expiryMs > 0) expiryInput.setText((expiryMs / 86_400_000).toString())
            val quality = dpm.getPasswordQuality(admin)
            val qualityPos = qualities.indexOfFirst { it.second == quality }
            if (qualityPos >= 0) qualitySpinner.setSelection(qualityPos, false)
            cameraSwitch.isChecked = dpm.getCameraDisabled(admin)
            val flags = dpm.getKeyguardDisabledFeatures(admin)
            keyguardWidgets.isChecked =
                flags and DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL != 0
            keyguardCamera.isChecked =
                flags and DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA != 0
            keyguardFingerprint.isChecked =
                flags and DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT != 0
            keyguardFace.isChecked =
                flags and DevicePolicyManager.KEYGUARD_DISABLE_FACE != 0
            keyguardTrust.isChecked =
                flags and DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS != 0
            val permitted = dpm.getPermittedAccessibilityServices(admin)
            if (permitted != null) a11yInput.setText(permitted.joinToString(", "))
        } catch (e: Exception) {
            // A value we can't read just leaves its default.
        } finally {
            updatingUi = false
        }
    }

    // ---- Features (each guarded; a failure is a toast, never a crash) ----

    private fun lockNow() {
        try {
            dpm.lockNow()
            done("Device locked")
        } catch (e: Exception) {
            fail("Couldn't lock the device")
        }
    }

    private fun setPassword() {
        val password = passwordInput.text.toString()
        if (password.isEmpty()) {
            toast("Enter a password first")
            return
        }
        try {
            // Deprecated on newer Android and may fail if a lock
            // screen is already set — the result tells us.
            val ok = dpm.resetPassword(password, 0)
            if (ok) done("Password set") else fail("Couldn't set password on this device")
        } catch (e: Exception) {
            fail("Couldn't set password on this device")
        }
    }

    private fun setPasswordQuality() {
        val quality = qualities[qualitySpinner.selectedItemPosition].second
        try {
            dpm.setPasswordQuality(admin, quality)
            done("Password quality applied")
        } catch (e: Exception) {
            fail("Couldn't set password quality on this device")
        }
    }

    private fun setPasswordLength() {
        val n = lengthInput.text.toString().trim().toIntOrNull()
        if (n == null || n < 0) {
            toast("Enter a length — 0 or more")
            return
        }
        try {
            dpm.setPasswordMinimumLength(admin, n)
            done("Minimum password length applied")
        } catch (e: Exception) {
            fail("Couldn't set password length on this device")
        }
    }

    private fun setMaxTimeToLock() {
        val seconds = maxLockInput.text.toString().trim().toLongOrNull()
        if (seconds == null || seconds < 0) {
            toast("Enter seconds — 0 or more")
            return
        }
        try {
            dpm.setMaximumTimeToLock(admin, seconds * 1000)
            done("Maximum time to lock applied")
        } catch (e: Exception) {
            fail("Couldn't set maximum time to lock on this device")
        }
    }

    private fun setPasswordExpiry() {
        val days = expiryInput.text.toString().trim().toLongOrNull()
        if (days == null || days < 0) {
            toast("Enter days — 0 or more (0 = never)")
            return
        }
        try {
            dpm.setPasswordExpirationTimeout(admin, days * 86_400_000)
            done("Password expiration applied")
        } catch (e: Exception) {
            fail("Couldn't set password expiration on this device")
        }
    }

    private fun setCameraDisabled(disabled: Boolean) {
        try {
            dpm.setCameraDisabled(admin, disabled)
            done(if (disabled) "Camera disabled" else "Camera enabled")
        } catch (e: Exception) {
            fail("Couldn't change the camera setting on this device")
            // Put the switch back to the real state.
            refreshAdminState()
        }
    }

    private fun setKeyguardFeatures() {
        var flags = 0
        if (keyguardWidgets.isChecked) flags =
            flags or DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL
        if (keyguardCamera.isChecked) flags =
            flags or DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA
        if (keyguardFingerprint.isChecked) flags =
            flags or DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT
        if (keyguardFace.isChecked) flags =
            flags or DevicePolicyManager.KEYGUARD_DISABLE_FACE
        if (keyguardTrust.isChecked) flags =
            flags or DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS
        try {
            dpm.setKeyguardDisabledFeatures(admin, flags)
            done("Keyguard features applied")
        } catch (e: Exception) {
            fail("Couldn't set keyguard features on this device")
        }
    }

    private fun requireEncryption() {
        try {
            // Deprecated on newer Android; the call itself is the
            // request, and the device may already be encrypted.
            dpm.setStorageEncryption(admin, true)
            done("Encryption requested")
        } catch (e: Exception) {
            fail("Couldn't require encryption on this device")
        }
    }

    private fun setPermittedA11y() {
        val text = a11yInput.text.toString().trim()
        val packages = text.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        try {
            // This one needs a device or profile owner — a plain
            // device admin will get a SecurityException, which is
            // why the failure message says so.
            val ok = dpm.setPermittedAccessibilityServices(admin, packages)
            if (ok) done("Accessibility restriction applied")
            else fail("Couldn't restrict accessibility services — needs a device or profile owner")
        } catch (e: Exception) {
            fail("Couldn't restrict accessibility services — needs a device or profile owner")
        }
    }

    private fun clearPermittedA11y() {
        try {
            val ok = dpm.setPermittedAccessibilityServices(admin, null)
            if (ok) {
                a11yInput.setText("")
                done("Accessibility restriction cleared")
            } else {
                fail("Couldn't clear the restriction — needs a device or profile owner")
            }
        } catch (e: Exception) {
            fail("Couldn't clear the restriction — needs a device or profile owner")
        }
    }

    private fun done(message: String) {
        statusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun fail(message: String) {
        statusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
