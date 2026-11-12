package com.opentv.appdisabler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import java.util.concurrent.Executors

/**
 * App Manager: enable or disable any installed app, from one list.
 *
 * Toggle method — ROOT FIRST. At startup the app checks for a su
 * binary; whenever one is present, every toggle goes through root
 * (`su -c "pm disable-user --user 0 <pkg>"` to disable,
 * `su -c "pm enable <pkg>"` to enable). Root is fast, reliable, and
 * works even where Settings greys the button out. Only when there
 * is genuinely no su binary does a toggle fall back to the
 * accessibility service, which opens the app's Settings page and
 * taps Disable/Enable there (that needs the App Manager
 * accessibility service turned on — the Setup card walks the user
 * through it). On the A16 root is hidden from apps by KernelSU, so
 * the accessibility path is the one that runs there.
 *
 * The list shows every installed app (system + user). Each row's
 * icon is the state indicator — a circle split diagonally 50/50,
 * red ✕ half for disabled, green ✓ half for enabled, the matching
 * half lit — next to the app name and a switch. A search box
 * filters by name or package, and "Disabled only" shows just the
 * disabled ones. The list loads on a background thread so a 400+
 * app list stays fast. App Manager never lists itself — toggling
 * it would kill the app.
 */
class MainActivity : AppCompatActivity() {

    private data class AppEntry(
        val label: String,
        val packageName: String,
        var disabled: Boolean
    )

    private val allEntries = mutableListOf<AppEntry>()
    private val shownEntries = mutableListOf<AppEntry>()
    private lateinit var adapter: AppAdapter
    private lateinit var statusView: TextView
    private lateinit var countView: TextView
    private lateinit var rootStatusView: TextView

    /** Root, checked once at startup. Null until the check finishes. */
    private var rootAvailable: Boolean? = null
    private var rootSuPath: String? = null

    private var searchQuery = ""
    private var disabledOnly = false
    private val background = Executors.newSingleThreadExecutor()

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (intent.action != DisableService.ACTION_PROGRESS &&
                intent.action != DisableService.ACTION_DONE
            ) return
            if (intent.action == DisableService.ACTION_DONE) {
                statusView.text = "Done — list refreshed"
                return
            }
            val pkg = intent.getStringExtra(DisableService.EXTRA_PACKAGE) ?: return
            val status = intent.getStringExtra(DisableService.EXTRA_STATUS) ?: return
            val detail = intent.getStringExtra(DisableService.EXTRA_DETAIL) ?: ""
            val entry = allEntries.find { it.packageName == pkg } ?: return
            when (status) {
                DisableService.STATUS_DISABLED -> {
                    entry.disabled = true
                    statusView.text = "✓ Disabled ${entry.label}"
                }
                DisableService.STATUS_ENABLED -> {
                    entry.disabled = false
                    statusView.text = "✓ Enabled ${entry.label}"
                }
                else -> {
                    statusView.text =
                        "– Skipped ${entry.label}${if (detail.isNotEmpty()) " ($detail)" else ""}"
                }
            }
            refreshEntry(entry)
            updateCount()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status_text)
        countView = findViewById(R.id.list_count)
        rootStatusView = findViewById(R.id.root_status)
        val searchBox = findViewById<TextInputEditText>(R.id.search_box)
        val disabledOnlyBox = findViewById<MaterialCheckBox>(R.id.disabled_only)
        val accessButton = findViewById<android.widget.Button>(R.id.accessibility_button)
        val recycler = findViewById<RecyclerView>(R.id.app_list)

        adapter = AppAdapter(shownEntries) { entry, enable ->
            onToggle(entry, enable)
        }
        recycler.layoutManager = LinearLayoutManager(this)
        // Rows are a fixed height, so the list can skip measuring
        // them on every scroll — keeps 400+ rows smooth.
        recycler.setHasFixedSize(true)
        recycler.adapter = adapter

        // Root check at startup, off the main thread — findSu() runs
        // `which su`, which must not block the UI.
        rootStatusView.text = "Checking for root…"
        background.execute {
            val su = RootHelper.findSu()
            rootAvailable = su != null
            rootSuPath = su
            runOnUiThread {
                rootStatusView.text = if (su != null) {
                    "Root: available (su at $su) — toggles use pm directly"
                } else {
                    "Root: not found — toggles use the accessibility service"
                }
            }
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim()?.lowercase() ?: ""
                applyFilter()
            }
        })

        disabledOnlyBox.setOnCheckedChangeListener { _, checked ->
            disabledOnly = checked
            applyFilter()
        }

        accessButton.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(
                    this,
                    "Turn on App Manager under Accessibility, then come back",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    "Couldn't open Accessibility settings on this device",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(DisableService.ACTION_PROGRESS)
            addAction(DisableService.ACTION_DONE)
        }
        // The 3-arg registerReceiver(receiver, filter, flags) only
        // exists from Android 13 (API 33); on older versions the
        // 2-arg call is the only one. On Android 14+ the flag is
        // required — without it onResume throws a SecurityException
        // and the app crashes on launch.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(progressReceiver, filter)
        }
        // Re-check root in case it appeared since last time (e.g. the
        // user just granted SuperSU). Cheap enough to redo here.
        background.execute {
            val su = RootHelper.findSu()
            rootAvailable = su != null
            rootSuPath = su
            runOnUiThread {
                rootStatusView.text = if (su != null) {
                    "Root: available (su at $su) — toggles use pm directly"
                } else {
                    "Root: not found — toggles use the accessibility service"
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(progressReceiver)
        } catch (_: IllegalArgumentException) {
            // Not registered — nothing to do.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        background.shutdown()
    }

    /** Loads every installed app on a background thread, then shows it. */
    private fun loadApps() {
        statusView.text = "Loading apps…"
        background.execute {
            val pm = packageManager
            val apps = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            } catch (e: Exception) {
                emptyList<ApplicationInfo>()
            }
            val loaded = apps
                .filter { it.packageName != packageName } // Never list ourselves.
                .map {
                    val label = try {
                        it.loadLabel(pm).toString()
                    } catch (e: Exception) {
                        it.packageName
                    }
                    AppEntry(label, it.packageName, isDisabled(it.packageName))
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                allEntries.clear()
                allEntries.addAll(loaded)
                applyFilter()
                statusView.text = "Ready — ${allEntries.size} apps loaded. Tap a row to toggle it."
            }
        }
    }

    /** True when [pkg] is currently disabled (for user 0 or entirely). */
    private fun isDisabled(pkg: String): Boolean {
        return try {
            when (packageManager.getApplicationEnabledSetting(pkg)) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> true
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Rebuilds the shown list from the search box + disabled-only flag. */
    private fun applyFilter() {
        shownEntries.clear()
        for (entry in allEntries) {
            if (disabledOnly && !entry.disabled) continue
            if (searchQuery.isNotEmpty() &&
                !entry.label.lowercase().contains(searchQuery) &&
                !entry.packageName.lowercase().contains(searchQuery)
            ) continue
            shownEntries.add(entry)
        }
        adapter.notifyDataSetChanged()
        updateCount()
    }

    /** Refreshes one row in place (its state just changed). */
    private fun refreshEntry(entry: AppEntry) {
        val index = shownEntries.indexOf(entry)
        if (index >= 0) adapter.notifyItemChanged(index)
    }

    private fun updateCount() {
        val disabledCount = allEntries.count { it.disabled }
        countView.text = "${shownEntries.size} shown · $disabledCount disabled"
    }

    /**
     * Toggles [entry]: enable it if it is disabled, disable it if it
     * is enabled. Root first — if su is available the toggle goes
     * through `pm` directly. Only when there is genuinely no su does
     * it fall back to the accessibility service.
     */
    private fun onToggle(entry: AppEntry, enable: Boolean) {
        val action = if (enable) "Enabling" else "Disabling"
        statusView.text = "$action ${entry.label}…"
        if (rootAvailable == true) {
            background.execute {
                val ok = RootHelper.setPackageEnabled(entry.packageName, enable)
                runOnUiThread {
                    if (ok) {
                        // Re-read the real state — pm is the source of truth.
                        entry.disabled = isDisabled(entry.packageName)
                        refreshEntry(entry)
                        updateCount()
                        val verb = if (entry.disabled) "Disabled" else "Enabled"
                        statusView.text = "✓ $verb ${entry.label} (root)"
                    } else {
                        // Root call failed — revert the switch and say so.
                        refreshEntry(entry)
                        val verb = if (enable) "Enable" else "Disable"
                        statusView.text =
                            "✗ $verb ${entry.label} failed (root) — try again"
                        Toast.makeText(
                            this,
                            "Root toggle failed — check the SuperSU grant",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            return
        }
        // No root — accessibility fallback. It needs the service on.
        if (!DisableService.isEnabled(this)) {
            refreshEntry(entry)
            statusView.text = "Turn on the App Manager accessibility service first (Setup above)"
            Toast.makeText(
                this,
                "No root — turn on the accessibility service first",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val job = DisableService.Job(entry.packageName, enable)
        DisableService.startJobs(this, listOf(job))
    }

    /** One row: split state icon, app name, package, toggle switch. */
    private inner class AppAdapter(
        private val items: List<AppEntry>,
        private val onToggle: (AppEntry, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iconView: StateBadgeView = view.findViewById(R.id.row_icon)
            val labelView: TextView = view.findViewById(R.id.row_label)
            val packageView: TextView = view.findViewById(R.id.row_package)
            val switchView: SwitchMaterial = view.findViewById(R.id.row_switch)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            holder.labelView.text = entry.label
            holder.packageView.text = entry.packageName
            bindState(holder, entry)
            // Set the switch state without firing the listener, then
            // attach the listener — otherwise binding flips the app.
            holder.switchView.setOnCheckedChangeListener(null)
            holder.switchView.isChecked = !entry.disabled
            holder.switchView.setOnCheckedChangeListener { _, checked ->
                onToggle(entry, checked)
            }
            holder.itemView.setOnClickListener {
                holder.switchView.toggle()
            }
        }

        private fun bindState(holder: ViewHolder, entry: AppEntry) {
            // The split icon is the state indicator: it lights the
            // half that matches the app's current state.
            holder.iconView.setDisabled(entry.disabled)
        }

        override fun getItemCount(): Int = items.size
    }
}
