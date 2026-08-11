package com.lanshare.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanshare.app.model.Device
import com.lanshare.app.model.TransferDirection
import com.lanshare.app.model.TransferStatus
import com.lanshare.app.model.formatFileSize
import com.lanshare.app.ui.theme.*
import com.lanshare.app.util.FileUtils
import com.lanshare.app.util.MediaFile
import com.lanshare.app.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices by viewModel.allDevices.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var images by remember { mutableStateOf<List<MediaFile>>(emptyList()) }
    var documents by remember { mutableStateOf<List<MediaFile>>(emptyList()) }
    var selectedFiles by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var showDevicePicker by remember { mutableStateOf(false) }

    // 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedFiles = uris.toSet()
            if (devices.isNotEmpty()) {
                showDevicePicker = true
            }
        }
    }

    // 加载文件
    LaunchedEffect(Unit) {
        images = FileUtils.queryRecentImages(context, 200)
        documents = FileUtils.queryRecentFiles(context, 200)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text("文件", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            },
            actions = {
                if (selectedFiles.isNotEmpty()) {
                    Text(
                        "已选 ${selectedFiles.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = {
                            if (devices.isNotEmpty()) {
                                showDevicePicker = true
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("发送")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )

        // 标签栏
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = {}
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("照片")
                    }
                }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("文档")
                    }
                }
            )
        }

        // 快捷操作
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = true,
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                label = { Text("浏览文件") },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }

        // 文件列表
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val files = if (selectedTab == 0) images else documents

            if (files.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Folder,
                        title = "暂无文件",
                        subtitle = "点击上方「浏览文件」选择要发送的文件"
                    )
                }
            }

            items(files, key = { it.uri.toString() }) { file ->
                val isSelected = file.uri in selectedFiles
                FileListItem(
                    file = file,
                    isSelected = isSelected,
                    onToggle = {
                        selectedFiles = if (isSelected) {
                            selectedFiles - file.uri
                        } else {
                            selectedFiles + file.uri
                        }
                    }
                )
            }
        }
    }

    // 设备选择对话框
    if (showDevicePicker && selectedFiles.isNotEmpty()) {
        DevicePickerDialog(
            devices = devices,
            onDeviceSelected = { device ->
                showDevicePicker = false
                scope.launch {
                    selectedFiles.forEach { uri ->
                        val task = viewModel.transferClient.sendFile(
                            device = device,
                            fileUri = uri,
                            senderName = viewModel.localDeviceName.value
                        )
                        viewModel.addTransferToHistory(task)
                    }
                    selectedFiles = emptySet()
                }
            },
            onDismiss = {
                showDevicePicker = false
                selectedFiles = emptySet()
            }
        )
    }
}

@Composable
fun FileListItem(
    file: MediaFile,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 文件类型图标
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(getFileTypeColor(file.name).copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    getFileTypeIcon(file.name),
                    contentDescription = null,
                    tint = getFileTypeColor(file.name),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatFileSize(file.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 选择框
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
