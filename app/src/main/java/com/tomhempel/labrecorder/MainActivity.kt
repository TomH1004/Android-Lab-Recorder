package com.tomhempel.labrecorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tomhempel.labrecorder.ui.theme.LabRecorderTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import android.content.Intent
import androidx.appcompat.app.AlertDialog

private const val TAG = "LabRecorder"

// Standard Bluetooth Service and Characteristic UUIDs
private val HR_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
private val HR_MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

data class BleDevice(val name: String, val address: String)

@SuppressLint("MissingPermission")
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // --- Bluetooth & System Services ---
    private val bluetoothManager: BluetoothManager by lazy {
        application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter by lazy { bluetoothManager.adapter }
    private val bluetoothLeScanner by lazy { bluetoothAdapter.bluetoothLeScanner }

    // --- UI State Management ---
    val isScanning = mutableStateOf(false)
    val discoveredDevices = mutableStateListOf<BleDevice>()
    val groupId = mutableStateOf("")
    val participantId = mutableStateOf("") // New state for single participant ID
    val isRecording = mutableStateOf(false)
    val isIntervalRunning = mutableStateOf(false)
    val connectionState1 = mutableStateOf("Disconnected")
    val connectionState2 = mutableStateOf("Disconnected")
    val hrValue1 = mutableStateOf(0)
    val hrValue2 = mutableStateOf(0)
    val logMessages = mutableStateListOf<String>()
    val selectedDevice1 = mutableStateOf<BleDevice?>(null)
    val selectedDevice2 = mutableStateOf<BleDevice?>(null)
    val numberOfParticipants = mutableStateOf(0) // New state for number of participants


    // --- Connection & File Management ---
    private var gatt1: BluetoothGatt? = null
    private var gatt2: BluetoothGatt? = null
    private var hrWriter1: BufferedWriter? = null
    private var rrWriter1: BufferedWriter? = null
    private var hrWriter2: BufferedWriter? = null
    private var rrWriter2: BufferedWriter? = null
    private var timestampWriter: BufferedWriter? = null
    private var scanTimeoutJob: Job? = null

    init {
        addLog("App initialized. Welcome!")
    }

    // --- Logging Helper ---
    private fun addLog(message: String) {
        val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "$timeStamp: $message"
        logMessages.add(0, logEntry)
        if (logMessages.size > 100) {
            logMessages.removeLast()
        }
        Log.d(TAG, message)
    }

    // --- GATT Callback for Connection & Data Events ---
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            val (stateToUpdate, hrToUpdate, participant) = if (deviceAddress == gatt1?.device?.address) {
                Triple(connectionState1, hrValue1, "P1")
            } else {
                Triple(connectionState2, hrValue2, "P2")
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    stateToUpdate.value = "Connected"
                    addLog("[$participant] Connected to $deviceAddress. Discovering services...")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    stateToUpdate.value = "Disconnected"
                    hrToUpdate.value = 0
                    addLog("[$participant] Disconnected from $deviceAddress.")
                    gatt.close()
                }
            } else {
                stateToUpdate.value = "Error"
                hrToUpdate.value = 0
                addLog("[$participant] ERROR: GATT Error for $deviceAddress. Status: $status")
                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val participant = if (gatt.device.address == gatt1?.device?.address) "P1" else "P2"
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("[$participant] Services discovered. Enabling HR notifications.")
                enableHeartRateNotifications(gatt)
            } else {
                addLog("[$participant] ERROR: Service discovery failed.")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == HR_MEASUREMENT_CHAR_UUID) {
                parseAndRecordData(gatt.device.address, value)
            }
        }
    }

    // --- Scanning Logic ---
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name
            if (deviceName?.startsWith("Polar", ignoreCase = true) == true) {
                val device = BleDevice(deviceName, result.device.address)
                if (discoveredDevices.none { it.address == device.address }) {
                    addLog("Found device: ${device.name}")
                    discoveredDevices.add(device)
                    if (discoveredDevices.size >= numberOfParticipants.value) {
                        addLog("Found ${numberOfParticipants.value} Polar device(s). Stopping scan.")
                        stopBleScan()
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            addLog("ERROR: BLE Scan Failed with code: $errorCode")
            isScanning.value = false
        }
    }

    // --- Data Processing ---
    private fun parseAndRecordData(deviceAddress: String, data: ByteArray) {
        val isDevice1 = deviceAddress == gatt1?.device?.address
        val hrState = if (isDevice1) hrValue1 else hrValue2
        val currentHrWriter = if (isDevice1) hrWriter1 else hrWriter2
        val currentRrWriter = if (isDevice1) rrWriter1 else rrWriter2

        val flags = data[0].toInt()
        val is16BitFormat = (flags and 0x01) != 0
        val hrValue = if (is16BitFormat) {
            (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        } else {
            data[1].toInt() and 0xFF
        }
        hrState.value = hrValue

        currentHrWriter?.let { writer ->
            try {
                val timestamp = System.currentTimeMillis()
                writer.write("$timestamp,$hrValue\n")
            } catch (e: IOException) {
                Log.e(TAG, "Error writing HR data for $deviceAddress", e)
            }
        }

        val rrIntervalsPresent = (flags and 0x10) != 0
        if (rrIntervalsPresent) {
            var offset = if (is16BitFormat) 3 else 2
            while (offset < data.size) {
                val rrValue = ((data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8))
                currentRrWriter?.let { writer ->
                    try {
                        val timestamp = System.currentTimeMillis()
                        writer.write("$timestamp,$rrValue\n")
                    } catch (e: IOException) {
                        Log.e(TAG, "Error writing RR data for $deviceAddress", e)
                    }
                }
                offset += 2
            }
        }
    }

    // --- BLE Actions ---
    private fun enableHeartRateNotifications(gatt: BluetoothGatt) {
        val hrCharacteristic = gatt.getService(HR_SERVICE_UUID)?.getCharacteristic(HR_MEASUREMENT_CHAR_UUID)
        if (hrCharacteristic == null) {
            addLog("ERROR: Heart Rate characteristic not found on ${gatt.device.address}")
            return
        }
        gatt.setCharacteristicNotification(hrCharacteristic, true)
        val descriptor = hrCharacteristic.getDescriptor(CCCD_UUID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
        addLog("Enabling notifications for ${gatt.device.address}")
    }

    fun startBleScan() {
        addLog("Starting automatic BLE scan (2s or 2 devices)...")
        disconnectAll()
        discoveredDevices.clear()
        selectedDevice1.value = null
        selectedDevice2.value = null
        isScanning.value = true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bluetoothLeScanner?.startScan(null, settings, scanCallback)

        scanTimeoutJob?.cancel()
        scanTimeoutJob = viewModelScope.launch {
            delay(2000)
            if (isScanning.value) {
                addLog("Scan timeout (2s) reached.")
                stopBleScan()
            }
        }
    }

    fun stopBleScan() {
        if (!isScanning.value) return
        addLog("Stopping BLE scan.")
        isScanning.value = false
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        bluetoothLeScanner?.stopScan(scanCallback)

        // Auto-select devices after scan stops and connect
        if (discoveredDevices.isNotEmpty()) {
            selectedDevice1.value = discoveredDevices.getOrNull(0)
            selectedDevice2.value = discoveredDevices.getOrNull(1)
            connectToDevices()
        }
    }

    fun connectToDevices() {
        val address1 = selectedDevice1.value?.address ?: ""
        val address2 = if (numberOfParticipants.value == 2) selectedDevice2.value?.address else ""
        disconnectAll()
        if (address1.isNotEmpty()) {
            val device = bluetoothAdapter?.getRemoteDevice(address1)
            addLog("Attempting to connect to P1: $address1")
            gatt1 = device?.connectGatt(getApplication(), false, gattCallback)
        }
        if (address2?.isNotEmpty() == true && address1 != address2) {
            val device = bluetoothAdapter?.getRemoteDevice(address2)
            addLog("Attempting to connect to P2: $address2")
            gatt2 = device?.connectGatt(getApplication(), false, gattCallback)
        }
    }

    fun disconnectAll() {
        gatt1?.disconnect()
        gatt2?.disconnect()
    }

    // --- File Recording Logic ---
    fun startRecording() {
        if (numberOfParticipants.value == 2 && groupId.value.isBlank()) {
            addLog("ERROR: Group ID cannot be empty.")
            Toast.makeText(getApplication(), "Group ID cannot be empty.", Toast.LENGTH_SHORT).show()
            return
        } else if (numberOfParticipants.value == 1 && participantId.value.isBlank()) {
            addLog("ERROR: Participant ID cannot be empty.")
            Toast.makeText(getApplication(), "Participant ID cannot be empty.", Toast.LENGTH_SHORT).show()
            return
        }

        val baseDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "LabRecorder")
        val recordingDir = if (numberOfParticipants.value == 2) {
            addLog("Starting recording for group: ${groupId.value}")
            File(baseDir, groupId.value)
        } else {
            addLog("Starting recording for participant: ${participantId.value}")
            val singleRecordingsDir = File(baseDir, "SingleRecordings")
            File(singleRecordingsDir, participantId.value)
        }

        if (!recordingDir.exists() && !recordingDir.mkdirs()) {
            addLog("ERROR: Failed to create recording directory.")
            return
        }

        if (gatt1?.device?.address != null) {
            val deviceDir = if (numberOfParticipants.value == 2) {
                File(recordingDir, "Participant_1")
            } else {
                recordingDir // For single participant, use the participant directory directly
            }
            deviceDir.mkdirs()
            try {
                hrWriter1 = BufferedWriter(FileWriter(File(deviceDir, "hr.csv"))).apply { write("timestamp,hr\n") }
                rrWriter1 = BufferedWriter(FileWriter(File(deviceDir, "rr.csv"))).apply { write("timestamp,rr_ms\n") }
            } catch (e: IOException) { addLog("ERROR: Failed creating writers for P1.") }
        }

        if (numberOfParticipants.value == 2 && gatt2?.device?.address != null) {
            val deviceDir = File(recordingDir, "Participant_2")
            deviceDir.mkdirs()
            try {
                hrWriter2 = BufferedWriter(FileWriter(File(deviceDir, "hr.csv"))).apply { write("timestamp,hr\n") }
                rrWriter2 = BufferedWriter(FileWriter(File(deviceDir, "rr.csv"))).apply { write("timestamp,rr_ms\n") }
            } catch (e: IOException) { addLog("ERROR: Failed creating writers for P2.") }
        }

        try {
            timestampWriter = BufferedWriter(FileWriter(File(recordingDir, "timestamps.csv"))).apply { write("timestamp,event_type\n") }
        } catch(e: IOException) { addLog("ERROR: Failed creating timestamp writer.") }

        isRecording.value = true
        Toast.makeText(getApplication(), "Recording started.", Toast.LENGTH_SHORT).show()
    }

    fun stopRecording() {
        addLog("Stopping recording.")
        isRecording.value = false
        isIntervalRunning.value = false
        try {
            hrWriter1?.close()
            rrWriter1?.close()
            hrWriter2?.close()
            rrWriter2?.close()
            timestampWriter?.close()
        } catch (e: IOException) { addLog("ERROR: Failed closing file writers.") }

        hrWriter1 = null; rrWriter1 = null; hrWriter2 = null; rrWriter2 = null; timestampWriter = null
        Toast.makeText(getApplication(), "Recording stopped.", Toast.LENGTH_SHORT).show()
    }

    fun markTimestamp(eventType: String) {
        if (!isRecording.value) {
            val msg = "Must be recording to mark a timestamp."
            addLog("INFO: $msg")
            Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
            return
        }
        timestampWriter?.let { writer ->
            try {
                val timestamp = System.currentTimeMillis()
                writer.write("$timestamp,$eventType\n")
                writer.flush()
                val msg = "'$eventType' marked."
                addLog(msg)
                Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                addLog("ERROR: Could not write timestamp.")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (isRecording.value) {
            stopRecording()
        }
        disconnectAll()
        addLog("ViewModel cleared. Resources released.")
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var showExitDialog by mutableStateOf(false)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                // Permissions granted, start the scan
                viewModel.startBleScan()
            } else {
                Toast.makeText(this, "Bluetooth permissions are required for this app to function.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onBackPressed() {
        // If recording is in progress, show a warning dialog
        if (viewModel.isRecording.value) {
            showExitDialog = true
        } else {
            returnToSelection()
        }
    }

    private fun returnToSelection() {
        // Stop any ongoing operations
        viewModel.stopBleScan()
        viewModel.disconnectAll()
        
        // Start ParticipantSelectionActivity
        val intent = Intent(this, ParticipantSelectionActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
        finish()
    }

    private fun hasRequiredBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRelevantPermissions() {
        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissionLauncher.launch(permissionsToRequest)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Get the number of participants from the intent
        val numberOfParticipants = intent.getIntExtra("NUMBER_OF_PARTICIPANTS", 2)
        viewModel.numberOfParticipants.value = numberOfParticipants

        // Lock orientation to vertical to prevent activity recreation on rotation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        setContent {
            LabRecorderTheme {
                if (showExitDialog) {
                    AlertDialog(
                        onDismissRequest = { showExitDialog = false },
                        title = { Text("Recording in Progress") },
                        text = { Text("Stop recording and return to selection screen?") },
                        confirmButton = {
                            TextButton(onClick = {
                                showExitDialog = false
                                viewModel.stopRecording()
                                returnToSelection()
                            }) {
                                Text("Yes")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showExitDialog = false }) {
                                Text("No")
                            }
                        }
                    )
                }

                Scaffold { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        viewModel = viewModel
                    )
                }
            }
        }

        // Check for permissions and start scanning if granted
        if (hasRequiredBluetoothPermissions()) {
            viewModel.startBleScan()
        } else {
            requestRelevantPermissions()
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel
) {
    // Read state from the ViewModel
    val isScanning by viewModel.isScanning
    val discoveredDevices = viewModel.discoveredDevices
    val connectionStatus1 by viewModel.connectionState1
    val connectionStatus2 by viewModel.connectionState2
    val hrValue1 by viewModel.hrValue1
    val hrValue2 by viewModel.hrValue2
    val groupId by viewModel.groupId
    val participantId by viewModel.participantId
    val isRecording by viewModel.isRecording
    val isIntervalRunning by viewModel.isIntervalRunning
    val logMessages = viewModel.logMessages
    val selectedDevice1 by viewModel.selectedDevice1
    val selectedDevice2 by viewModel.selectedDevice2
    val numberOfParticipants by viewModel.numberOfParticipants

    val dropdownOptions = when {
        discoveredDevices.isNotEmpty() -> discoveredDevices
        isScanning -> listOf(BleDevice("Scanning...", ""))
        else -> listOf(BleDevice("No devices found", ""))
    }

    var expanded1 by remember { mutableStateOf(false) }
    var expanded2 by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // --- Section 1: Setup ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (numberOfParticipants == 2) {
                    OutlinedTextField(
                        value = groupId,
                        onValueChange = { viewModel.groupId.value = it },
                        label = { Text("Group ID *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isRecording
                    )
                } else if (numberOfParticipants == 1) {
                    OutlinedTextField(
                        value = participantId,
                        onValueChange = { viewModel.participantId.value = it },
                        label = { Text("Participant ID *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isRecording
                    )
                }
                Button(
                    onClick = { if (isScanning) viewModel.stopBleScan() else viewModel.startBleScan() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRecording
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop Scan")
                    } else {
                        Text("Scan for Polar Devices")
                    }
                }
            }
        }

        // --- Section 2: Device Connection ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DeviceSelectionDropdown(
                    label = "Participant 1",
                    options = dropdownOptions,
                    selectedOption = selectedDevice1,
                    onOptionSelected = { viewModel.selectedDevice1.value = it },
                    expanded = expanded1,
                    onExpandedChange = { expanded1 = it },
                    enabled = discoveredDevices.isNotEmpty() && !isRecording,
                    status = connectionStatus1,
                    hrValue = hrValue1
                )
                if (numberOfParticipants == 2) {
                    DeviceSelectionDropdown(
                        label = "Participant 2",
                        options = dropdownOptions,
                        selectedOption = selectedDevice2,
                        onOptionSelected = { viewModel.selectedDevice2.value = it },
                        expanded = expanded2,
                        onExpandedChange = { expanded2 = it },
                        enabled = discoveredDevices.isNotEmpty() && !isRecording,
                        status = connectionStatus2,
                        hrValue = hrValue2
                    )
                }
                Button(
                    onClick = { viewModel.connectToDevices() },
                    enabled = (selectedDevice1 != null || selectedDevice2 != null) && !isRecording,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect")
                }
            }
        }

        // --- Section 3: Recording Controls ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { if (isRecording) viewModel.stopRecording() else viewModel.startRecording() },
                    enabled = (connectionStatus1 == "Connected" || connectionStatus2 == "Connected") && 
                             ((numberOfParticipants == 2 && groupId.isNotBlank()) || 
                              (numberOfParticipants == 1 && participantId.isNotBlank())),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(if (isRecording) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isRecording) "Stop Recording" else "Start Recording")
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (isIntervalRunning) {
                                viewModel.markTimestamp("interval_end")
                                viewModel.isIntervalRunning.value = false
                            } else {
                                viewModel.markTimestamp("interval_start")
                                viewModel.isIntervalRunning.value = true
                            }
                        },
                        enabled = isRecording,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isIntervalRunning) "Stop Interval" else "Start Interval")
                    }
                    Button(
                        onClick = { viewModel.markTimestamp("manual_mark") },
                        enabled = isRecording,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Mark Timestamp")
                    }
                }
            }
        }

        // --- Section 4: Event Log Console ---
        LogConsole(logMessages = logMessages, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSelectionDropdown(
    label: String,
    options: List<BleDevice>,
    selectedOption: BleDevice?,
    onOptionSelected: (BleDevice) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean,
    status: String,
    hrValue: Int
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(
            expanded = expanded && enabled,
            onExpandedChange = onExpandedChange,
            modifier = Modifier.weight(1f)
        ) {
            TextField(
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                readOnly = true,
                value = selectedOption?.name ?: if(enabled) "Select a device" else "Scan first",
                onValueChange = {},
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                enabled = enabled
            )
            ExposedDropdownMenu(
                expanded = expanded && enabled,
                onDismissRequest = { onExpandedChange(false) },
            ) {
                options.forEach { device ->
                    if (device.address.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text(device.name) },
                            onClick = {
                                onOptionSelected(device)
                                onExpandedChange(false)
                            }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(100.dp)) {
            val statusColor = when(status) {
                "Connected" -> Color(0xFF2E7D32) // Dark Green
                "Error" -> MaterialTheme.colorScheme.error
                else -> LocalContentColor.current.copy(alpha = 0.7f)
            }
            Text(status, fontSize = 14.sp, color = statusColor, fontWeight = FontWeight.Bold)

            if (status == "Connected") {
                Text(
                    text = "$hrValue BPM",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun LogConsole(logMessages: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(logMessages.size) {
        if (logMessages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(0)
            }
        }
    }

    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Event Log", style = MaterialTheme.typography.titleMedium)
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                items(logMessages) { msg ->
                    val color = when {
                        msg.contains("ERROR") -> MaterialTheme.colorScheme.error
                        msg.contains("Connected") || msg.contains("Recording started") -> Color(0xFF2E7D32) // Dark Green
                        msg.contains("Stopping") || msg.contains("Disconnected") -> Color(0xFFC62828) // Dark Red
                        else -> LocalContentColor.current
                    }
                    Text(
                        text = msg,
                        color = color,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ParticipantSelectionDialog(
    onDismiss: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* Do nothing, force selection */ },
        title = { Text("Select Number of Participants") },
        text = { Text("Please select the number of participants for this recording session.") },
        confirmButton = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { onDismiss(1) },
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                ) {
                    Text("1 Participant")
                }
                Button(
                    onClick = { onDismiss(2) },
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                ) {
                    Text("2 Participants")
                }
            }
        },
        dismissButton = null // No dismiss button to force selection
    )
}