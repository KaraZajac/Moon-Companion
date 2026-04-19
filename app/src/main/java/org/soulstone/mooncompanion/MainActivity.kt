package org.soulstone.mooncompanion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.soulstone.mooncompanion.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Preferences

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isEmpty()) {
            startPeripheral()
        } else {
            setStatus("Missing permissions: ${denied.joinToString()}")
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(MoonPeripheralService.EXTRA_STATUS) ?: return
            setStatus(status)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Preferences(this)

        binding.startButton.setOnClickListener { requestPermissionsAndStart() }
        binding.stopButton.setOnClickListener {
            MoonPeripheralService.stop(this)
            setStatus("Stopped")
        }

        binding.startAtBootSwitch.isChecked = prefs.startAtBoot
        binding.startAtBootSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.startAtBoot = checked
        }

        binding.batteryButton.setOnClickListener { requestBatteryOptimisationExemption() }

        binding.copyLogsButton.setOnClickListener { copyLogsToClipboard() }
        binding.clearLogsButton.setOnClickListener { DebugLog.clear() }

        setStatus("Idle")
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(MoonPeripheralService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        refreshBatteryButton()
        DebugLog.addListener(logListener)
    }

    override fun onStop() {
        super.onStop()
        DebugLog.removeListener(logListener)
        try { unregisterReceiver(statusReceiver) } catch (_: IllegalArgumentException) {}
    }

    // Lives as a field so addListener / removeListener see the same instance.
    private val logListener: (List<String>) -> Unit = { entries ->
        runOnUiThread {
            binding.logText.text = entries.joinToString("\n")
            // Auto-scroll to the newest line whenever entries come in.
            binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun copyLogsToClipboard() {
        val snapshot = DebugLog.snapshot().joinToString("\n")
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Moon Companion log", snapshot))
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }

    private fun requestPermissionsAndStart() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startPeripheral()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requiredPermissions(): List<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        // Foreground-only location. Background location (needed if you want
        // fixes while the app is swipe-killed) requires a separate settings
        // grant the user has to do manually — not worth the UX cost until
        // the Flipper actually relies on swipe-killed streaming.
        perms += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms
    }

    private fun startPeripheral() {
        MoonPeripheralService.start(this)
        setStatus("Starting...")
    }

    /**
     * Fire the system Settings intent that opens the per-app battery
     * optimisation list with our package pre-selected. Google Play policy
     * frowns on this for general apps; fine for a companion app whose
     * whole job is persistent BLE. User still has to confirm — we can't
     * flip the bit silently.
     */
    private fun requestBatteryOptimisationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) {
            setStatus(getString(R.string.battery_opt_already_granted))
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Some OEMs strip the direct intent; fall back to the generic
            // battery-optimisation list.
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun refreshBatteryButton() {
        val pm = getSystemService(PowerManager::class.java)
        val exempt = pm?.isIgnoringBatteryOptimizations(packageName) == true
        binding.batteryButton.isEnabled = !exempt
        binding.batteryButton.text = if (exempt) {
            getString(R.string.battery_opt_already_granted)
        } else {
            getString(R.string.disable_battery_opt)
        }
    }

    private fun setStatus(text: String) {
        binding.statusText.text = text
    }
}
