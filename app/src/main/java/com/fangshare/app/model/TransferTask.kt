package com.fangshare.app.model

import java.util.UUID

/**
 * 传输任务状态
 */
enum class TransferStatus {
    PENDING,
    CONNECTING,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * 传输方向
 */
enum class TransferDirection {
    SENDING,
    RECEIVING
}

/**
 * 单个文件传输任务
 */
data class TransferTask(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val mimeType: String,
    val targetDevice: Device? = null,
    val sourceDevice: Device? = null,
    val status: TransferStatus = TransferStatus.PENDING,
    val progress: Float = 0f,         // 0.0 ~ 1.0
    val transferredBytes: Long = 0,
    val speedBytesPerSec: Long = 0,    // 传输速率
    val direction: TransferDirection = TransferDirection.SENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
) {
    val progressPercent: Int get() = (progress * 100).toInt().coerceIn(0, 100)

    val formattedSize: String get() = formatFileSize(fileSize)

    val formattedSpeed: String get() = "${formatFileSize(speedBytesPerSec)}/s"

    val isActive: Boolean get() = status in listOf(
        TransferStatus.CONNECTING,
        TransferStatus.TRANSFERRING
    )
}

/**
 * 文件类型枚举
 */
enum class FileType(val extensions: List<String>, val mimePrefix: String) {
    IMAGE(listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic"), "image/"),
    VIDEO(listOf("mp4", "mkv", "avi", "mov", "flv", "wmv", "3gp"), "video/"),
    AUDIO(listOf("mp3", "wav", "aac", "flac", "ogg", "m4a"), "audio/"),
    DOCUMENT(listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv"), "application/"),
    ARCHIVE(listOf("zip", "rar", "7z", "tar", "gz"), "application/"),
    APK(listOf("apk"), "application/"),
    OTHER(emptyList(), "")
}

fun getFileType(extension: String): FileType {
    val ext = extension.lowercase()
    return FileType.entries.firstOrNull { ext in it.extensions } ?: FileType.OTHER
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val size = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "%.1f %s".format(size, units[digitGroups])
}
