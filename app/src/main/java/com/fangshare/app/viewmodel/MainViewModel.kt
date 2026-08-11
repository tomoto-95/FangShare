package com.fangshare.app.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fangshare.app.model.Device
import com.fangshare.app.model.DeviceGroup
import com.fangshare.app.service.DiscoveryService
import com.fangshare.app.service.FileServerService
import com.fangshare.app.service.TransferClient
import com.fangshare.app.util.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 主 ViewModel — 管理全局状态
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val discoveryService = DiscoveryService(application)
    val transferClient = TransferClient(application)

    // 主机/副机角色
    enum class DeviceRole { HOST, CLIENT, UNSET }
    
    private val _role = MutableStateFlow(DeviceRole.UNSET)
    val role: StateFlow<DeviceRole> = _role.asStateFlow()

    // 本机信息
    private val _localDevice = MutableStateFlow<Device?>(null)
    val localDevice: StateFlow<Device?> = _localDevice.asStateFlow()

    private val _localDeviceName = MutableStateFlow(Build.MODEL ?: "My Phone")
    val localDeviceName: StateFlow<String> = _localDeviceName.asStateFlow()

    private val _serverPort = MutableStateFlow(8080)
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    // 当前连接的网络信息
    private val _networkStatus = MutableStateFlow(NetworkStatus())
    val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    // 设备分组
    private val _groups = MutableStateFlow<List<DeviceGroup>>(emptyList())
    val groups: StateFlow<List<DeviceGroup>> = _groups.asStateFlow()

    // 照片扫描
    private val _photos = MutableStateFlow<List<com.fangshare.app.util.PhotoItem>>(emptyList())
    val photos: StateFlow<List<com.fangshare.app.util.PhotoItem>> = _photos.asStateFlow()

    private val _selectedPhotoUris = MutableStateFlow<Set<android.net.Uri>>(emptySet())
    val selectedPhotoUris: StateFlow<Set<android.net.Uri>> = _selectedPhotoUris.asStateFlow()

    private val _photoLoading = MutableStateFlow(false)
    val photoLoading: StateFlow<Boolean> = _photoLoading.asStateFlow()

    // 传输历史
    private val _transferHistory = MutableStateFlow<List<com.fangshare.app.model.TransferTask>>(emptyList())
    val transferHistory: StateFlow<List<com.fangshare.app.model.TransferTask>> = _transferHistory.asStateFlow()

    // 待确认的接收文件（批量）
    private val _pendingReceivedFiles = MutableStateFlow<List<com.fangshare.app.model.TransferTask>>(emptyList())
    val pendingReceivedFiles: StateFlow<List<com.fangshare.app.model.TransferTask>> = _pendingReceivedFiles.asStateFlow()

    // 保存目录
    private val _saveDirectory = MutableStateFlow(getCurrentSaveDir())
    val saveDirectory: StateFlow<String> = _saveDirectory.asStateFlow()

    init {
        // 监听接收通知 (SharedFlow + StateFlow 双重保障)
        viewModelScope.launch {
            com.fangshare.app.service.ReceiveNotifier.receivedFiles.collect { task ->
                _pendingReceivedFiles.value = _pendingReceivedFiles.value + task
                addTransferToHistory(task)
            }
        }
        // 从 StateFlow 同步初始状态（防止 SharedFlow 丢失）
        com.fangshare.app.service.ReceiveNotifier.pendingFiles.value.let { existing ->
            if (existing.isNotEmpty()) {
                existing.forEach { addTransferToHistory(it) }
                _pendingReceivedFiles.value = existing
            }
        }
    }

    private fun getCurrentSaveDir(): String {
        val prefs = getApplication<android.app.Application>()
            .getSharedPreferences("lan_share_prefs", android.content.Context.MODE_PRIVATE)
        return prefs.getString("save_directory", null)
            ?: "默认 (内部存储/Fangshare)"
    }

    fun setCustomSaveDirectory(path: String) {
        com.fangshare.app.util.FileUtils.setCustomSaveDirectory(getApplication(), path)
        _saveDirectory.value = path
    }

    fun resetSaveDirectory() {
        val prefs = getApplication<android.app.Application>()
            .getSharedPreferences("lan_share_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().remove("save_directory").apply()
        _saveDirectory.value = "默认 (内部存储/Fangshare)"
    }

    fun dismissReceiveNotification() {
        _pendingReceivedFiles.value = emptyList()
        com.fangshare.app.service.ReceiveNotifier.clearPending()
    }

    fun setRole(role: DeviceRole) {
        _role.value = role
        val prefs = getApplication<android.app.Application>()
            .getSharedPreferences("lan_share_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("device_role", role.name).apply()

        // 角色确认后按角色重启服务（副机→常驻模式；主机→普通模式服从系统调度）
        // 首次选择角色时 initialize() 可能已在角色选择前启动，这里必须按新角色刷新
        if (role != DeviceRole.UNSET) {
            startFileServer()
            startDeviceDiscovery()
        }
    }

    fun loadSavedRole(): DeviceRole {
        val prefs = getApplication<android.app.Application>()
            .getSharedPreferences("lan_share_prefs", android.content.Context.MODE_PRIVATE)
        val saved = prefs.getString("device_role", null)
        return try { saved?.let { DeviceRole.valueOf(it) } ?: DeviceRole.UNSET } catch (_: Exception) { DeviceRole.UNSET }
    }

    // 所有已发现的设备
    val allDevices: StateFlow<List<Device>>
        get() {
            val flow = MutableStateFlow<List<Device>>(emptyList())
            viewModelScope.launch {
                discoveryService.discoveredDevices.collect {
                    flow.value = it.values.toList()
                }
            }
            return flow
        }

    /**
     * 初始化 — 启动服务发现和文件服务器
     */
    fun initialize() {
        NetworkUtils.acquireMulticastLock(getApplication())
        startFileServer()
        startDeviceDiscovery()
        updateNetworkStatus()
    }

    private fun startFileServer() {
        val port = _serverPort.value
        val ip = NetworkUtils.getLocalIpAddress()
        val deviceName = _localDeviceName.value

        if (ip != null) {
            val device = Device.localDevice(
                name = deviceName,
                ip = ip,
                port = port
            )
            _localDevice.value = device
            discoveryService.registerService(device)
        }

        // 副机常驻：申请忽略电池优化（系统白名单），防止后台被杀
        val persistent = _role.value == DeviceRole.CLIENT
        if (persistent) {
            com.fangshare.app.util.PowerKeeper.requestIgnoreBatteryOptimizationsIfNeeded(getApplication())
        }

        // 安全启动前台服务（华为/荣耀设备上可能抛出异常）
        try {
            val intent = Intent(getApplication(), FileServerService::class.java).apply {
                putExtra("port", port)
                putExtra(FileServerService.EXTRA_PERSISTENT, persistent)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplication<Application>().startForegroundService(intent)
            } else {
                getApplication<Application>().startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "startForegroundService failed: ${e.message}", e)
            // 荣耀/华为设备 fallback：尝试普通 startService
            try {
                val intent = Intent(getApplication(), FileServerService::class.java).apply {
                    putExtra("port", port)
                    putExtra(FileServerService.EXTRA_PERSISTENT, persistent)
                }
                getApplication<Application>().startService(intent)
            } catch (e2: Exception) {
                android.util.Log.e("MainViewModel", "startService also failed: ${e2.message}", e2)
            }
        }
    }

    /**
     * 获取 NSD 是否可用（华为/荣耀设备上可能不可用）
     */
    val isNsdAvailable: Boolean
        get() = discoveryService.nsdAvailable

    fun startDeviceDiscovery() {
        discoveryService.startDiscovery()
    }

    fun updateNetworkStatus() {
        val context = getApplication<Application>()
        _networkStatus.value = NetworkStatus(
            isConnected = NetworkUtils.isWifiConnected(context),
            ipAddress = NetworkUtils.getLocalIpAddress(),
            ssid = NetworkUtils.getWifiSsid(context)
        )
    }

    fun setDeviceName(name: String) {
        _localDeviceName.value = name
        // 需要重启服务以更新名称
    }

    fun setServerPort(port: Int) {
        _serverPort.value = port
    }

    // --- 设备分组管理 ---

    fun createGroup(name: String) {
        val group = DeviceGroup(name = name)
        _groups.value = _groups.value + group
        persistGroups()
    }

    fun addDeviceToGroup(groupId: String, deviceId: String) {
        val idx = _groups.value.indexOfFirst { it.id == groupId }
        if (idx >= 0) {
            val group = _groups.value[idx]
            if (!group.deviceIds.contains(deviceId)) {
                _groups.value = _groups.value.toMutableList().also {
                    it[idx] = group.copy(deviceIds = group.deviceIds + deviceId)
                }
                persistGroups()
            }
        }
    }

    fun removeDeviceFromGroup(groupId: String, deviceId: String) {
        val idx = _groups.value.indexOfFirst { it.id == groupId }
        if (idx >= 0) {
            val group = _groups.value[idx]
            _groups.value = _groups.value.toMutableList().also {
                it[idx] = group.copy(deviceIds = group.deviceIds - deviceId)
            }
            persistGroups()
        }
    }

    fun deleteGroup(groupId: String) {
        _groups.value = _groups.value.filter { it.id != groupId }
        persistGroups()
    }

    private fun persistGroups() {
        try {
            val prefs = getApplication<android.app.Application>()
                .getSharedPreferences("lan_share_groups", android.content.Context.MODE_PRIVATE)
            prefs.edit()
                .putString("groups_data", com.google.gson.Gson().toJson(_groups.value))
                .commit()
        } catch (_: Exception) {}
    }

    // --- 照片管理 ---

    fun scanPhotos() {
        if (_photoLoading.value) return
        _photoLoading.value = true
        viewModelScope.launch {
            try {
                val app = getApplication<android.app.Application>()
                val photos = com.fangshare.app.util.PhotoUtils.scanPhotos(app)
                _photos.value = photos
            } catch (_: Exception) {}
            _photoLoading.value = false
        }
    }

    fun togglePhotoSelection(uri: android.net.Uri) {
        _selectedPhotoUris.value = _selectedPhotoUris.value.toMutableSet().apply {
            if (contains(uri)) remove(uri) else add(uri)
        }
    }

    fun selectAllPhotos(photos: List<com.fangshare.app.util.PhotoItem>) {
        _selectedPhotoUris.value = photos.map { it.uri }.toSet()
    }

    fun clearPhotoSelection() {
        _selectedPhotoUris.value = emptySet()
    }

    fun sendSelectedPhotos(device: Device) {
        val uris = _selectedPhotoUris.value.toList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            uris.forEach { uri ->
                try {
                    val task = transferClient.sendFile(device, uri, _localDeviceName.value)
                    addTransferToHistory(task)
                } catch (_: Exception) {}
            }
            _selectedPhotoUris.value = emptySet()
        }
    }

    fun sendSelectedPhotosToGroup(group: DeviceGroup) {
        val uris = _selectedPhotoUris.value.toList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val groupDevices = discoveryService.discoveredDevices.value.values
                .filter { it.id in group.deviceIds }
            uris.forEach { uri ->
                groupDevices.forEach { device ->
                    try {
                        val task = transferClient.sendFile(device, uri, _localDeviceName.value)
                        addTransferToHistory(task)
                    } catch (_: Exception) {}
                }
            }
            _selectedPhotoUris.value = emptySet()
        }
    }

    // --- 传输历史 ---

    fun addTransferToHistory(task: com.fangshare.app.model.TransferTask) {
        _transferHistory.value = (listOf(task) + _transferHistory.value).take(50)
    }

    fun clearTransferHistory() {
        _transferHistory.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        discoveryService.destroy()
        NetworkUtils.releaseMulticastLock()
    }
}

data class NetworkStatus(
    val isConnected: Boolean = false,
    val ipAddress: String? = null,
    val ssid: String? = null
)
