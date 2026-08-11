package com.fangshare.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fangshare.app.model.Device
import com.fangshare.app.viewmodel.MainViewModel
import com.fangshare.app.ui.theme.Primary
import com.fangshare.app.ui.theme.Success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    viewModel: MainViewModel,
    deviceIp: String,
    devicePort: Int,
    deviceName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentPath by remember { mutableStateOf("/storage/emulated/0") }
    var files by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var parentPath by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun loadFiles(path: String) {
        scope.launch {
            isLoading = true
            error = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val url = "http://$deviceIp:$devicePort/files/list?path=${Uri.encode(path)}"
                    val request = Request.Builder().url(url).get().build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) throw Exception("${response.code}")
                    response.body?.string() ?: throw Exception("empty")
                }
                @Suppress("UNCHECKED_CAST")
                val json = try {
                    com.google.gson.Gson().fromJson(result, Map::class.java) as Map<String, Any>
                } catch (e: Exception) { throw Exception("解析失败: ${e.message}") }
                currentPath = json["currentPath"] as? String ?: path
                parentPath = json["parentPath"] as? String ?: ""
                val rawFiles = json["files"] as? List<*> ?: emptyList<Any>()
                files = rawFiles.mapNotNull { f ->
                    @Suppress("UNCHECKED_CAST")
                    val fileMap = f as? Map<String, Any> ?: return@mapNotNull null
                    FileEntry(
                        name = fileMap["name"] as? String ?: "",
                        path = fileMap["path"] as? String ?: "",
                        isDirectory = fileMap["isDirectory"] as? Boolean ?: false,
                        size = (fileMap["size"] as? Number)?.toLong() ?: 0L,
                        lastModified = (fileMap["lastModified"] as? Number)?.toLong() ?: 0L
                    )
                }
            } catch (e: Exception) {
                error = e.message ?: "连接失败"
            }
            isLoading = false
        }
    }

    val formatSize: (Long) -> String = { size ->
        if (size < 1024) "${size} B"
        else if (size < 1024 * 1024) "${size / 1024} KB"
        else {
            val mb = size / (1024.0 * 1024.0)
            "${(mb * 10).toLong() / 10.0} MB"
        }
    }

    LaunchedEffect(Unit) { loadFiles(currentPath) }

    // 主 UI
    var hasCrash by remember { mutableStateOf(false) }
    if (hasCrash) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("加载失败", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { hasCrash = false }) { Text("重试") }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        ) {
        TopAppBar(
            title = { Text(deviceName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        // 路径导航
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Folder, null, tint = Primary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    currentPath,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { loadFiles(currentPath) }) { Text("重试") }
                }
                files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("空目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn {
                    // 返回上级
                    if (parentPath.isNotEmpty()) {
                        item {
                            ListItem(
                                headlineContent = { Text("...", fontWeight = FontWeight.Medium) },
                                leadingContent = { Icon(Icons.Filled.FolderOpen, null, tint = Primary) },
                                modifier = Modifier.clickable { loadFiles(parentPath) }
                            )
                        }
                    }
                    items(files, key = { it.path }) { file ->
                        ListItem(
                            headlineContent = {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(
                                    if (file.isDirectory) "文件夹" else formatSize(file.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            leadingContent = {
                                val icon = if (file.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile
                                val tint = if (file.isDirectory) Primary else MaterialTheme.colorScheme.onSurfaceVariant
                                Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
                            },
                            trailingContent = {
                                if (!file.isDirectory) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            try {
                                                val url = "http://$deviceIp:$devicePort/files/download?path=${Uri.encode(file.path)}"
                                                val saveDir = com.fangshare.app.util.FileUtils.getReceiveDirectory(context)
                                                val outputFile = java.io.File(saveDir, file.name)
                                                withContext(Dispatchers.IO) {
                                                    val request = Request.Builder().url(url).get().build()
                                                    val response = client.newCall(request).execute()
                                                    if (response.isSuccessful) {
                                                        response.body?.byteStream()?.use { input ->
                                                            outputFile.outputStream().use { output ->
                                                                input.copyTo(output)
                                                            }
                                                        }
                                                    }
                                                }
                                                // 添加到历史
                                                viewModel.addTransferToHistory(
                                                    com.fangshare.app.model.TransferTask(
                                                        id = java.util.UUID.randomUUID().toString(),
                                                        fileName = file.name,
                                                        filePath = outputFile.absolutePath,
                                                        fileSize = outputFile.length(),
                                                        mimeType = com.fangshare.app.util.FileUtils.getMimeType(file.name),
                                                        status = com.fangshare.app.model.TransferStatus.COMPLETED,
                                                        direction = com.fangshare.app.model.TransferDirection.RECEIVING,
                                                        sourceDevice = Device(
                                                            id = deviceIp,
                                                            name = deviceName,
                                                            ipAddress = deviceIp,
                                                            port = devicePort
                                                        )
                                                    )
                                                )
                                                android.widget.Toast.makeText(context, "已拉取: ${file.name}", android.widget.Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                android.widget.Toast.makeText(context, "拉取失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Filled.Download, "拉取", tint = Success, modifier = Modifier.size(18.dp))
                                    }
                                }
                            },
                            modifier = if (file.isDirectory) Modifier.clickable { loadFiles(file.path) } else Modifier
                        )
                    }
                }
            }
        }
        } // end else (no crash)
    }
}
