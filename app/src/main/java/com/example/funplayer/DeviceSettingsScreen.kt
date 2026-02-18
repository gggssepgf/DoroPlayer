@file:Suppress("DEPRECATION")
package com.example.funplayer

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAdapter.LeScanCallback
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.location.LocationManager
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.ExperimentalMaterial3Api
import kotlin.OptIn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceSettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDeveloperMode = getIsDeveloperMode(context)
    val activity = context as? Activity
    var connectionTestInProgress by remember { mutableStateOf(false) }

    var handyScanning by remember { mutableStateOf(false) }
    var handyDeviceExpanded by remember { mutableStateOf(false) }
    var handyDeviceOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var handyScanTick by remember { mutableStateOf(0) }
    var joyPlayScanTick by remember { mutableStateOf(0) }
    var joyPlayScanning by remember { mutableStateOf(false) }
    var pendingBleScan by remember { mutableStateOf<ConnectionType?>(null) }

    val handyPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.values.all { it }
        if (granted) {
            when (pendingBleScan) {
                ConnectionType.TheHandy -> handyScanTick++
                ConnectionType.JoyPlay -> joyPlayScanTick++
                else -> { }
            }
            pendingBleScan = null
        } else {
            Toast.makeText(context, context.getString(R.string.bt_scan_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    // BLE 权限：Android 12 (API 31)–16 需 BLUETOOTH_SCAN/CONNECT；Android 6–11 需定位，部分 12+ 机型也需定位才有扫描结果
    fun handyRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun handyHasAllPermissions(): Boolean {
        return handyRequiredPermissions().all { p ->
            ContextCompat.checkSelfPermission(context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    fun handyStartScan() {
        if (handyScanning) return
        if (!handyHasAllPermissions()) {
            pendingBleScan = ConnectionType.TheHandy
            handyPermLauncher.launch(handyRequiredPermissions())
            return
        }
        handyScanTick++
    }

    fun joyPlayStartScan() {
        if (joyPlayScanning) return
        if (!handyHasAllPermissions()) {
            pendingBleScan = ConnectionType.JoyPlay
            handyPermLauncher.launch(handyRequiredPermissions())
            return
        }
        joyPlayScanTick++
    }

    var connectionEnabled by remember { mutableStateOf(getConnectionEnabled(context)) }
    var connectionType by remember { mutableStateOf(getConnectionType(context)) }
    var connectionExpanded by remember { mutableStateOf(false) }

    var ipAddress by remember { mutableStateOf(getDeviceIp(context)) }
    var port by remember { mutableStateOf(context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE).getString(KEY_DEVICE_PORT, "8080") ?: "8080") }
    var serialDeviceId by remember { mutableStateOf(getSerialDeviceId(context)) }
    var serialPortExpanded by remember { mutableStateOf(false) }
    var serialPortOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var baudRate by remember { mutableStateOf(context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE).getString(KEY_BAUD_RATE, "9600") ?: "9600") }
    var btSerialDeviceAddress by remember { mutableStateOf(getBtSerialDeviceAddress(context)) }
    var btSerialDeviceExpanded by remember { mutableStateOf(false) }
    var btSerialDeviceOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var handyAxis by remember { mutableStateOf(getHandyAxis(context)) }
    var handyAxisExpanded by remember { mutableStateOf(false) }
    var handyDeviceAddress by remember { mutableStateOf(getHandyDeviceAddress(context)) }
    var handyBondedOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var handyKey by remember { mutableStateOf(getHandyKey(context)) }
    var joyPlayDeviceAddress by remember { mutableStateOf(getJoyPlayDeviceAddress(context)) }
    var joyPlayDeviceExpanded by remember { mutableStateOf(false) }
    var joyPlayDeviceOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var joyPlayBondedOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var sendFormatPrefix by remember { mutableStateOf(getSendFormatPrefix(context)) }
    var sendFormatSuffix by remember { mutableStateOf(getSendFormatSuffix(context)) }
    var connectionDetailsExpanded by remember { mutableStateOf(true) }
    var outputRangeExpanded by remember { mutableStateOf(true) }
    var deviceControlExpanded by remember { mutableStateOf(true) }
    var manualAxisPositions by remember { mutableStateOf(AXIS_NAMES.associateWith { 50 }) }
    var sliderSendJob by remember { mutableStateOf<Job?>(null) }
    var selectedScriptUris by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedScriptName by remember { mutableStateOf<String?>(null) }
    var standaloneScriptData by remember { mutableStateOf<FunscriptData?>(null) }
    var scriptPlayPositionMs by remember { mutableStateOf(0L) }
    var scriptPlaying by remember { mutableStateOf(false) }
    var scriptPlayJob by remember { mutableStateOf<Job?>(null) }

    val scriptPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            selectedScriptUris = uris.map { it.toString() }
            selectedScriptName = if (uris.size == 1) {
                DocumentFile.fromSingleUri(context, uris[0])?.name ?: uris[0].lastPathSegment?.substringAfterLast('/') ?: context.getString(R.string.unknown)
            } else {
                context.getString(R.string.device_control_multi_script_format, uris.size)
            }
            standaloneScriptData = null
            scriptPlayPositionMs = 0L
        }
    }
    LaunchedEffect(selectedScriptUris) {
        if (selectedScriptUris.isEmpty()) {
            standaloneScriptData = null
            scriptPlayPositionMs = 0L
            return@LaunchedEffect
        }
        val data = if (selectedScriptUris.size == 1) {
            withContext(Dispatchers.IO) { loadFunscriptFromUri(context, selectedScriptUris[0]) }
        } else {
            val urisByAxis = mutableMapOf<String, String>()
            for (uriStr in selectedScriptUris) {
                val name = DocumentFile.fromSingleUri(context, android.net.Uri.parse(uriStr))?.name ?: uriStr.substringAfterLast('/')
                if (!name.endsWith(".funscript", ignoreCase = true)) continue
                val rest = name.removeSuffix(".funscript").removeSuffix(".FUNSCRIPT")
                val axisId = if ('.' in rest) rest.substringAfterLast('.') else "L0"
                urisByAxis[axisId] = uriStr
            }
            if (urisByAxis.isEmpty()) null else withContext(Dispatchers.IO) { loadFunscriptMultiFromUris(context, urisByAxis) }
        }
        standaloneScriptData = data
        scriptPlayPositionMs = 0L
    }

    LaunchedEffect(connectionEnabled, connectionType, ipAddress, port, sendFormatPrefix, sendFormatSuffix, serialDeviceId, baudRate, btSerialDeviceAddress, handyAxis, handyKey, handyDeviceAddress, joyPlayDeviceAddress) {
        saveConnectionSettings(
            context,
            connectionEnabled,
            connectionType,
            ipAddress,
            port,
            sendFormatPrefix,
            sendFormatSuffix,
            serialDeviceId,
            baudRate,
            btSerialDeviceAddress,
            handyDeviceAddress,
            handyKey,
            handyAxis,
            joyPlayDeviceAddress
        )
    }

    // Handy 模式：预加载已配对设备（与蓝牙串口一致），便于在 BLE 扫不到时仍能选到已配对的 Handy
    LaunchedEffect(connectionType) {
        if (connectionType == ConnectionType.TheHandy) {
            if (Build.VERSION.SDK_INT >= 31 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                handyBondedOptions = emptyList()
                return@LaunchedEffect
            }
            val manager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val bonded = manager?.adapter?.bondedDevices?.toList().orEmpty()
            handyBondedOptions = bonded
                .mapNotNull { dev ->
                    val addr = dev.address ?: return@mapNotNull null
                    val name = (dev.name?.trim()?.takeIf { it.isNotEmpty() }
                        ?: context.getString(R.string.bluetooth_device))
                    name to addr
                }
                .distinctBy { it.second }
                .sortedBy { it.first }
            if (handyDeviceOptions.isEmpty()) handyDeviceOptions = handyBondedOptions
        }
        if (connectionType == ConnectionType.JoyPlay) {
            if (Build.VERSION.SDK_INT >= 31 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                joyPlayBondedOptions = emptyList()
                return@LaunchedEffect
            }
            val manager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val bonded = manager?.adapter?.bondedDevices?.toList().orEmpty()
            joyPlayBondedOptions = bonded
                .mapNotNull { dev ->
                    val addr = dev.address ?: return@mapNotNull null
                    val name = (dev.name?.trim()?.takeIf { it.isNotEmpty() }
                        ?: context.getString(R.string.bluetooth_device))
                    name to addr
                }
                .distinctBy { it.second }
                .sortedBy { it.first }
            if (joyPlayDeviceOptions.isEmpty()) joyPlayDeviceOptions = joyPlayBondedOptions
        }
    }

    LaunchedEffect(handyScanTick) {
        if (handyScanTick <= 0) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return@LaunchedEffect
        }
        val manager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return@LaunchedEffect

        handyScanning = true
        // 保留已配对设备，再叠加 BLE 扫描结果（避免只显示“未扫描到设备”）
        handyDeviceOptions = handyBondedOptions

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val address = dev.address ?: return
                val name = (dev.name ?: result.scanRecord?.deviceName ?: "").trim()
                if (name.isEmpty()) return  // 空名称设备过滤，不加入列表
                activity?.runOnUiThread {
                    handyDeviceOptions = (handyDeviceOptions + (name to address))
                        .distinctBy { it.second }
                        .sortedBy { it.first }
                }
            }
        }

        try {
            scanner.startScan(null, settings, callback)
            delay(10_000L)
        } catch (_: Exception) {
            // ignore
        } finally {
            try { scanner.stopScan(callback) } catch (_: Exception) { }
            handyScanning = false
        }
    }

    // 与 eciot_bletool 完全一致：BluetoothAdapter.getDefaultAdapter() + startLeScan(LeScanCallback)
    LaunchedEffect(joyPlayScanTick) {
        if (joyPlayScanTick <= 0) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return@LaunchedEffect
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            DevLog.log("JoyPlay", "需要定位权限才能扫描 BLE")
            return@LaunchedEffect
        }
        @Suppress("DEPRECATION")
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            DevLog.log("JoyPlay", "设备不支持蓝牙")
            return@LaunchedEffect
        }
        if (!bluetoothAdapter.isEnabled) {
            DevLog.log("JoyPlay", "请先打开蓝牙")
            return@LaunchedEffect
        }
        // 与 eciot 一致：检查定位开关，未开时很多机型 BLE 扫描无结果
        val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager
        val locationOn = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
            locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        if (!locationOn) {
            DevLog.log("JoyPlay", "请打开系统定位（GPS 或网络位置）后再扫描")
            activity?.runOnUiThread {
                Toast.makeText(context, context.getString(R.string.ble_need_location_hint), Toast.LENGTH_LONG).show()
            }
        }

        joyPlayScanning = true
        joyPlayDeviceOptions = joyPlayBondedOptions

        // 与 eciot_bletool 相同的 LeScanCallback，过滤空名称设备（仅显示有名称的 BLE 设备）
        val leScanCallback = @SuppressLint("MissingPermission")
        object : LeScanCallback {
            override fun onLeScan(device: BluetoothDevice, rssi: Int, scanRecord: ByteArray?) {
                try {
                    val name = device.name?.trim() ?: ""
                    val mac = device.address ?: return
                    if (mac.isEmpty()) return
                    if (name.isEmpty()) return  // 空名称设备过滤，不加入列表
                    DevLog.log("JoyPlay", "BLE 发现: $name rssi=$rssi")
                    activity?.runOnUiThread {
                        joyPlayDeviceOptions = (joyPlayDeviceOptions + (name to mac))
                            .distinctBy { it.second }
                            .sortedBy { it.first }
                    }
                } catch (e: Throwable) {
                    DevLog.log("JoyPlay", "LeScanCallback: ${e.message}")
                }
            }
        }

        @Suppress("DEPRECATION")
        val started = bluetoothAdapter.startLeScan(leScanCallback)
        DevLog.log("JoyPlay", if (started) "开始 BLE 扫描 (eciot startLeScan)..." else "startLeScan 返回 false")

        try {
            delay(12_000L)
        } finally {
            @Suppress("DEPRECATION")
            bluetoothAdapter.stopLeScan(leScanCallback)
            joyPlayScanning = false
            DevLog.log("JoyPlay", "扫描结束，共 ${joyPlayDeviceOptions.size} 个设备")
        }
    }

    var axisRanges by remember {
        mutableStateOf(
            AXIS_NAMES.associateWith { axisName ->
                getAxisRanges(context)[axisName] ?: (0f to 100f)
            }
        )
    }
    LaunchedEffect(axisRanges) {
        saveAxisRanges(context, axisRanges)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(stringResource(R.string.device_settings_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        // ---------- 连接设置 ----------
        SettingsCard(title = stringResource(R.string.connection_settings)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.connection_switch), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Switch(
                        checked = connectionEnabled,
                        onCheckedChange = { connectionEnabled = it }
                    )
                }
                OutlinedButton(
                    onClick = {
                        if (connectionTestInProgress) return@OutlinedButton
                        connectionTestInProgress = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { sendConnectionTest(context) }
                            connectionTestInProgress = false
Toast.makeText(
                            context,
                            if (ok) context.getString(R.string.connection_test_sent) else context.getString(R.string.connection_test_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                        }
                    },
                    enabled = !connectionTestInProgress
                ) {
                    Text(if (connectionTestInProgress) stringResource(R.string.connection_test_sending) else stringResource(R.string.connection_test))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { connectionDetailsExpanded = !connectionDetailsExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.connection_type_label), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Icon(
                    imageVector = if (connectionDetailsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (connectionDetailsExpanded) "收起" else "展开"
                )
            }
            AnimatedVisibility(
                visible = connectionDetailsExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(8.dp))
            ExposedDropdownMenuBox(
                expanded = connectionExpanded,
                onExpandedChange = { connectionExpanded = it }
            ) {
                OutlinedTextField(
                    readOnly = true,
                    value = stringResource(connectionType.nameResId),
                    onValueChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    label = { Text(stringResource(R.string.connection_type_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = connectionExpanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                )
                ExposedDropdownMenu(
                    expanded = connectionExpanded,
                    onDismissRequest = { connectionExpanded = false }
                ) {
                    ConnectionType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(stringResource(type.nameResId)) },
                            onClick = {
                                connectionType = type
                                connectionExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            when (connectionType) {
                ConnectionType.Serial -> {
                    LaunchedEffect(connectionType, serialPortExpanded) {
                        if (connectionType == ConnectionType.Serial) {
                            serialPortOptions = getAvailableSerialPorts(context)
                        }
                    }
                    ExposedDropdownMenuBox(
                        expanded = serialPortExpanded,
                        onExpandedChange = { serialPortExpanded = it }
                    ) {
                        val selectedDisplay = serialPortOptions.find { it.second == serialDeviceId }?.first
                            ?: if (serialDeviceId.isNotEmpty()) serialDeviceId else stringResource(R.string.select_serial_device)
                        OutlinedTextField(
                            readOnly = true,
                            value = if (serialPortOptions.isEmpty() && serialDeviceId.isEmpty()) stringResource(R.string.serial_no_device) else selectedDisplay,
                            onValueChange = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            label = { Text(stringResource(R.string.serial_device)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = serialPortExpanded) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                        )
                        ExposedDropdownMenu(
                            expanded = serialPortExpanded,
                            onDismissRequest = { serialPortExpanded = false }
                        ) {
                            if (serialPortOptions.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.serial_no_device)) },
                                    onClick = { serialPortExpanded = false }
                                )
                            } else {
                                serialPortOptions.forEach { (display, deviceId) ->
                                    DropdownMenuItem(
                                        text = { Text(display) },
                                        onClick = {
                                            serialDeviceId = deviceId
                                            serialPortExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (isDeveloperMode) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = baudRate,
                            onValueChange = { baudRate = it },
                            label = { Text(stringResource(R.string.baud_rate)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                ConnectionType.BluetoothSerial -> {
                    LaunchedEffect(connectionType, btSerialDeviceExpanded) {
                        if (connectionType != ConnectionType.BluetoothSerial) return@LaunchedEffect
                        if (Build.VERSION.SDK_INT >= 31 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            btSerialDeviceOptions = emptyList()
                            return@LaunchedEffect
                        }
                        val manager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? BluetoothManager
                        val bonded = manager?.adapter?.bondedDevices?.toList().orEmpty()
                        btSerialDeviceOptions = bonded
                            .mapNotNull { dev ->
                                val addr = dev.address ?: return@mapNotNull null
                                val name = (dev.name?.trim()?.takeIf { it.isNotEmpty() }
                                    ?: context.getString(R.string.bluetooth_device))
                                name to addr
                            }
                            .distinctBy { it.second }
                            .sortedBy { it.first }
                    }
                    ExposedDropdownMenuBox(
                        expanded = btSerialDeviceExpanded,
                        onExpandedChange = { btSerialDeviceExpanded = it }
                    ) {
                        val selectedDisplay = btSerialDeviceOptions.find { it.second == btSerialDeviceAddress }?.first
                            ?: if (btSerialDeviceAddress.isNotEmpty()) btSerialDeviceAddress else stringResource(R.string.select_bt_device)
                        OutlinedTextField(
                            readOnly = true,
                            value = if (btSerialDeviceOptions.isEmpty() && btSerialDeviceAddress.isEmpty()) stringResource(R.string.bt_serial_no_device) else selectedDisplay,
                            onValueChange = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            label = { Text(stringResource(R.string.bt_serial_device)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = btSerialDeviceExpanded) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                        )
                        ExposedDropdownMenu(
                            expanded = btSerialDeviceExpanded,
                            onDismissRequest = { btSerialDeviceExpanded = false }
                        ) {
                            if (btSerialDeviceOptions.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bt_serial_no_device)) },
                                    onClick = { btSerialDeviceExpanded = false }
                                )
                            } else {
                                btSerialDeviceOptions.forEach { (display, addr) ->
                                    DropdownMenuItem(
                                        text = { Text(display) },
                                        onClick = {
                                            btSerialDeviceAddress = addr
                                            btSerialDeviceExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = btSerialDeviceAddress,
                        onValueChange = { btSerialDeviceAddress = it },
                        label = { Text(stringResource(R.string.device_address_mac)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.mac_placeholder)) }
                    )
                    if (isDeveloperMode) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = baudRate,
                            onValueChange = { baudRate = it },
                            label = { Text(stringResource(R.string.baud_rate_bt_hint)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                ConnectionType.TCP, ConnectionType.UDP -> {
                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = { ipAddress = it },
                        label = { Text(stringResource(R.string.ip_address)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text(stringResource(R.string.port_number)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isDeveloperMode) {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.send_format_title), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = sendFormatPrefix,
                            onValueChange = { sendFormatPrefix = it },
                            label = { Text(stringResource(R.string.prefix)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = sendFormatSuffix,
                            onValueChange = { sendFormatSuffix = it },
                            label = { Text(stringResource(R.string.suffix)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                ConnectionType.TheHandy -> {
                    Text(stringResource(R.string.handy_bluetooth_device), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { handyStartScan() },
                            enabled = !handyScanning
                        ) {
                            Text(if (handyScanning) stringResource(R.string.scanning) else stringResource(R.string.scan))
                        }
                        Text(
                            text = if (handyScanning) stringResource(R.string.scanning_devices) else "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ExposedDropdownMenuBox(
                        expanded = handyDeviceExpanded,
                        onExpandedChange = { handyDeviceExpanded = it }
                    ) {
                        val selectedDisplay = handyDeviceOptions.find { it.second == handyDeviceAddress }?.first
                            ?: if (handyDeviceAddress.isNotEmpty()) handyDeviceAddress else stringResource(R.string.select_handy_device)
                        OutlinedTextField(
                            readOnly = true,
                            value = if (handyDeviceOptions.isEmpty() && handyDeviceAddress.isEmpty()) stringResource(R.string.handy_no_device) else selectedDisplay,
                            onValueChange = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            label = { Text(stringResource(R.string.serial_device)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = handyDeviceExpanded) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                        )
                        ExposedDropdownMenu(
                            expanded = handyDeviceExpanded,
                            onDismissRequest = { handyDeviceExpanded = false }
                        ) {
                            if (handyDeviceOptions.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.handy_no_device)) },
                                    onClick = { handyDeviceExpanded = false }
                                )
                            } else {
                                handyDeviceOptions.forEach { (display, address) ->
                                    DropdownMenuItem(
                                        text = { Text(display) },
                                        onClick = {
                                            handyDeviceAddress = address
                                            handyDeviceExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = handyDeviceAddress,
                        onValueChange = { handyDeviceAddress = it },
                        label = { Text(stringResource(R.string.device_address_mac)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.mac_placeholder)) }
                    )
                    Spacer(Modifier.height(12.dp))
                    ExposedDropdownMenuBox(
                        expanded = handyAxisExpanded,
                        onExpandedChange = { handyAxisExpanded = it }
                    ) {
                        OutlinedTextField(
                            readOnly = true,
                            value = handyAxis,
                            onValueChange = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            label = { Text(stringResource(R.string.axis)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = handyAxisExpanded) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                        )
                        ExposedDropdownMenu(
                            expanded = handyAxisExpanded,
                            onDismissRequest = { handyAxisExpanded = false }
                        ) {
                            AXIS_NAMES.forEach { axis ->
                                DropdownMenuItem(
                                    text = { Text(axis) },
                                    onClick = {
                                        handyAxis = axis
                                        handyAxisExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = handyKey,
                        onValueChange = { handyKey = it },
                        label = { Text(stringResource(R.string.handy_key)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ConnectionType.JoyPlay -> {
                Text(stringResource(R.string.handy_bluetooth_device), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { joyPlayStartScan() },
                        enabled = !joyPlayScanning
                    ) {
                        Text(if (joyPlayScanning) stringResource(R.string.scanning) else stringResource(R.string.scan))
                    }
                    Text(
                        text = if (joyPlayScanning) stringResource(R.string.scanning_devices) else "",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                ExposedDropdownMenuBox(
                    expanded = joyPlayDeviceExpanded,
                    onExpandedChange = { joyPlayDeviceExpanded = it }
                ) {
                    val selectedDisplay = joyPlayDeviceOptions.find { it.second == joyPlayDeviceAddress }?.first
                        ?: if (joyPlayDeviceAddress.isNotEmpty()) joyPlayDeviceAddress else stringResource(R.string.select_handy_device)
                    OutlinedTextField(
                        readOnly = true,
                        value = if (joyPlayDeviceOptions.isEmpty() && joyPlayDeviceAddress.isEmpty()) stringResource(R.string.handy_no_device) else selectedDisplay,
                        onValueChange = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        label = { Text(stringResource(R.string.serial_device)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = joyPlayDeviceExpanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                    ExposedDropdownMenu(
                        expanded = joyPlayDeviceExpanded,
                        onDismissRequest = { joyPlayDeviceExpanded = false }
                    ) {
                        if (joyPlayDeviceOptions.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.handy_no_device)) },
                                onClick = { joyPlayDeviceExpanded = false }
                            )
                        } else {
                            joyPlayDeviceOptions.forEach { (display, address) ->
                                DropdownMenuItem(
                                    text = { Text(display) },
                                    onClick = {
                                        joyPlayDeviceAddress = address
                                        joyPlayDeviceExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = joyPlayDeviceAddress,
                    onValueChange = { joyPlayDeviceAddress = it },
                    label = { Text(stringResource(R.string.device_address_mac)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.mac_placeholder)) }
                )
                if (isDeveloperMode) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.send_format_title), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sendFormatPrefix,
                        onValueChange = { sendFormatPrefix = it },
                        label = { Text(stringResource(R.string.prefix)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sendFormatSuffix,
                        onValueChange = { sendFormatSuffix = it },
                        label = { Text(stringResource(R.string.suffix)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                }
            }
                }
            }
        }

        // ---------- 输出范围设置 ----------
        SettingsCard(title = stringResource(R.string.output_range_settings)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { outputRangeExpanded = !outputRangeExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.output_range_title), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Icon(
                    imageVector = if (outputRangeExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (outputRangeExpanded) "收起" else "展开"
                )
            }
            AnimatedVisibility(
                visible = outputRangeExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(8.dp))
            AXIS_NAMES.forEach { axisName ->
                val range = axisRanges[axisName] ?: (0f to 100f)
                var minV by remember(axisName) { mutableStateOf(range.first) }
                var maxV by remember(axisName) { mutableStateOf(range.second) }
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("$axisName", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(
                            "${minV.toInt()}% – ${maxV.toInt()}%",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    RangeSlider(
                        value = minV..maxV,
                        onValueChange = { r ->
                            minV = r.start
                            maxV = r.endInclusive
                            axisRanges = axisRanges + (axisName to (minV to maxV))
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
                }
            }
        }

        // ---------- 设备控制 ----------
        SettingsCard(title = stringResource(R.string.device_control_title)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { deviceControlExpanded = !deviceControlExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.device_control_axis_sliders), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Icon(
                    imageVector = if (deviceControlExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (deviceControlExpanded) "收起" else "展开"
                )
            }
            AnimatedVisibility(
                visible = deviceControlExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(8.dp))
            AXIS_NAMES.forEach { axisName ->
                val pos = manualAxisPositions[axisName] ?: 50
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("$axisName", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("${pos}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Slider(
                        value = pos.toFloat(),
                        onValueChange = {
                            manualAxisPositions = manualAxisPositions + (axisName to it.toInt())
                            sliderSendJob?.cancel()
                            sliderSendJob = scope.launch {
                                while (isActive) {
                                    val cmd = buildAxisCommandFromPositions(context, manualAxisPositions, 500L)
                                    if (cmd.isNotEmpty()) sendAxisCommand(context, cmd)
                                    delay(100L)
                                }
                            }
                        },
                        onValueChangeFinished = {
                            sliderSendJob?.cancel()
                            sliderSendJob = null
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.device_control_script_section), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { scriptPickerLauncher.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.device_control_select_script))
                }
                Text(
                    text = selectedScriptName ?: stringResource(R.string.device_control_no_script),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (scriptPlaying) {
                            scriptPlayJob?.cancel()
                            scriptPlaying = false
                        } else {
                            val data = standaloneScriptData
                            if (data == null) {
                                Toast.makeText(context, context.getString(R.string.device_control_no_script), Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            val totalMs = if (data.durationSec > 0) (data.durationSec * 1000).toLong()
                                else data.axes.flatMap { it.actions }.maxOfOrNull { it.at } ?: 1L
                            scriptPlaying = true
                            scriptPlayJob = scope.launch {
                                var pos = scriptPlayPositionMs
                                while (pos <= totalMs && isActive) {
                                    val cmd = buildAxisCommandFromScript(context, data, pos)
                                    if (cmd.isNotEmpty()) sendAxisCommand(context, cmd)
                                    delay(100L)
                                    pos += 100L
                                    scriptPlayPositionMs = pos
                                }
                                scriptPlaying = false
                            }
                        }
                    },
                    enabled = standaloneScriptData != null
                ) {
                    Text(if (scriptPlaying) stringResource(R.string.device_control_stop_script) else stringResource(R.string.device_control_play_script))
                }
            }
                }
            }
        }
    }
}
