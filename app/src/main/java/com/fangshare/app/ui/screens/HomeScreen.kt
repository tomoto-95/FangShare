package com.fangshare.app.ui.screens

import android.net.Uri
import android.provider.DocumentsContract
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.fangshare.app.model.*
import com.fangshare.app.ui.theme.*
import com.fangshare.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MainViewModel) {
    val transferHistory by viewModel.transferHistory.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()
    val devices by viewModel.allDevices.collectAsState()
    val localDevice by viewModel.localDevice.collectAsState()
    val lastReceivedFile by viewModel.pendingReceivedFiles.collectAsState()
    val saveDirectory by viewModel.saveDirectory.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val photos by viewModel.photos.collectAsState()
    val selectedPhotoUris by viewModel.selectedPhotoUris.collectAsState()
    val photoLoading by viewModel.photoLoading.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showDevicePicker by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showSettings by remember { mutableStateOf(false) }
    var showReceivedDialog by remember { mutableStateOf(false) }

    // 历史筛选: 0=全部, 1=发送, 2=接收
    var historyFilter by remember { mutableIntStateOf(0) }
    val historyFilterOptions = listOf("全部", "已发送", "已接收")

    // 图片预览
    var previewTask by remember { mutableStateOf<TransferTask?>(null) }

    // 接收文件弹窗
    LaunchedEffect(lastReceivedFile) {
        if (lastReceivedFile.isNotEmpty()) {
            showReceivedDialog = true
        }
    }

    // SAF 目录选择器
    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 持久化权限
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            // 获取实际路径
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
            val path = getPathFromUri(context, childUri) ?: uri.toString()
            viewModel.setCustomSaveDirectory(path)
        }
    }

    // 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedFiles = uris
            showDevicePicker = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部状态栏
        TopAppBar(
            title = {
                Column {
                    Text(
                        "Fangshare",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (networkStatus.isConnected && devices.isNotEmpty()) {
                        Text(
                            "${networkStatus.ssid ?: "WiFi"} · ${devices.size} 台设备在线",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            actions = {
                // 网络状态指示器
                val statusColor = if (networkStatus.isConnected) Success else Error
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // 设置按钮
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 快捷操作
            item {
                Text(
                    "快捷操作",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuickActionCard(
                        title = "发送文件",
                        subtitle = "选择文件发送",
                        icon = Icons.Filled.Send,
                        color = Primary,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            filePickerLauncher.launch(arrayOf("*/*"))
                        }
                    )
                    QuickActionCard(
                        title = "接收文件",
                        subtitle = "等待传输",
                        icon = Icons.Filled.Download,
                        color = Secondary,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            // 显示本机信息供其他设备连接
                        }
                    )
                }
            }

            // 本机信息卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "本机设备",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                localDevice?.name ?: "未就绪",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (networkStatus.ipAddress != null) {
                                Text(
                                    "IP: ${networkStatus.ipAddress}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            Icons.Filled.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }

            // 附近设备
            if (devices.isNotEmpty()) {
                item {
                    Text(
                        "附近设备",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(devices, key = { it.id }) { device ->
                            DeviceChip(
                                device = device,
                                onClick = {
                                    filePickerLauncher.launch(arrayOf("*/*"))
                                }
                            )
                        }
                    }
                }
            }

            // --- 照片分区 ---
            item {
                // 首次加载时自动扫描
                if (photos.isEmpty() && !photoLoading) {
                    LaunchedEffect(Unit) { viewModel.scanPhotos() }
                }
                PhotoGallerySection(
                    photos = photos,
                    selectedUris = selectedPhotoUris,
                    isLoading = photoLoading,
                    onToggle = { viewModel.togglePhotoSelection(it) },
                    onSelectAll = { filtered -> viewModel.selectAllPhotos(filtered) },
                    onClearSelection = { viewModel.clearPhotoSelection() },
                    onRefresh = { viewModel.scanPhotos() },
                    onSendToDevice = { viewModel.sendSelectedPhotos(it) },
                    onSendToGroup = { viewModel.sendSelectedPhotosToGroup(it) },
                    devices = devices,
                    groups = groups
                )
            }

            // 传输历史
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "传输历史",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (transferHistory.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearTransferHistory() }) {
                            Text("清除", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                // 筛选标签
                ScrollableTabRow(
                    selectedTabIndex = historyFilter,
                    containerColor = MaterialTheme.colorScheme.background,
                    edgePadding = 0.dp,
                    divider = {}
                ) {
                    historyFilterOptions.forEachIndexed { index, label ->
                        Tab(
                            selected = historyFilter == index,
                            onClick = { historyFilter = index },
                            text = {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (historyFilter == index) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }
            }

            val filteredHistory = when (historyFilter) {
                1 -> transferHistory.filter { it.direction == TransferDirection.SENDING }
                2 -> transferHistory.filter { it.direction == TransferDirection.RECEIVING }
                else -> transferHistory
            }

            if (filteredHistory.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.SwapHoriz,
                        title = "暂无记录",
                        subtitle = if (historyFilter == 0) "发送或接收文件后，记录将显示在这里"
                                   else if (historyFilter == 1) "暂无发送记录" else "暂无接收记录"
                    )
                }
            } else {
                items(filteredHistory, key = { it.id }) { task ->
                    TransferHistoryItem(
                        task = task,
                        onClick = {
                            if (task.status == TransferStatus.COMPLETED) {
                                openFile(context, task, onOpenImage = { previewTask = task })
                            }
                        },
                        onLongClick = {
                            // 长按：跳转到文件所在目录
                            try {
                                val file = java.io.File(task.filePath)
                                val dir = file.parentFile ?: file
                                // 使用 MediaStore 查询文件，然后用 SAF 打开父目录
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                                val uri = android.provider.DocumentsContract.buildDocumentUri(
                                    "com.android.externalstorage.documents",
                                    "primary:${dir.absolutePath.removePrefix(android.os.Environment.getExternalStorageDirectory().absolutePath + "/")}"
                                )
                                intent.setDataAndType(uri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                                intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // 备用：用媒体扫描打开
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                                    intent.setDataAndType(
                                        android.net.Uri.parse(task.filePath), 
                                        "resource/folder"
                                    )
                                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                } catch (e2: Exception) {
                                    android.widget.Toast.makeText(context, "无法打开目录", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    // 设备选择对话框
    if (showDevicePicker && selectedFiles.isNotEmpty()) {
        NewDevicePicker(
            devices = devices,
            groups = groups,
            onDeviceSelected = { device ->
                showDevicePicker = false
                scope.launch {
                    selectedFiles.forEach { uri ->
                        val task = viewModel.transferClient.sendFile(device = device, fileUri = uri, senderName = viewModel.localDeviceName.value)
                        viewModel.addTransferToHistory(task)
                    }
                    selectedFiles = emptyList()
                }
            },
            onGroupSelected = { group ->
                showDevicePicker = false
                scope.launch {
                    val groupDevices = devices.filter { it.id in group.deviceIds }
                    selectedFiles.forEach { uri ->
                        groupDevices.forEach { device ->
                            try {
                                val task = viewModel.transferClient.sendFile(device = device, fileUri = uri, senderName = viewModel.localDeviceName.value)
                                viewModel.addTransferToHistory(task)
                            } catch (_: Exception) {}
                        }
                    }
                    selectedFiles = emptyList()
                }
            },
            onDismiss = {
                showDevicePicker = false
                selectedFiles = emptyList()
            }
        )
    }

    // 接收文件弹窗
    if (showReceivedDialog && lastReceivedFile.isNotEmpty()) {
        BatchReceivedDialog(
            files = lastReceivedFile,
            saveDir = saveDirectory,
            onAcceptAll = {
                showReceivedDialog = false
                viewModel.dismissReceiveNotification()
            },
            onDismiss = {
                showReceivedDialog = false
                viewModel.dismissReceiveNotification()
            }
        )
    }

    // 设置对话框
    if (showSettings) {
        SettingsDialog(
            saveDirectory = saveDirectory,
            onPickDirectory = { dirPickerLauncher.launch(null) },
            onResetDirectory = { viewModel.resetSaveDirectory() },
            onDismiss = { showSettings = false }
        )
    }

    // 图片全屏预览
    if (previewTask != null) {
        ImageViewer(
            task = previewTask!!,
            onDismiss = { previewTask = null }
        )
    }
}

@Composable
fun QuickActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.08f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DeviceChip(device: Device, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Filled.PhoneAndroid,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Text(
                device.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (device.isInGroup) {
                Text(
                    "家庭组",
                    style = MaterialTheme.typography.labelSmall,
                    color = Success
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun TransferHistoryItem(task: TransferTask, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val statusIcon = when (task.status) {
        TransferStatus.COMPLETED -> if (task.direction == TransferDirection.RECEIVING) Icons.Filled.Download else Icons.Filled.Upload
        TransferStatus.FAILED -> Icons.Filled.Error
        TransferStatus.CANCELLED -> Icons.Filled.Cancel
        else -> Icons.Filled.Schedule
    }
    val statusColor = when (task.status) {
        TransferStatus.COMPLETED -> if (task.direction == TransferDirection.RECEIVING) Success else Primary
        TransferStatus.FAILED -> Error
        TransferStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Warning
    }
    val directionLabel = if (task.direction == TransferDirection.SENDING) "发送至" else "来自"
    val peerName = if (task.direction == TransferDirection.SENDING) {
        task.targetDevice?.name ?: ""
    } else {
        task.sourceDevice?.name ?: ""
    }
    val isImage = task.mimeType.startsWith("image/")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = task.status == TransferStatus.COMPLETED,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 缩略图区域
            if (isImage) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = File(task.filePath),
                        contentDescription = task.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Icon(
                    getFileTypeIcon(task.fileName),
                    contentDescription = null,
                    tint = getFileTypeColor(task.fileName),
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    task.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        task.formattedSize,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        " · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (task.direction == TransferDirection.SENDING) Primary.copy(alpha = 0.1f) else Success.copy(alpha = 0.1f)
                    ) {
                        Text(
                            directionLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (task.direction == TransferDirection.SENDING) Primary else Success,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                    if (peerName.isNotEmpty()) {
                        Text(
                            " $peerName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
        }
        // 失败时显示具体错误信息
        if (task.status == TransferStatus.FAILED && !task.errorMessage.isNullOrBlank()) {
            Text(
                text = task.errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = Error,
                modifier = Modifier.padding(start = 56.dp, end = 12.dp, bottom = 8.dp, top = 2.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(56.dp)
        )
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerDialog(
    devices: List<Device>,
    onDeviceSelected: (Device) -> Unit,
    onDismiss: () -> Unit
) = NewDevicePicker(devices, emptyList(), onDeviceSelected, null, onDismiss)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewDevicePicker(
    devices: List<Device>,
    groups: List<com.fangshare.app.model.DeviceGroup>,
    onDeviceSelected: (Device) -> Unit,
    onGroupSelected: ((com.fangshare.app.model.DeviceGroup) -> Unit)?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择接收设备") },
        text = {
            Column {
                // 分组发送
                val nonEmptyGroups = groups.filter { it.deviceIds.isNotEmpty() && devices.any { d -> d.id in it.deviceIds } }
                if (nonEmptyGroups.isNotEmpty()) {
                    Text("分组", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
                    nonEmptyGroups.forEach { group ->
                        val count = devices.count { it.id in group.deviceIds }
                        Button(
                            onClick = { onGroupSelected?.invoke(group) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Forward, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("发送给 ${group.name} ($count 台)")
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                // 单台设备
                Text("单台设备", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                if (devices.isEmpty()) {
                    Text("没有可用设备", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn {
                        items(devices) { device ->
                            ListItem(
                                headlineContent = { Text(device.name) },
                                supportingContent = { Text(device.ipAddress) },
                                leadingContent = { Icon(Icons.Filled.PhoneAndroid, null) },
                                modifier = Modifier.clickable { onDeviceSelected(device) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

fun getFileTypeIcon(fileName: String): ImageVector {
    return when (getFileType(com.fangshare.app.util.FileUtils.getFileExtension(fileName))) {
        FileType.IMAGE -> Icons.Filled.Image
        FileType.VIDEO -> Icons.Filled.Videocam
        FileType.AUDIO -> Icons.Filled.MusicNote
        FileType.DOCUMENT -> Icons.Filled.Description
        FileType.ARCHIVE -> Icons.Filled.FolderZip
        FileType.APK -> Icons.Filled.Android
        else -> Icons.Filled.InsertDriveFile
    }
}

fun getFileTypeColor(fileName: String): Color {
    return when (getFileType(com.fangshare.app.util.FileUtils.getFileExtension(fileName))) {
        FileType.IMAGE -> ImageColor
        FileType.VIDEO -> VideoColor
        FileType.AUDIO -> AudioColor
        FileType.DOCUMENT -> DocumentColor
        FileType.ARCHIVE -> ArchiveColor
        FileType.APK -> ApkColor
        else -> OtherFileColor
    }
}

// ===== 接收文件弹窗 =====

@Composable
fun BatchReceivedDialog(
    files: List<TransferTask>,
    saveDir: String,
    onAcceptAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val totalSize = files.sumOf { it.fileSize }
    val formattedTotal = if (totalSize < 1024) "${totalSize} B"
        else if (totalSize < 1024 * 1024) "${totalSize / 1024} KB"
        else String.format("%.1f MB", totalSize / (1024.0 * 1024.0))
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.DownloadDone, null, tint = Success, modifier = Modifier.size(40.dp)) },
        title = { Text("收到 ${files.size} 个文件", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("来自: ${files.firstOrNull()?.sourceDevice?.name ?: "未知"} · $formattedTotal",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                files.take(5).forEach { file ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Icon(getFileTypeIcon(file.fileName), null, tint = getFileTypeColor(file.fileName), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(file.fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(file.formattedSize, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (files.size > 5) Text("...还有 ${files.size - 5} 个文件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Text("已保存到 $saveDir", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = onAcceptAll) { Text("全部接受 (${files.size})", fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

// ===== 设置对话框 =====

@Composable
fun SettingsDialog(
    saveDirectory: String,
    onPickDirectory: () -> Unit,
    onResetDirectory: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("设置", style = MaterialTheme.typography.headlineSmall)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 保存目录
                Text(
                    "文件保存位置",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Folder, null, tint = Primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            saveDirectory,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onPickDirectory,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("选择目录")
                    }
                    TextButton(onClick = onResetDirectory) { Text("恢复默认") }
                }
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    "文件保存位置仅对新接收的文件生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}


// ===== 辅助函数 =====

fun getPathFromUri(context: android.content.Context, uri: Uri): String? {
    return try {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        // SAF document ID format: "primary:Download/Fangshare" or "primary:Download"
        val parts = docId.split(":")
        if (parts.size >= 2 && parts[0].equals("primary", ignoreCase = true)) {
            val path = parts[1].replace("/", java.io.File.separator)
            java.io.File(android.os.Environment.getExternalStorageDirectory(), path).absolutePath
        } else null
    } catch (e: Exception) {
        null
    }
}

// ===== 文件打开辅助函数 =====

fun openFile(context: android.content.Context, task: TransferTask, onOpenImage: () -> Unit) {
    if (task.mimeType.startsWith("image/")) {
        // 图片：应用内全屏预览
        onOpenImage()
        return
    }
    // 其他文件：调系统应用打开
    try {
        val file = File(task.filePath)
        val uri = if (file.exists()) {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } else {
            Uri.parse(task.filePath)
        }
        val mimeType = task.mimeType.ifBlank {
            val ext = MimeTypeMap.getFileExtensionFromUrl(task.fileName)
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "无法打开文件: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

// ===== 图片全屏预览 =====

@Composable
fun ImageViewer(task: TransferTask, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss)
        ) {
            val file = File(task.filePath)
            AsyncImage(
                model = if (file.exists()) file else task.filePath,
                contentDescription = task.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(12.dp)
                    .align(Alignment.TopStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                ) {
                    Icon(Icons.Filled.Close, "关闭", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    task.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 底部信息
            Text(
                "${task.formattedSize} · ${if (task.direction == TransferDirection.RECEIVING) "接收" else "发送"}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 16.dp)
            )
        }
    }
}
