package com.lanshare.app.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/**
 * 照片类型
 */
enum class PhotoType { CAMERA, SCREENSHOT, OTHER }

/**
 * 照片实体
 */
data class PhotoItem(
    val uri: Uri,
    val name: String,
    val path: String,
    val dateTaken: Long,
    val size: Long,
    val mimeType: String,
    val type: PhotoType
)

/**
 * 照片扫描和分类工具
 */
object PhotoUtils {

    fun scanPhotos(context: Context): List<PhotoItem> {
        val result = mutableListOf<PhotoItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )

        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.MIME_TYPE} IN ('image/jpeg', 'image/png', 'image/webp', 'image/heic', 'image/heif')",
            null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        ) ?: return result

        cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dataCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val dateCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mimeCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val pathCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val widthCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val name = it.getString(nameCol) ?: continue
                val data = it.getString(dataCol) ?: continue
                val date = it.getLong(dateCol)
                val size = it.getLong(sizeCol)
                val mime = it.getString(mimeCol) ?: "image/jpeg"
                val relPath = it.getString(pathCol) ?: ""
                val width = it.getInt(widthCol)
                val height = it.getInt(heightCol)

                val type = classifyPhoto(name, relPath, data, width, height)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())

                result.add(PhotoItem(uri, name, data, date, size, mime, type))
            }
        }
        return result
    }

    private fun classifyPhoto(name: String, relPath: String, fullPath: String, width: Int, height: Int): PhotoType {
        val lowerName = name.lowercase()
        val lowerPath = (relPath + fullPath).lowercase()

        // 截图特征
        val screenshotKeywords = listOf("screenshot", "screen", "截屏", "截图", "capture", "snap")
        val screenshotFolders = listOf("screenshot", "screenshots", "截屏", "截图", "pictures/screenshots")

        for (kw in screenshotKeywords) {
            if (lowerName.contains(kw)) return PhotoType.SCREENSHOT
        }
        for (folder in screenshotFolders) {
            if (lowerPath.contains(folder)) return PhotoType.SCREENSHOT
        }

        // 微信/QQ 等聊天软件保存的图片 → 归为截图
        val chatFolders = listOf("weixin", "wechat", "微信", "tencent/micromsg", "qq", "tim")
        for (folder in chatFolders) {
            if (lowerPath.contains(folder)) return PhotoType.SCREENSHOT
        }

        // 相机特征
        val cameraKeywords = listOf("img_", "dsc_", "dscn", "pxl_", "vid_", "pan_", "mvimg_")
        val cameraFolders = listOf("dcim", "camera", "相机")

        for (kw in cameraKeywords) {
            if (lowerName.startsWith(kw)) return PhotoType.CAMERA
        }
        for (folder in cameraFolders) {
            if (lowerPath.contains(folder)) return PhotoType.CAMERA
        }

        // 根据图片尺寸判断：截图通常匹配屏幕分辨率
        if (width > 0 && height > 0) {
            // 16:9 或接近屏幕常见分辨率的 → 可能是截图
            val ratio = width.toFloat() / height.toFloat()
            if ((ratio in 1.7f..1.8f || ratio in 0.55f..0.6f) &&
                (width in 720..3000 || height in 720..3000)) {
                return PhotoType.SCREENSHOT
            }
            // 3:4 或 4:3 → 更可能是相机
            if (ratio in 0.74f..0.76f || ratio in 1.32f..1.35f) {
                return PhotoType.CAMERA
            }
        }

        return PhotoType.OTHER
    }
}
