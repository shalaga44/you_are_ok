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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var enableBtLauncher: ActivityResultLauncher<Intent>
    private lateinit var permLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var notifPermLauncher: ActivityResultLauncher<String>

    private var selectedDeviceName by mutableStateOf<String?>(null)
    private var selectedDeviceAddr by mutableStateOf<String?>(null)
    private var userId by mutableStateOf("")

    private val prefsName = "you_are_okay_prefs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val btMgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btMgr.adapter ?: run {
            toast("Bluetooth not supported on this device")
            finish()
            return
        }

        enableBtLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                ensureReadyState()
            }
        permLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                ensureReadyState()
            }
        notifPermLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* noop */ }

        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        selectedDeviceAddr = sp.getString(DataService.EXTRA_DEVICE_ID, null)
        selectedDeviceName = sp.getString(DataService.EXTRA_DEVICE_NAME, null)
        userId = sp.getString(DataService.EXTRA_USER_ID, "") ?: ""

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        ensureReadyState()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    MainScreen(
                        deviceName = selectedDeviceName,
                        deviceAddr = selectedDeviceAddr,
                        userId = userId,
                        onUserIdChange = { new ->
                            userId = new.trim()
                            sp.edit().putString(DataService.EXTRA_USER_ID, userId).apply()
                        },
                        onPickDevice = { pickDevice() },
                        onStartService = { startDataServiceIfReady() },
                        onOpenDashboard = { openDashboard() }
                    )
                }
            }
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun ensureReadyState() {
        if (!bluetoothAdapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        val neededPerms = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                neededPerms += Manifest.permission.BLUETOOTH_CONNECT
            }

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                neededPerms += Manifest.permission.BLUETOOTH_SCAN
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
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
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
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
                toast("Selected ${selectedDeviceName} (${selectedDeviceAddr})")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startDataServiceIfReady() {
        val addr = selectedDeviceAddr
        val name = selectedDeviceName
        val uid = userId.trim()

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
        val uid = userId.trim()

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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String?>,
        grantResults: IntArray,
        deviceId: Int
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults, deviceId)
    }


}

@Composable
private fun MainScreen(
    deviceName: String?,
    deviceAddr: String?,
    userId: String,
    onUserIdChange: (String) -> Unit,
    onPickDevice: () -> Unit,
    onStartService: () -> Unit,
    onOpenDashboard: () -> Unit
) {
    val name = deviceName ?: "None"
    val addr = deviceAddr ?: "—"
    val uid = if (userId.isBlank()) "—" else userId
    val statusText = "Device: $name\nMAC: $addr\nUser ID: $uid"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "You Are Okay",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = userId,
            onValueChange = { onUserIdChange(it.filter { ch -> ch.isDigit() }) },
            label = { Text("User ID") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onPickDevice,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Select Bluetooth Device")
        }

        Button(
            onClick = onStartService,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Data Service")
        }

        OutlinedButton(
            onClick = onOpenDashboard,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Dashboard")
        }
    }
}