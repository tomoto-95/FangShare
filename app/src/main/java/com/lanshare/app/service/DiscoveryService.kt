package com.lanshare.app.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.lanshare.app.model.Device
import com.lanshare.app.util.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * 基于 NSD + UDP 广播的局域网设备发现
 * NSD 为主，UDP 广播为备用（兼容华为/荣耀设备的 NSD 缺陷）
 */
class DiscoveryService(private val context: Context) {

    companion object {
        private const val TAG = "DiscoveryService"
        const val SERVICE_TYPE = "_lanshare._tcp."
        private const val SERVICE_NAME_PREFIX = "LanShare-"

        // UDP 广播端口
        private const val UDP_PORT = 19888
        private const val UDP_DISCOVERY_MSG = "LANSHARE_DISCOVER"
        private const val UDP_RESPONSE_PREFIX = "LANSHARE_DEVICE:"
    }

    private var nsdManager: NsdManager? = null

    private val _discoveredDevices = MutableStateFlow<Map<String, Device>>(emptyMap())
    val discoveredDevices: StateFlow<Map<String, Device>> = _discoveredDevices.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    private var localDevice: Device? = null
    private var localServiceName: String = ""

    // UDP 备用
    private var udpSocket: DatagramSocket? = null
    private var udpRunning = false
    var nsdAvailable = true
        private set

    /**
     * 将设备名转换为合法的 NSD 服务名（仅 ASCII，最长 63 字符）
     */
    private fun sanitizeServiceName(deviceName: String): String {
        // 移除所有非 ASCII 字符，仅保留英文字母数字和连字符
        val asciiOnly = deviceName.replace(Regex("[^\\x00-\\x7F]"), "")
            .replace(Regex("[^a-zA-Z0-9\\-_]"), "")
        val baseName = if (asciiOnly.isNotEmpty()) asciiOnly else "Device"
        val prefix = SERVICE_NAME_PREFIX
        val maxNameLen = 63 - prefix.length
        return prefix + baseName.take(maxNameLen)
    }

    /**
     * 注册本机服务
     */
    fun registerService(device: Device) {
        localDevice = device
        localServiceName = sanitizeServiceName(device.name)

        // 先尝试 NSD 注册
        tryRegisterNsd(device)
        // 同时启动 UDP 广播响应
        startUdpResponder(device)
    }

    private fun tryRegisterNsd(device: Device) {
        try {
            val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
                Log.w(TAG, "NsdManager not available, will use UDP only")
                nsdAvailable = false
                return
            }
            nsdManager = nsd

            val serviceInfo = NsdServiceInfo().apply {
                serviceName = localServiceName
                serviceType = SERVICE_TYPE
                port = device.port
                // 属性值 — 使用简洁的键名，设备名编码为 Base64 避免非 ASCII 问题
                setAttribute("dn", android.util.Base64.encodeToString(
                    device.name.toByteArray(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP))
                setAttribute("did", device.id)
                setAttribute("dt", device.deviceType)
                device.groupId?.let { setAttribute("gid", it) }
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.d(TAG, "NSD registered: ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD registration failed: $errorCode, using UDP fallback")
                    nsdAvailable = false
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.d(TAG, "NSD unregistered")
                }
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD unregistration failed: $errorCode")
                }
            }

            nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener!!)
            Log.d(TAG, "NSD registerService called for $localServiceName")
        } catch (e: Exception) {
            Log.w(TAG, "NSD register failed: ${e.message}", e)
            nsdAvailable = false
        }
    }

    /**
     * 开始发现其他设备
     */
    fun startDiscovery() {
        stopDiscovery()
        tryStartNsdDiscovery()
        startUdpDiscovery()
    }

    private fun tryStartNsdDiscovery() {
        if (!nsdAvailable) return

        try {
            val nsd = nsdManager ?: return

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "NSD discovery started")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType == SERVICE_TYPE &&
                        serviceInfo.serviceName.startsWith(SERVICE_NAME_PREFIX)) {
                        try {
                            nsd.resolveService(serviceInfo, resolveListener)
                        } catch (e: Exception) {
                            Log.w(TAG, "resolveService failed: ${e.message}")
                        }
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    try {
                        val host = serviceInfo.host
                        val deviceId = if (host != null) {
                            "${host.hostAddress}_${serviceInfo.port}"
                        } else {
                            // host 为 null（华为设备常见），尝试用 serviceName 匹配
                            _discoveredDevices.value.entries
                                .find { it.value.name == serviceInfo.serviceName.removePrefix(SERVICE_NAME_PREFIX) }
                                ?.key ?: return
                        }
                        val current = _discoveredDevices.value.toMutableMap()
                        current.remove(deviceId)
                        _discoveredDevices.value = current
                    } catch (e: Exception) {
                        Log.w(TAG, "onServiceLost error: ${e.message}")
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "NSD discovery stopped")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "NSD start discovery failed: $errorCode, switching to UDP only")
                    nsdAvailable = false
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "NSD stop discovery failed: $errorCode")
                }
            }

            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener!!)
        } catch (e: Exception) {
            Log.w(TAG, "NSD discoverServices failed: ${e.message}")
            nsdAvailable = false
        }
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "NSD resolve failed: $errorCode - ${serviceInfo.serviceName}")
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            try {
                val host = serviceInfo.host
                if (host == null) {
                    Log.w(TAG, "Resolved service with null host: ${serviceInfo.serviceName}")
                    return
                }

                val attrs = serviceInfo.attributes ?: emptyMap()
                val rawName = attrs["dn"]
                val deviceName = if (rawName != null) {
                    try {
                        String(android.util.Base64.decode(rawName, android.util.Base64.NO_WRAP), StandardCharsets.UTF_8)
                    } catch (_: Exception) {
                        serviceInfo.serviceName.removePrefix(SERVICE_NAME_PREFIX)
                    }
                } else {
                    serviceInfo.serviceName.removePrefix(SERVICE_NAME_PREFIX)
                }

                val device = Device(
                    id = attrs["did"]?.let { String(it) } ?: "${host.hostAddress}_${serviceInfo.port}",
                    name = deviceName,
                    ipAddress = host.hostAddress ?: return,
                    port = serviceInfo.port,
                    deviceType = attrs["dt"]?.let { String(it) } ?: "android",
                    groupId = attrs["gid"]?.let { String(it) }
                )

                if (device.id != localDevice?.id) {
                    val current = _discoveredDevices.value.toMutableMap()
                    current[device.id] = device
                    _discoveredDevices.value = current
                }
            } catch (e: Exception) {
                Log.w(TAG, "onServiceResolved error: ${e.message}")
            }
        }
    }

    // ===== UDP 广播备用方案 =====

    /**
     * UDP 响应器 — 响应来自其他设备的发现请求
     */
    private fun startUdpResponder(device: Device) {
        thread(name = "LanShare-UDP-Responder") {
            try {
                val socket = DatagramSocket(null)
                socket.reuseAddress = true
                socket.broadcast = true
                socket.bind(InetSocketAddress(UDP_PORT))
                udpSocket = socket
                udpRunning = true
                Log.d(TAG, "UDP responder started on port $UDP_PORT")

                val buf = ByteArray(1024)
                while (udpRunning) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length)

                        if (msg == UDP_DISCOVERY_MSG) {
                            // 构建响应: LANSHARE_DEVICE:name|ip|port|deviceId|groupId
                            val response = buildString {
                                append(UDP_RESPONSE_PREFIX)
                                append(device.name).append("|")
                                append(device.ipAddress).append("|")
                                append(device.port).append("|")
                                append(device.id).append("|")
                                append(device.groupId ?: "")
                            }
                            val respData = response.toByteArray()
                            val respPacket = DatagramPacket(
                                respData, respData.size,
                                packet.address, packet.port
                            )
                            socket.send(respPacket)
                        }
                    } catch (e: Exception) {
                        if (udpRunning) {
                            Log.w(TAG, "UDP responder error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP responder failed to start: ${e.message}")
            }
        }
    }

    /**
     * UDP 发现 — 广播搜索局域网设备
     */
    private fun startUdpDiscovery() {
        thread(name = "LanShare-UDP-Discovery") {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                socket.soTimeout = 3000
                Log.d(TAG, "UDP discovery started")

                while (udpRunning || nsdAvailable) {
                    try {
                        // 发送广播
                        val data = UDP_DISCOVERY_MSG.toByteArray()
                        val broadcastAddr = InetAddress.getByName("255.255.255.255")
                        val packet = DatagramPacket(data, data.size, broadcastAddr, UDP_PORT)
                        socket.send(packet)

                        // 等待响应
                        val buf = ByteArray(1024)
                        val respPacket = DatagramPacket(buf, buf.size)

                        try {
                            socket.soTimeout = 2000
                            while (true) {
                                socket.receive(respPacket)
                                val msg = String(respPacket.data, 0, respPacket.length)
                                if (msg.startsWith(UDP_RESPONSE_PREFIX)) {
                                    processUdpResponse(msg, respPacket.address.hostAddress ?: continue)
                                }
                            }
                        } catch (_: java.net.SocketTimeoutException) {
                            // 超时，继续下一轮广播
                        }

                        Thread.sleep(5000) // 每 5 秒广播一次
                    } catch (e: Exception) {
                        if (udpRunning || nsdAvailable) {
                            Log.w(TAG, "UDP discovery error: ${e.message}")
                        }
                        Thread.sleep(5000)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP discovery failed: ${e.message}")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun processUdpResponse(msg: String, ipAddress: String) {
        try {
            // 格式: LANSHARE_DEVICE:name|ip|port|deviceId|groupId
            val parts = msg.removePrefix(UDP_RESPONSE_PREFIX).split("|")
            if (parts.size < 4) return

            val device = Device(
                id = parts[3],
                name = parts[0],
                ipAddress = if (parts[1].isNotEmpty()) parts[1] else ipAddress,
                port = parts[2].toIntOrNull() ?: 8080,
                groupId = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }
            )

            if (device.id != localDevice?.id) {
                val current = _discoveredDevices.value.toMutableMap()
                current[device.id] = device
                _discoveredDevices.value = current
            }
        } catch (e: Exception) {
            Log.w(TAG, "processUdpResponse error: ${e.message}")
        }
    }

    /**
     * 停止设备发现
     */
    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager?.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.w(TAG, "stopServiceDiscovery failed: ${e.message}")
            }
        }
        discoveryListener = null
    }

    /**
     * 注销本机服务
     */
    fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager?.unregisterService(it)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterService failed: ${e.message}")
            }
        }
        registrationListener = null
    }

    /**
     * 清理所有
     */
    fun destroy() {
        stopDiscovery()
        unregisterService()
        udpRunning = false
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
    }
}
