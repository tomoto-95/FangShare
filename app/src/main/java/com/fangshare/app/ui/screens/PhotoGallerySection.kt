package com.fangshare.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.fangshare.app.model.Device
import com.fangshare.app.model.DeviceGroup
import com.fangshare.app.util.PhotoItem
import com.fangshare.app.util.PhotoType

@Composable
fun PhotoGallerySection(
    photos: List<PhotoItem>,
    selectedUris: Set<Uri>,
    isLoading: Boolean,
    onToggle: (Uri) -> Unit,
    onSelectAll: (List<PhotoItem>) -> Unit,
    onClearSelection: () -> Unit,
    onRefresh: () -> Unit,
    onSendToDevice: (Device) -> Unit,
    onSendToGroup: (DeviceGroup) -> Unit,
    devices: List<Device>,
    groups: List<DeviceGroup>
) {
    var photoFilter by remember { mutableIntStateOf(0) } // 0=全部, 1=相机, 2=截图
    var showSendPicker by remember { mutableStateOf(false) }

    val filterOptions = listOf("全部", "相机拍摄", "截图")

    val cameraPhotos = photos.filter { it.type == PhotoType.CAMERA }
    val screenshotPhotos = photos.filter { it.type == PhotoType.SCREENSHOT }
    val displayPhotos = when (photoFilter) {
        1 -> cameraPhotos
        2 -> screenshotPhotos
        else -> photos
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("照片", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (photos.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                        Text(
                            "${photos.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Row {
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    Icon(Icons.Filled.Refresh, "刷新", modifier = Modifier.size(20.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                Text("加载中…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (photos.isEmpty()) {
            Button(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Icon(Icons.Filled.PhotoLibrary, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("扫描照片")
            }
        } else {
            // 分类标签
            ScrollableTabRow(
                selectedTabIndex = photoFilter,
                containerColor = MaterialTheme.colorScheme.background,
                edgePadding = 0.dp,
                divider = {}
            ) {
                filterOptions.forEachIndexed { index, label ->
                    val count = when (index) {
                        1 -> cameraPhotos.size
                        2 -> screenshotPhotos.size
                        else -> photos.size
                    }
                    Tab(
                        selected = photoFilter == index,
                        onClick = { photoFilter = index },
                        text = {
                            Text("$label ($count)", style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (photoFilter == index) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    )
                }
            }

            // 操作栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val selectedCount = selectedUris.size
                if (selectedCount > 0) {
                    TextButton(onClick = onClearSelection) {
                        Text("取消 ($selectedCount)", style = MaterialTheme.typography.labelMedium)
                    }
                    if (displayPhotos.isNotEmpty()) {
                        TextButton(onClick = {
                            showSendPicker = true
                        }) {
                            Icon(Icons.Filled.Send, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("发送选中 ($selectedCount)", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                } else {
                    if (displayPhotos.isNotEmpty()) {
                        TextButton(onClick = { onSelectAll(displayPhotos) }) {
                            Icon(Icons.Filled.SelectAll, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("全选", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // 照片网格
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.height(260.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(displayPhotos, key = { it.uri.toString() }) { photo ->
                    val isSelected = photo.uri in selectedUris
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .border(if (isSelected) 2.dp else 0.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(6.dp))
                            .clickable { onToggle(photo.uri) }
                    ) {
                        AsyncImage(
                            model = photo.uri,
                            contentDescription = photo.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        // 选中标记
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // 发送选择器
    if (showSendPicker) {
        AlertDialog(
            onDismissRequest = { showSendPicker = false },
            title = { Text("发送照片") },
            text = {
                Column {
                    if (devices.isEmpty()) {
                        Text("没有可用的设备")
                    } else {
                        val nonEmptyGroups = groups.filter { it.deviceIds.isNotEmpty() && devices.any { d -> d.id in it.deviceIds } }
                        if (nonEmptyGroups.isNotEmpty()) {
                            Text("分组发送", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            nonEmptyGroups.forEach { group ->
                                val count = devices.count { it.id in group.deviceIds }
                                Button(
                                    onClick = { onSendToGroup(group); showSendPicker = false },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("发送给 ${group.name} ($count 台)")
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text("单台设备", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                            items(devices.size) { index ->
                                val device = devices[index]
                                ListItem(
                                    headlineContent = { Text(device.name) },
                                    supportingContent = { Text(device.ipAddress) },
                                    leadingContent = { Icon(Icons.Filled.PhoneAndroid, null) },
                                    modifier = Modifier.clickable {
                                        onSendToDevice(device)
                                        showSendPicker = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showSendPicker = false }) { Text("取消") } }
        )
    }
}
