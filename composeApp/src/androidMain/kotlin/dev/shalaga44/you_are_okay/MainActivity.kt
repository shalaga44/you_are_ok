package dev.shalaga44.you_are_okay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var enableBtLauncher: ActivityResultLauncher<Intent>
    private lateinit var permLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var notifPermLauncher: ActivityResultLauncher<String>

    private lateinit var tvStatus: TextView
    private lateinit var etUserId: EditText
    private lateinit var btnPick: Button
    private lateinit var btnStartSvc: Button
    private lateinit var btnOpenDashboard: Button

    private var selectedDeviceName: String? = null
    private var selectedDeviceAddr: String? = null
    private var userId: String = ""


    private val prefsName = "you_are_okay_prefs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
        }

        tvStatus = TextView(this).apply {
            textSize = 18f
            text = "Select a paired Polar device to begin."
        }


        etUserId = EditText(this).apply {
            hint = "User ID"
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        btnPick = Button(this).apply {
            text = "Select Bluetooth Device"
            setOnClickListener { pickDevice() }
        }
        btnStartSvc = Button(this).apply {
            text = "Start Data Service"
            setOnClickListener { startDataServiceIfReady() }
        }
        btnOpenDashboard = Button(this).apply {
            text = "Open Dashboard"
            setOnClickListener { openDashboard() }
        }

        listOf<View>(
            tvStatus, space(),
            etUserId, space(),
            btnPick, space(),
            btnStartSvc, space(),
            btnOpenDashboard
        ).forEach(root::addView)
        setContentView(root)


        val btMgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btMgr.adapter ?: run {
            toast("Bluetooth not supported on this device")
            finish()
            return
        }


        enableBtLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            ensureReadyState()
        }
        permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            ensureReadyState()
        }
        notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* noop */ }


        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        selectedDeviceAddr = sp.getString(DataService.EXTRA_DEVICE_ID, null)
        selectedDeviceName = sp.getString(DataService.EXTRA_DEVICE_NAME, null)
        userId = sp.getString(DataService.EXTRA_USER_ID, "") ?: ""
        etUserId.setText(userId)
        updateStatus()


        etUserId.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                userId = s?.toString()?.trim().orEmpty()
                sp.edit().putString(DataService.EXTRA_USER_ID, userId).apply()
                updateStatus()
            }
        })


        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }


        ensureReadyState()
    }


    private fun space(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 36)
    }

    private fun updateStatus() {
        val name = selectedDeviceName ?: "None"
        val addr = selectedDeviceAddr ?: "—"
        val uid = if (userId.isBlank()) "—" else userId
        tvStatus.text = "Device: $name\nMAC: $addr\nUser ID: $uid"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()


    private fun ensureReadyState() {

        if (!bluetoothAdapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }


        val neededPerms = mutableListOf<String>()


        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                neededPerms += Manifest.permission.BLUETOOTH_CONNECT
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                neededPerms += Manifest.permission.BLUETOOTH_SCAN
            }
        } else {

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                neededPerms += Manifest.permission.ACCESS_FINE_LOCATION
            }
        }

        if (neededPerms.isNotEmpty()) {
            permLauncher.launch(neededPerms.toTypedArray())
        }
    }


    private fun pickDevice() {
        if (!bluetoothAdapter.isEnabled) {
            toast("Enable Bluetooth first")
            ensureReadyState()
            return
        }


        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            return
        }

        val bonded: Set<BluetoothDevice>? = try {
            bluetoothAdapter.bondedDevices
        } catch (se: SecurityException) {
            toast("Bluetooth permission required")
            return
        }

        if (bonded.isNullOrEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No paired devices found")
                .setMessage("Pair your Polar device in Android Settings > Bluetooth, then come back.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val items = bonded.map { "${it.name ?: "Unknown"}\n${it.address}" }.toTypedArray()
        val devices = bonded.toList()

        AlertDialog.Builder(this)
            .setTitle("Choose a paired device")
            .setItems(items) { _, which ->
                val dev = devices[which]
                selectedDeviceName = dev.name ?: "Unknown"
                selectedDeviceAddr = dev.address

                val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                sp.edit()
                    .putString(DataService.EXTRA_DEVICE_NAME, selectedDeviceName)
                    .putString(DataService.EXTRA_DEVICE_ID, selectedDeviceAddr)
                    .apply()
                updateStatus()
                toast("Selected ${selectedDeviceName} (${selectedDeviceAddr})")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private fun startDataServiceIfReady() {
        val addr = selectedDeviceAddr
        val name = selectedDeviceName
        val uid = etUserId.text.toString().trim()

        if (uid.isBlank()) {
            toast("Enter User ID first")
            return
        }
        if (addr.isNullOrBlank() || name.isNullOrBlank()) {
            toast("Pick a device first")
            return
        }


        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        sp.edit()
            .putString(DataService.EXTRA_USER_ID, uid)
            .putString(DataService.EXTRA_DEVICE_ID, addr)
            .putString(DataService.EXTRA_DEVICE_NAME, name)
            .apply()

        val svcIntent = Intent(this, DataService::class.java).apply {
            putExtra(DataService.EXTRA_USER_ID, uid)
            putExtra(DataService.EXTRA_DEVICE_ID, addr)
            putExtra(DataService.EXTRA_DEVICE_NAME, name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, svcIntent)
        } else {
            startService(svcIntent)
        }
        toast("Data service started")
    }

    private fun openDashboard() {
        val addr = selectedDeviceAddr
        val name = selectedDeviceName
        val uid = etUserId.text.toString().trim()

        if (uid.isBlank()) {
            toast("Enter User ID first")
            return
        }
        if (addr.isNullOrBlank() || name.isNullOrBlank()) {
            toast("Pick a device first")
            return
        }


        startDataServiceIfReady()

        val dash = Intent(this, DataCollector::class.java).apply {
            putExtra(DataService.EXTRA_DEVICE_ID, addr)
            putExtra(DataService.EXTRA_DEVICE_NAME, name)
            putExtra(DataService.EXTRA_USER_ID, uid)
        }
        startActivity(dash)
    }


    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

    }
}