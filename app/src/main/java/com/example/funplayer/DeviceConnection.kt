@file:Suppress("DEPRECATION")
package com.example.funplayer

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAdapter.LeScanCallback
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.funplayer.handyplug.Handyplug
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal fun sendUdpMessage(host: String, port: Int, message: String): Boolean {
    if (message.isEmpty()) return true
    return try {
        java.net.DatagramSocket().use { socket ->
            val bytes = message.toByteArray(Charsets.UTF_8)
            val packet = java.net.DatagramPacket(bytes, bytes.size, java.net.InetAddress.getByName(host), port)
            socket.send(packet)
        }
        true
    } catch (_: Exception) {
        false
    }
}

internal fun sendTcpMessage(host: String, port: Int, message: String): Boolean {
    if (message.isEmpty()) return true
    return try {
        java.net.Socket(host, port).use { socket ->
            socket.getOutputStream().use { os ->
                os.write(message.toByteArray(Charsets.UTF_8))
                os.flush()
            }
        }
        true
    } catch (_: Exception) {
        false
    }
}

internal fun sendTcpBytes(host: String, port: Int, bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return true
    return try {
        java.net.Socket(host, port).use { socket ->
            socket.getOutputStream().use { os ->
                os.write(bytes)
                os.flush()
            }
        }
        true
    } catch (_: Exception) {
        false
    }
}

internal fun buildHandyTestPayload(): ByteArray {
    val vector = Handyplug.LinearCmd.Vector.newBuilder()
        .setIndex(0)
        .setDuration(900)
        .setPosition(0.5)
        .build()
    val linearCmd = Handyplug.LinearCmd.newBuilder()
        .setId(1)
        .setDeviceIndex(0)
        .addVectors(vector)
        .build()
    val handyMsg = Handyplug.HandyMessage.newBuilder()
        .setLinearCmd(linearCmd)
        .build()
    return Handyplug.Payload.newBuilder()
        .addMessages(handyMsg)
        .build()
        .toByteArray()
}

internal fun buildHandyLinearPayload(
    context: android.content.Context,
    script: FunscriptData?,
    axisId: String,
    currentPositionMs: Long
): ByteArray? {
    val posDur = getAxisPositionAndDurationForHandy(context, script, axisId, currentPositionMs) ?: return null
    val (position, durationMs) = posDur
    val vector = Handyplug.LinearCmd.Vector.newBuilder()
        .setIndex(0)
        .setDuration(durationMs.toInt().coerceIn(1, 65535))
        .setPosition(position)
        .build()
    val linearCmd = Handyplug.LinearCmd.newBuilder()
        .setId(1)
        .setDeviceIndex(0)
        .addVectors(vector)
        .build()
    val handyMsg = Handyplug.HandyMessage.newBuilder()
        .setLinearCmd(linearCmd)
        .build()
    val payload = Handyplug.Payload.newBuilder()
        .addMessages(handyMsg)
        .build()
    return payload.toByteArray()
}

internal fun sendBluetoothSerialMessage(context: android.content.Context, address: String, message: String): Boolean {
    if (message.isEmpty() || address.isBlank()) return true
    if (Build.VERSION.SDK_INT >= 31 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    val manager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
    val adapter: BluetoothAdapter = manager.adapter ?: return false
    val device = try { adapter.getRemoteDevice(address) } catch (_: Exception) { return false }
    val socket: BluetoothSocket = try { device.createRfcommSocketToServiceRecord(BT_SPP_UUID) } catch (_: Exception) { return false }
    return try {
        try { adapter.cancelDiscovery() } catch (_: Exception) { }
        socket.connect()
        socket.outputStream.use { os ->
            os.write(message.toByteArray(Charsets.UTF_8))
            os.flush()
        }
        true
    } catch (_: Exception) {
        false
    } finally {
        try { socket.close() } catch (_: Exception) { }
    }
}

internal object HandyBleClient {
    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var connectedAddress: String? = null
    private var pendingWrite: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private var negotiatedMtu: Int = 23

    private fun hasPermission(context: android.content.Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun closeInternal() {
        try { gatt?.close() } catch (_: Exception) { }
        gatt = null
        txCharacteristic = null
        connectedAddress = null
        pendingWrite = null
        negotiatedMtu = 23
    }

    suspend fun write(context: android.content.Context, address: String, payload: ByteArray, useWriteWithResponse: Boolean = true): Boolean {
        if (address.isBlank() || payload.isEmpty()) {
            DevLog.log("Handy", "连接/写入失败: 设备地址为空或载荷为空")
            return false
        }
        if (Build.VERSION.SDK_INT >= 31 && !hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)) {
            DevLog.log("Handy", "连接失败: 缺少 BLUETOOTH_CONNECT 权限")
            return false
        }
        val forJoyPlay = !useWriteWithResponse
        val ok = ensureConnected(context, address, forJoyPlay)
        if (!ok) return false
        val g = gatt
        val ch = txCharacteristic
        if (g == null || ch == null) {
            DevLog.log("Handy", "写入失败: GATT 或特征为空，连接可能已断开")
            return false
        }
        if (useWriteWithResponse) kotlinx.coroutines.delay(150L)
        val written = writeChunked(g, ch, payload, useWriteWithResponse)
        if (!written) DevLog.log("Handy", "写入失败: 未完成或超时")
        return written
    }

    private suspend fun ensureConnected(context: android.content.Context, address: String, forJoyPlay: Boolean = false): Boolean {
        if (gatt != null && txCharacteristic != null && connectedAddress == address) return true
        closeInternal()

        val manager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (manager == null) {
            DevLog.log("Handy", "连接失败: 无法获取 BluetoothManager")
            return false
        }
        val adapter = manager.adapter
        if (adapter == null) {
            DevLog.log("Handy", "连接失败: 蓝牙适配器不可用")
            return false
        }
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            DevLog.log("Handy", "连接失败: 无效设备地址 $address, ${e.message}")
            return false
        }

        suspend fun tryConnect(autoConnect: Boolean): Boolean? {
            return withTimeoutOrNull(25_000L) {
                kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                    val callback = object : BluetoothGattCallback() {
                        private var finished = false

                        private fun finish(value: Boolean) {
                            if (finished) return
                            finished = true
                            cont.resume(value)
                        }

                        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                DevLog.log("Handy", "连接失败: onConnectionStateChange status=$status (非 GATT_SUCCESS)")
                                closeInternal()
                                finish(false)
                                return
                            }
                            if (newState == BluetoothProfile.STATE_CONNECTED) {
                                try { g.requestMtu(247) } catch (_: Exception) { }
                                g.discoverServices()
                            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                if (finished) DevLog.log("Handy", "BLE 连接已断开，下次发送将自动重连")
                                else DevLog.log("Handy", "连接断开: STATE_DISCONNECTED")
                                closeInternal()
                                finish(false)
                            }
                        }

                        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                DevLog.log("Handy", "连接失败: onServicesDiscovered status=$status (非 GATT_SUCCESS)")
                                closeInternal()
                                finish(false)
                                return
                            }
                            val service: BluetoothGattService? = g.getService(HANDY_SERVICE_UUID)
                            val ch: BluetoothGattCharacteristic? = service?.getCharacteristic(HANDY_CHARACTERISTIC_UUID)
                            if (ch == null) {
                                DevLog.log("Handy", "连接失败: 未找到 Handy 服务或特征 UUID")
                                closeInternal()
                                finish(false)
                                return
                            }
                            txCharacteristic = ch
                            finish(true)
                        }

                        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                DevLog.log("Handy", "写入回调: status=$status (非 GATT_SUCCESS)")
                            }
                            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
                            pendingWrite = null
                        }
                    }

                    val g = if (forJoyPlay) {
                        device.connectGatt(context, autoConnect, callback)
                    } else if (Build.VERSION.SDK_INT >= 23) {
                        device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        device.connectGatt(context, autoConnect, callback)
                    }
                    gatt = g
                    connectedAddress = address

                    cont.invokeOnCancellation {
                        closeInternal()
                    }
                }
            }
        }

        var result = tryConnect(autoConnect = false)
        if (result != true) {
            DevLog.log("Handy", "首次连接未成功，关闭后重试一次(直连)")
            closeInternal()
            kotlinx.coroutines.delay(500L)
            result = tryConnect(autoConnect = false)
        }
        if (result != true) {
            DevLog.log("Handy", "直连两次未成，尝试后台连接(autoConnect=true)")
            closeInternal()
            kotlinx.coroutines.delay(300L)
            result = tryConnect(autoConnect = true)
        }
        if (result != true) {
            DevLog.log("Handy", "连接失败: 连接超时(25s)或未完成，已尝试直连与后台连接")
        }
        return result == true
    }

    private suspend fun writeChunked(g: BluetoothGatt, ch: BluetoothGattCharacteristic, payload: ByteArray, useWriteWithResponse: Boolean): Boolean {
        if (!useWriteWithResponse) return writeOnceNoResponseEciotStyle(g, ch, payload)
        suspend fun tryWriteNoRsp(data: ByteArray, index: Int): Boolean {
            var delayMs = 80L
            repeat(4) { attempt ->
                if (attempt > 0) kotlinx.coroutines.delay(delayMs).also { delayMs = (delayMs * 2).coerceAtMost(400L) }
                if (writeOnceNoResponse(g, ch, data, index)) return true
            }
            return false
        }
        if (writeOnceWithResponse(g, ch, payload, 1)) return true
        return tryWriteNoRsp(payload, 1)
    }

    private fun writeOnceNoResponseEciotStyle(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        ch.value = bytes
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return try {
            g.writeCharacteristic(ch)
        } catch (e: Exception) {
            DevLog.log("Handy", "写入失败(JoyPlay/eciot): ${e.message}")
            false
        }
    }

    private suspend fun writeOnceWithResponse(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray, chunkIndex: Int = 0): Boolean {
        ch.value = bytes
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val started = try { g.writeCharacteristic(ch) } catch (e: Exception) {
            DevLog.log("Handy", "写入(带响应)失败(第${chunkIndex}包): ${e.message}")
            return false
        }
        if (!started) return false
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pendingWrite = deferred
        val ok = withTimeoutOrNull(800L) { deferred.await() }
        pendingWrite = null
        return ok == true
    }

    private suspend fun writeOnceNoResponse(g: BluetoothGatt, ch: BluetoothGattCharacteristic, bytes: ByteArray, chunkIndex: Int = 0): Boolean {
        ch.value = bytes
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val started = try { g.writeCharacteristic(ch) } catch (e: Exception) {
            DevLog.log("Handy", "写入失败(第${chunkIndex}包): writeCharacteristic 异常 ${e.message} → 判断: 发不出去")
            return false
        }
        if (!started) {
            DevLog.log("Handy", "写入失败(第${chunkIndex}包): writeCharacteristic 返回 false → 判断: 发不出去(可能 MTU 过大或队列满)")
            return false
        }
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pendingWrite = deferred
        val ok = withTimeoutOrNull(500L) { deferred.await() }
        pendingWrite = null
        when {
            ok == true -> return true
            ok == false -> { DevLog.log("Handy", "写入(第${chunkIndex}包): status 非 GATT_SUCCESS"); return false }
            else -> return true
        }
    }
}

internal fun sendSerialMessage(context: android.content.Context, deviceId: String, baudRate: Int, message: String): Boolean {
    if (message.isEmpty() || deviceId.isBlank()) return true
    val parts = deviceId.trim().split(":")
    if (parts.size < 2) return false
    val vid = parts[0].trim().toIntOrNull(16) ?: return false
    val pid = parts[1].trim().toIntOrNull(16) ?: return false
    val usbManager = context.getSystemService(android.content.Context.USB_SERVICE) as? UsbManager ?: return false
    val device = usbManager.deviceList.values.find { it.vendorId == vid && it.productId == pid } ?: return false
    val connection = usbManager.openDevice(device) ?: return false
    return try {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: run {
            connection.close()
            return false
        }
        val port = driver.ports.firstOrNull() ?: run {
            connection.close()
            return false
        }
        port.open(connection)
        port.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        port.write(message.toByteArray(Charsets.UTF_8), 5000)
        port.close()
        true
    } catch (_: Exception) {
        try { connection.close() } catch (_: Exception) { }
        false
    }
}

internal suspend fun sendConnectionTest(context: android.content.Context): Boolean {
    val connType = getConnectionType(context)
    if (connType != ConnectionType.UDP && connType != ConnectionType.TCP &&
        connType != ConnectionType.Serial && connType != ConnectionType.BluetoothSerial &&
        connType != ConnectionType.TheHandy && connType != ConnectionType.JoyPlay) return false
    return when (connType) {
        ConnectionType.UDP -> {
            val prefix = getSendFormatPrefix(context)
            val suffix = getSendFormatSuffix(context)
            sendUdpMessage(getDeviceIp(context), getDevicePort(context), prefix + CONNECTION_TEST_MESSAGE + suffix)
        }
        ConnectionType.TCP -> {
            val prefix = getSendFormatPrefix(context)
            val suffix = getSendFormatSuffix(context)
            sendTcpMessage(getDeviceIp(context), getDevicePort(context), prefix + CONNECTION_TEST_MESSAGE + suffix)
        }
        ConnectionType.Serial -> {
            val prefix = getSendFormatPrefix(context)
            val suffix = getSendFormatSuffix(context)
            sendSerialMessage(context, getSerialDeviceId(context), getBaudRate(context), prefix + CONNECTION_TEST_MESSAGE + suffix)
        }
        ConnectionType.BluetoothSerial -> {
            val prefix = getSendFormatPrefix(context)
            val suffix = getSendFormatSuffix(context)
            sendBluetoothSerialMessage(context, getBtSerialDeviceAddress(context), prefix + CONNECTION_TEST_MESSAGE + suffix)
        }
        ConnectionType.TheHandy -> {
            val payload = buildHandyTestPayload()
            HandyBleClient.write(context, getHandyDeviceAddress(context), payload)
        }
        ConnectionType.JoyPlay -> {
            val prefix = getSendFormatPrefix(context)
            val suffix = getSendFormatSuffix(context)
            val message = prefix + CONNECTION_TEST_MESSAGE + suffix
            HandyBleClient.write(context, getJoyPlayDeviceAddress(context), message.toByteArray(Charsets.UTF_8), useWriteWithResponse = false)
        }
        else -> false
    }
}

internal suspend fun sendAxisCommand(context: android.content.Context, axisCommand: String): Boolean {
    if (axisCommand.isEmpty()) return false
    val connType = getConnectionType(context)
    if (connType != ConnectionType.UDP && connType != ConnectionType.TCP &&
        connType != ConnectionType.Serial && connType != ConnectionType.BluetoothSerial &&
        connType != ConnectionType.TheHandy && connType != ConnectionType.JoyPlay
    ) return false
    return withContext(Dispatchers.IO) {
        when (connType) {
            ConnectionType.UDP -> {
                val prefix = getSendFormatPrefix(context)
                val suffix = getSendFormatSuffix(context)
                sendUdpMessage(getDeviceIp(context), getDevicePort(context), prefix + axisCommand + suffix)
            }
            ConnectionType.TCP -> {
                val prefix = getSendFormatPrefix(context)
                val suffix = getSendFormatSuffix(context)
                sendTcpMessage(getDeviceIp(context), getDevicePort(context), prefix + axisCommand + suffix)
            }
            ConnectionType.Serial -> {
                val prefix = getSendFormatPrefix(context)
                val suffix = getSendFormatSuffix(context)
                sendSerialMessage(context, getSerialDeviceId(context), getBaudRate(context), prefix + axisCommand + suffix)
            }
            ConnectionType.BluetoothSerial -> {
                val prefix = getSendFormatPrefix(context)
                val suffix = getSendFormatSuffix(context)
                sendBluetoothSerialMessage(context, getBtSerialDeviceAddress(context), prefix + axisCommand + suffix)
            }
            ConnectionType.TheHandy -> {
                val axisId = getHandyAxis(context)
                val segment = parseAxisCommandSegments(axisCommand).firstOrNull { it.first == axisId } ?: return@withContext false
                val (_, pos, dur) = segment
                val ranges = getAxisRanges(context)
                val (minR, maxR) = ranges[axisId] ?: (0f to 100f)
                val t = (pos.coerceIn(0, 100) / 100f)
                val mappedFloat = minR + t * (maxR - minR)
                val position = (mappedFloat / 100.0).coerceIn(0.0, 1.0)
                val payload = buildHandyLinearPayloadFromPosition(context, axisId, position, dur.toInt())
                if (payload != null) HandyBleClient.write(context, getHandyDeviceAddress(context), payload) else false
            }
            ConnectionType.JoyPlay -> {
                val prefix = getSendFormatPrefix(context)
                val suffix = getSendFormatSuffix(context)
                val message = prefix + axisCommand + suffix
                HandyBleClient.write(context, getJoyPlayDeviceAddress(context), message.toByteArray(Charsets.UTF_8), useWriteWithResponse = false)
            }
            else -> false
        }
    }
}

internal fun getAvailableSerialPorts(context: android.content.Context): List<Pair<String, String>> {
    val usbManager = context.getSystemService(android.content.Context.USB_SERVICE) as? UsbManager ?: return emptyList()
    val drivers = try {
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    } catch (_: Exception) {
        return emptyList()
    }
    return drivers.map { driver ->
        val d = driver.device
        val vid = d.vendorId.toString(16).lowercase()
        val pid = d.productId.toString(16).lowercase()
        val id = "$vid:$pid"
        val name = d.productName?.takeIf { it.isNotEmpty() } ?: d.deviceName ?: context.getString(R.string.usb_serial)
        Pair("$name ($id)", id)
    }
}
