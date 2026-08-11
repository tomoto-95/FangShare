package com.fangshare.app.service

import android.content.Context
import android.net.Uri
import com.fangshare.app.model.Device
import com.fangshare.app.model.TransferDirection
import com.fangshare.app.model.TransferStatus
import com.fangshare.app.model.TransferTask
import com.fangshare.app.util.FileUtils
import com.fangshare.app.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 文件传输客户端 — 向目标设备发送文件
 */
class TransferClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    private val _activeTasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val activeTasks: StateFlow<List<TransferTask>> = _activeTasks.asStateFlow()

    private val taskProgressMap = mutableMapOf<String, Float>()

    /**
     * 检查目标设备是否在线
     */
    suspend fun pingDevice(device: Device): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${device.displayUrl}/ping")
                .get()
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 向主机注册加入家庭组
     */
    suspend fun joinHostGroup(hostIp: String, hostPort: Int, deviceId: String, deviceName: String, devicePort: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = okhttp3.FormBody.Builder()
                .add("deviceId", deviceId)
                .add("deviceName", deviceName)
                .add("ip", NetworkUtils.getLocalIpAddress() ?: "")
                .add("port", devicePort.toString())
                .build()
            val request = Request.Builder()
                .url("http://$hostIp:$hostPort/group/join")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            android.util.Log.w("TransferClient", "joinHostGroup failed: ${e.message}")
            false
        }
    }

    /**
     * 发送单个文件到目标设备
     */
    suspend fun sendFile(
        device: Device,
        fileUri: Uri,
        senderName: String,
        groupPin: String? = null,
        onProgress: ((Float, Long) -> Unit)? = null
    ): TransferTask = withContext(Dispatchers.IO) {
        val fileName = FileUtils.getFileName(context, fileUri)
        val fileSize = FileUtils.getFileSize(context, fileUri)
        val mimeType = FileUtils.getMimeType(fileName)

        // 创建任务
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            fileName = fileName,
            filePath = fileUri.toString(),
            fileSize = fileSize,
            mimeType = mimeType,
            targetDevice = device,
            status = TransferStatus.CONNECTING,
            direction = TransferDirection.SENDING
        )

        addTask(task)

        try {
            // 将 Uri 内容复制到临时文件
            val tmpFile = File(context.cacheDir, "upload_${UUID.randomUUID()}_$fileName")
            val inputStream = context.contentResolver.openInputStream(fileUri)
            if (inputStream == null) {
                throw java.io.IOException("openInputStream returned null (uri=$fileUri)")
            }
            inputStream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                }
            }
            android.util.Log.i("TransferClient", "sendFile: copied $fileName (${tmpFile.length()} bytes) to tmp")

            // 更新为传输中
            updateTask(task.id, TransferStatus.TRANSFERRING)

            // 构建 multipart 请求
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", fileName,
                    tmpFile.asRequestBody(mimeType.toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("${device.displayUrl}/receive")
                .header("x-file-name", fileName)
                .header("x-file-size", fileSize.toString())
                .header("x-sender-name", senderName)
                .apply {
                    if (!groupPin.isNullOrEmpty()) {
                        header("x-group-pin", groupPin)
                    }
                }
                .post(requestBody)
                .build()

            android.util.Log.i("TransferClient", "sendFile: POST ${device.displayUrl}/receive fileName=$fileName size=$fileSize")
            val response = client.newCall(request).execute()
            val respCode = response.code
            val respBody = response.body?.string() ?: ""
            android.util.Log.i("TransferClient", "sendFile: response $respCode body=$respBody")

            // 清理临时文件
            tmpFile.delete()

            if (response.isSuccessful) {
                updateTask(task.id, TransferStatus.COMPLETED, 1f, fileSize)
            } else {
                updateTask(
                    task.id, TransferStatus.FAILED,
                    errorMessage = "HTTP $respCode: $respBody"
                )
            }

        } catch (e: Exception) {
            android.util.Log.e("TransferClient", "sendFile failed for $fileName", e)
            updateTask(
                task.id, TransferStatus.FAILED,
                errorMessage = e.javaClass.simpleName + ": " + (e.message ?: "未知错误")
            )
        }

        return@withContext _activeTasks.value.find { it.id == task.id } ?: task
    }

    /**
     * 批量发送文件
     */
    suspend fun sendFiles(
        device: Device,
        fileUris: List<Uri>,
        senderName: String,
        onFileProgress: ((Int, Int) -> Unit)? = null
    ): List<TransferTask> {
        val results = mutableListOf<TransferTask>()
        fileUris.forEachIndexed { index, uri ->
            val task = sendFile(device, uri, senderName)
            results.add(task)
            onFileProgress?.invoke(index + 1, fileUris.size)
        }
        return results
    }

    /**
     * 发送文件到家庭组所有设备
     */
    suspend fun sendToGroup(
        devices: List<Device>,
        fileUris: List<Uri>,
        senderName: String
    ): Map<String, List<TransferTask>> {
        val results = mutableMapOf<String, List<TransferTask>>()
        devices.forEach { device ->
            results[device.id] = sendFiles(device, fileUris, senderName)
        }
        return results
    }

    private fun addTask(task: TransferTask) {
        _activeTasks.value = _activeTasks.value + task
    }

    private fun updateTask(
        taskId: String,
        status: TransferStatus,
        progress: Float? = null,
        transferredBytes: Long? = null,
        errorMessage: String? = null
    ) {
        _activeTasks.value = _activeTasks.value.map { task ->
            if (task.id == taskId) {
                task.copy(
                    status = status,
                    progress = progress ?: task.progress,
                    transferredBytes = transferredBytes ?: task.transferredBytes,
                    errorMessage = errorMessage
                )
            } else task
        }
    }

    /**
     * 清除已完成/失败/取消的任务
     */
    fun clearInactiveTasks() {
        _activeTasks.value = _activeTasks.value.filter { it.isActive }
    }

    /**
     * 取消任务
     */
    fun cancelTask(taskId: String) {
        updateTask(taskId, TransferStatus.CANCELLED)
    }
}
