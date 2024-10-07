package com.example.bluetoothlescannerhr

import PermissionHandler
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluetoothlescannerhr.ui.theme.Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private val permissionHandler = PermissionHandler()

    private var bpm by mutableStateOf(0)
    private var isConnected by mutableStateOf(false)
    private var connectedDeviceName by mutableStateOf<String?>(null)
    private var connectedDeviceMac by mutableStateOf<String?>(null)

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
                isConnected = true
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("DBG", "Disconnected from GATT server.")
                isConnected = false
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val heartRateService = gatt.getService(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"))
                val characteristic = heartRateService?.getCharacteristic(UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb"))

                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic?.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            } else {
                Log.w("DBG", "onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bpmValue = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 1)
            bpm = bpmValue ?: 0
            Log.d("DBG", "BPM: $bpm")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        enableEdgeToEdge()

        setContent {
            Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var hasPermissions by remember { mutableStateOf(false) }
                    var bluetoothEnabled by remember { mutableStateOf(bluetoothAdapter.isEnabled) }
                    val context = LocalContext.current
                    val requestPermissionsLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions -> hasPermissions = permissions.values.all { it } }

                    LaunchedEffect(Unit) {
                        val missingPermissions = permissionHandler.checkPermissions(context)
                        if (missingPermissions.isNotEmpty()) {
                            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
                        } else {
                            hasPermissions = true
                        }
                    }

                    val bluetoothEnablerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        if (result.resultCode == RESULT_OK) {
                            bluetoothEnabled = bluetoothAdapter.isEnabled
                        }
                    }

                    when {
                        !bluetoothEnabled -> {
                            PromptEnableBluetooth {
                                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                bluetoothEnablerLauncher.launch(enableBtIntent)
                            }
                        }
                        hasPermissions -> {
                            MainContent(
                                bluetoothLeScanner = bluetoothLeScanner,
                                requestPermissionsLauncher = requestPermissionsLauncher,
                                bpm = bpm,
                                bluetoothAdapter = bluetoothAdapter,
                                gattCallback = gattCallback,
                                isConnected = isConnected,
                                connectedDeviceName = connectedDeviceName,
                                connectedDeviceMac = connectedDeviceMac,
                                onDeviceConnect = { name, mac ->
                                    connectedDeviceName = name
                                    connectedDeviceMac = mac
                                }
                            )
                        }
                        else -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                PermissionMessage(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        "Bluetooth and Location Permissions are required to use this app."
                                    } else {
                                        "Location Permissions are required to use this app."
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PromptEnableBluetooth(onEnableBluetooth: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Bluetooth is turned off. Please enable Bluetooth to use this app.",
                style = TextStyle(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier
                    .padding(30.dp)
                    .background(MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium)
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface), shape = MaterialTheme.shapes.medium)
                    .padding(30.dp)
                    .fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onEnableBluetooth) {
                Text(text = "Enable Bluetooth")
            }
        }
    }
}

@Composable
fun PermissionMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = TextStyle(
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            ),
            modifier = Modifier
                .padding(30.dp)
                .background(MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface), shape = MaterialTheme.shapes.medium)
                .padding(30.dp)
                .fillMaxWidth()
        )
    }
}

@Composable
fun MainContent(
    bluetoothLeScanner: BluetoothLeScanner,
    requestPermissionsLauncher: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
    bpm: Int,
    bluetoothAdapter: BluetoothAdapter,
    gattCallback: BluetoothGattCallback,
    isConnected: Boolean,
    connectedDeviceName: String?,
    connectedDeviceMac: String?,
    onDeviceConnect: (String, String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val devices = remember { mutableStateListOf<BluetoothDevice>() }
    var isScanning by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val permissionHandler = PermissionHandler()

    val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "Unknown"
            val macAddress = device.address
            val rssi = result.rssi
            val isConnectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.isConnectable else false

            val existingDevice = devices.find { it.macAddress == macAddress }
            if (existingDevice == null) {
                val newDevice = BluetoothDevice(name, macAddress, rssi, isConnectable)
                devices.add(newDevice)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            println("Scan failed with error: $errorCode")
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Button(
                onClick = {
                    val missingPermissions = permissionHandler.checkPermissions(context)
                    if (missingPermissions.isEmpty()) {
                        isScanning = true
                        devices.clear()
                        try {
                            bluetoothLeScanner.startScan(scanCallback)
                        } catch (e: SecurityException) {
                            println("SecurityException: ${e.message}")
                        }
                        coroutineScope.launch {
                            delay(3000) // Scan for 3 seconds
                            bluetoothLeScanner.stopScan(scanCallback)
                            isScanning = false
                        }
                    } else {
                        requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp)
                    .height(64.dp),
                shape = RectangleShape
            ) {
                Text(
                    text = if (isScanning) "Scanning..." else "Start Scanning",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 20.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }

            if (isConnected) {
                Text(
                    text = "Device: ${connectedDeviceName ?: "Unknown"}\nMAC: ${connectedDeviceMac ?: "Unknown"}\nBPM: $bpm",
                    fontSize = 20.sp,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            LazyColumn {
                items(devices) { device ->
                    val textStyle = if (device.isConnectable) {
                        TextStyle(
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    } else {
                        TextStyle(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Text(
                        text = "${device.macAddress} ${device.name} ${device.rssi}dBm",
                        style = textStyle,
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable {
                                val bluetoothDevice = bluetoothAdapter.getRemoteDevice(device.macAddress)
                                bluetoothDevice.connectGatt(context, false, gattCallback)
                                onDeviceConnect(device.name, device.macAddress)
                            }
                    )
                }
            }
        }
    }
}

data class BluetoothDevice(
    val name: String,
    val macAddress: String,
    val rssi: Int,
    val isConnectable: Boolean
)