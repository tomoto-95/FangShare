package com.fangshare.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage as CoilImage
import com.fangshare.app.model.Device
import com.fangshare.app.ui.theme.*
import com.fangshare.app.viewmodel.NetworkStatus
import com.fangshare.app.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(viewModel: MainViewModel) {
    val devices by viewModel.allDevices.collectAsState()
    val allDevices by viewModel.discoveryService.discoveredDevices.collectAsState()
    val role by viewModel.role.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()
    val scope = rememberCoroutineScope()
    var showSendDialog by remember { mutableStateOf(false) }
    var selectedDeviceForSend by remember { mutableStateOf<Device?>(null) }
    var browseTarget by remember { mutableStateOf<Device?>(null) }
    val groupDevices = allDevices.values.filter { it.isInGroup }
    val otherDevices = allDevices.values.filter { !it.isInGroup }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty() && selectedDeviceForSend != null) {
            scope.launch {
                uris.forEach { uri ->
                    viewModel.addTransferToHistory(viewModel.transferClient.sendFile(selectedDeviceForSend!!, uri, viewModel.localDeviceName.value))
                }
                selectedDeviceForSend = null
            }
        }
    }

    if (browseTarget != null) {
        FileBrowserSheet(viewModel, browseTarget!!.ipAddress, browseTarget!!.port, browseTarget!!.name, { browseTarget = null })
        return
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(title = { Text("设备", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))

        if (!networkStatus.isConnected) {
            Card(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.1f))) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WifiOff, null, tint = Warning, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column { Text("未连接 WiFi", fontWeight = FontWeight.SemiBold); Text("请连接到 WiFi 网络以发现其他设备", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 本机卡片
            item { Text("本机", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 4.dp)) }
            item { LocalDeviceCard(viewModel, networkStatus) }

            // 发现设备
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("发现设备 (${allDevices.size})", style = MaterialTheme.typography.titleMedium)
                    FilledTonalIconButton(onClick = { viewModel.startDeviceDiscovery() }) { Icon(Icons.Filled.Refresh, "刷新") }
                }
            }

            if (groupDevices.isNotEmpty()) {
                item { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Groups, null, tint = Success, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("家庭组设备", style = MaterialTheme.typography.labelLarge, color = Success) } }
                items(groupDevices, key = { it.id }) { DeviceRow(it, true, { selectedDeviceForSend = it; filePickerLauncher.launch(arrayOf("*/*")) }, if (role == MainViewModel.DeviceRole.HOST) {{ browseTarget = it }} else null) }
            }
            if (otherDevices.isNotEmpty()) {
                item { Text("其他设备", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
                items(otherDevices, key = { it.id }) { DeviceRow(it, false, { selectedDeviceForSend = it; filePickerLauncher.launch(arrayOf("*/*")) }, if (role == MainViewModel.DeviceRole.HOST) {{ browseTarget = it }} else null) }
            }
            if (allDevices.isEmpty() && networkStatus.isConnected) { item { EmptyState(Icons.Outlined.SearchOff, "搜索设备中…", "确保其他设备也打开了 Fangshare 并连接同一 WiFi") } }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun LocalDeviceCard(viewModel: MainViewModel, networkStatus: NetworkStatus) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(viewModel.localDeviceName.value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold); Text(networkStatus.ipAddress ?: "获取 IP 中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Box(Modifier.size(8.dp).clip(CircleShape).background(Success))
        }
    }
}

@Composable
fun DeviceRow(device: Device, isInGroup: Boolean, onSend: () -> Unit, onBrowse: (() -> Unit)?) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (isInGroup) Success.copy(alpha = 0.1f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.PhoneAndroid, null, tint = if (isInGroup) Success else MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(device.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold); if (isInGroup) { Spacer(Modifier.width(6.dp)); Surface(shape = RoundedCornerShape(4.dp), color = Success.copy(alpha = 0.12f)) { Text("家庭组", style = MaterialTheme.typography.labelSmall, color = Success, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) } } }
                Text(device.ipAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (device.isOnline) Success else MaterialTheme.colorScheme.onSurfaceVariant))
            Spacer(Modifier.width(12.dp))
            if (onBrowse != null) { FilledTonalButton(onClick = onBrowse, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))) { Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("浏览", style = MaterialTheme.typography.labelMedium) }; Spacer(Modifier.width(6.dp)) }
            FilledTonalButton(onClick = onSend, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { Icon(Icons.Filled.Send, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("发送", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

// ===== FileBrowserSheet =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserSheet(viewModel: MainViewModel, deviceIp: String, devicePort: Int, deviceName: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    var currentPath by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var parentPath by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPhotos by remember { mutableStateOf(false) }
    var photos by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var photoOffset by remember { mutableStateOf(0) }
    var hasMorePhotos by remember { mutableStateOf(true) }
    val cl = remember { OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    fun loadFiles(path: String) { scope.launch { isLoading = true; error = null
        try { val r = withContext(Dispatchers.IO) { cl.newCall(Request.Builder().url("http://$deviceIp:$devicePort/files/list?path=${Uri.encode(path)}").get().build()).execute().use { if (!it.isSuccessful) throw Exception("${it.code}"); it.body?.string() ?: "" } }; val m = com.google.gson.Gson().fromJson(r, java.util.HashMap::class.java) as java.util.HashMap<String, Any>; currentPath = m["currentPath"] as? String ?: path; parentPath = m["parentPath"] as? String ?: ""; val raw = m["files"] as? List<*> ?: emptyList<Any>(); files = raw.mapNotNull { val o = it as? Map<*, *> ?: return@mapNotNull null; FileEntry(o["name"] as? String ?: "", o["path"] as? String ?: "", o["isDirectory"] as? Boolean ?: false, (o["size"] as? Number)?.toLong() ?: 0L, (o["lastModified"] as? Number)?.toLong() ?: 0L) } } catch (e: Exception) { error = e.message }; isLoading = false } }

    fun loadPhotos(reset: Boolean = false) { scope.launch { isLoading = true; error = null; showPhotos = true
        try { val off = if (reset) 0 else photoOffset
            val r = withContext(Dispatchers.IO) { cl.newCall(Request.Builder().url("http://$deviceIp:$devicePort/photos/list?limit=60&offset=$off").get().build()).execute().use { if (!it.isSuccessful) throw Exception("${it.code}"); it.body?.string() ?: "" } }
            val m = com.google.gson.Gson().fromJson(r, java.util.HashMap::class.java) as java.util.HashMap<String, Any>
            val batch = (m["photos"] as? List<*>)?.filterIsInstance<Map<String, Any>>() ?: emptyList()
            photos = if (reset) batch else photos + batch
            photoOffset = off + batch.size
            hasMorePhotos = batch.size >= 60
        } catch (e: Exception) { error = e.message }; isLoading = false } }

    LaunchedEffect(Unit) { loadFiles(currentPath) }

    // 照片网格滚动到底部时自动加载下一页
    LaunchedEffect(gridState, hasMorePhotos) {
        androidx.compose.runtime.snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { last ->
                if (hasMorePhotos && last != null && photos.isNotEmpty() && last >= photos.size - 8) {
                    loadPhotos(false)
                }
            }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(title = { Text(deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.ArrowBack, "返回") } },
            actions = { TextButton(onClick = { if (showPhotos) showPhotos = false else loadPhotos(true) }) { Text(if (showPhotos) "浏览文件" else "所有照片") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))

        if (!showPhotos) Text(currentPath.ifEmpty { "根目录" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)

        Box(Modifier.weight(1f)) {
            if (showPhotos) {
                if (isLoading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("加载中…") }
                else if (error != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(error!!, color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(8.dp)); Button(onClick = { loadPhotos(true) }) { Text("重试") } } }
                else Column {
                    // 选择栏
                    var selectedIndices by remember { mutableStateOf(setOf<Int>()) }
                    if (selectedIndices.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { selectedIndices = emptySet() }) { Text("取消 (${selectedIndices.size})") }
                            Button(onClick = {
                                val sel = selectedIndices.toList()
                                scope.launch {
                                    sel.forEach { i ->
                                        try {
                                            val p = photos[i]
                                            val saveDir = com.fangshare.app.util.FileUtils.getReceiveDirectory(context)
                                            val out = java.io.File(saveDir, p["name"] as? String ?: "photo.jpg")
                                            withContext(Dispatchers.IO) {
                                                val r = cl.newCall(Request.Builder().url("http://$deviceIp:$devicePort/files/download?path=${Uri.encode(p["path"] as? String ?: "")}").get().build()).execute()
                                                if (r.isSuccessful) r.body?.byteStream()?.use { i2 -> out.outputStream().use { o -> i2.copyTo(o) } }
                                            }
                                            viewModel.addTransferToHistory(com.fangshare.app.model.TransferTask(
                                                id = java.util.UUID.randomUUID().toString(), fileName = out.name, filePath = out.absolutePath, fileSize = out.length(),
                                                mimeType = "image/jpeg", status = com.fangshare.app.model.TransferStatus.COMPLETED,
                                                direction = com.fangshare.app.model.TransferDirection.RECEIVING,
                                                sourceDevice = com.fangshare.app.model.Device(id = deviceIp, name = deviceName, ipAddress = deviceIp, port = devicePort)))
                                        } catch (_: Exception) {}
                                    }
                                    selectedIndices = emptySet()
                                    android.widget.Toast.makeText(context, "已下载 ${sel.size} 张照片", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("下载 ${selectedIndices.size} 张") }
                        }
                    }
                    LazyVerticalGrid(columns = GridCells.Fixed(4), contentPadding = PaddingValues(4.dp),
                        state = gridState,
                        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(photos.size) { idx ->
                            val p = photos[idx]
                            val sel = idx in selectedIndices
                            Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { selectedIndices = if (sel) selectedIndices - idx else selectedIndices + idx }) {
                                val thumb = p["thumbUrl"] as? String ?: ""
                                if (thumb.isNotEmpty()) CoilImage(model = "http://$deviceIp:$devicePort$thumb", contentDescription = null,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                                    alpha = if (sel) 0.5f else 1f)
                                if (sel) Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                        if (hasMorePhotos) item {
                            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { Text("加载中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            } else {
                if (isLoading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("加载中…") }
                else if (error != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(error!!, color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(8.dp)); Button(onClick = { loadFiles(currentPath) }) { Text("重试") } } }
                else LazyColumn {
                    if (parentPath.isNotEmpty()) item { ListItem(headlineContent = { Text("…") }, leadingContent = { Icon(Icons.Filled.FolderOpen, null, tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { loadFiles(parentPath) }) }
                    items(files, key = { it.path }) { f -> FileRow(f, cl, deviceIp, devicePort, deviceName, viewModel, context, scope, { loadFiles(f.path) }) }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
fun FileRow(f: FileEntry, cl: OkHttpClient, deviceIp: String, devicePort: Int, deviceName: String, viewModel: MainViewModel, context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, onDirClick: () -> Unit) {
    fun fmt(s: Long): String = if (s < 1024) "${s} B" else if (s < 1048576) "${s / 1024} KB" else "${s / 1048576} MB"
    ListItem(
        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(if (f.isDirectory) "文件夹" else fmt(f.size), style = MaterialTheme.typography.labelSmall) },
        leadingContent = { Icon(if (f.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile, null, tint = if (f.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)) },
        trailingContent = { if (!f.isDirectory) TextButton(onClick = { scope.launch { try { val saveDir = com.fangshare.app.util.FileUtils.getReceiveDirectory(context); val out = java.io.File(saveDir, f.name); withContext(Dispatchers.IO) { val r = cl.newCall(Request.Builder().url("http://$deviceIp:$devicePort/files/download?path=${Uri.encode(f.path)}").get().build()).execute(); if (r.isSuccessful) r.body?.byteStream()?.use { i -> out.outputStream().use { o -> i.copyTo(o) } } else throw Exception("${r.code}") }; viewModel.addTransferToHistory(com.fangshare.app.model.TransferTask(id = java.util.UUID.randomUUID().toString(), fileName = f.name, filePath = out.absolutePath, fileSize = out.length(), mimeType = com.fangshare.app.util.FileUtils.getMimeType(f.name), status = com.fangshare.app.model.TransferStatus.COMPLETED, direction = com.fangshare.app.model.TransferDirection.RECEIVING, sourceDevice = com.fangshare.app.model.Device(id = deviceIp, name = deviceName, ipAddress = deviceIp, port = devicePort))); android.widget.Toast.makeText(context, "已拉取: ${f.name}", android.widget.Toast.LENGTH_SHORT).show() } catch (e: Exception) { android.widget.Toast.makeText(context, "失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show() } } }) { Icon(Icons.Filled.Download, "拉取", tint = Success, modifier = Modifier.size(18.dp)) } },
        modifier = if (f.isDirectory) Modifier.clickable { onDirClick() } else Modifier
    )
}
