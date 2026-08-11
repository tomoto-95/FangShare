package com.fangshare.app.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.fangshare.app.model.getFileType
import java.io.File

object FileUtils {

    /**
     * 从 Uri 获取文件名
     */
    fun getFileName(context: Context, uri: Uri): String {
        var name = "unknown"
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            name = File(uri.path ?: "").name
        } else {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        name = it.getString(index)
                    }
                }
            }
        }
        return name
    }

    /**
     * 从 Uri 获取文件大小
     */
    fun getFileSize(context: Context, uri: Uri): Long {
        var size = 0L
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            size = File(uri.path ?: "").length()
        } else {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0) {
                        size = it.getLong(index)
                    }
                }
            }
        }
        return size
    }

    /**
     * 获取文件扩展名
     */
    fun getFileExtension(fileName: String): String {
        return fileName.substringAfterLast('.', "")
    }

    /**
     * 从文件名获取 MIME 类型
     */
    fun getMimeType(fileName: String): String {
        val extension = getFileExtension(fileName)
        return if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        } else {
            "application/octet-stream"
        }
    }

    /**
     * 获取接收文件的保存目录（支持用户自定义路径）
     */
    fun getReceiveDirectory(context: Context): File {
        // 始终使用应用私有目录，避免 Android 10+ scoped storage EACCES
        val dir = File(context.getExternalFilesDir(null), "Fangshare")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 设置自定义保存目录
     */
    fun setCustomSaveDirectory(context: Context, path: String) {
        val prefs = context.getSharedPreferences("lan_share_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("save_directory", path).apply()
    }

    /**
     * 查询最近的照片
     */
    fun queryRecentImages(context: Context, limit: Int = 100): List<MediaFile> {
        val images = mutableListOf<MediaFile>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext() && images.size < limit) {
                val id = cursor.getLong(idCol)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                images.add(
                    MediaFile(
                        uri = uri,
                        name = cursor.getString(nameCol),
                        size = cursor.getLong(sizeCol),
                        dateAdded = cursor.getLong(dateCol),
                        fileType = com.fangshare.app.model.FileType.IMAGE
                    )
                )
            }
        }
        return images
    }

    /**
     * 查询最近的文件
     */
    fun queryRecentFiles(context: Context, limit: Int = 100): List<MediaFile> {
        val files = mutableListOf<MediaFile>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE}"
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection, selection, null, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)

            while (cursor.moveToNext() && files.size < limit) {
                val id = cursor.getLong(idCol)
                val uri = Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), id.toString())
                val name = cursor.getString(nameCol)
                files.add(
                    MediaFile(
                        uri = uri,
                        name = name,
                        size = cursor.getLong(sizeCol),
                        dateAdded = cursor.getLong(dateCol),
                        fileType = getFileType(getFileExtension(name))
                    )
                )
            }
        }
        return files
    }
}

/**
 * 媒体文件信息
 */
data class MediaFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateAdded: Long,
    val fileType: com.fangshare.app.model.FileType
)
