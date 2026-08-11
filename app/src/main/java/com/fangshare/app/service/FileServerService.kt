package com.fangshare.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fangshare.app.MainActivity
import com.fangshare.app.model.Device
import com.fangshare.app.model.TransferDirection
import com.fangshare.app.model.TransferStatus
import com.fangshare.app.model.TransferTask
import com.fangshare.app.util.FileUtils
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 前台服务 — 运行 NanoHTTPd 文件接收服务器
 */
class FileServerService : Service() {

    companion object {
        private const val CHANNEL_ID = "fangshare_server"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.fangshare.app.STOP_SERVER"
        const val EXTRA_PERSISTENT = "persistent"
    }

    private var server: FileReceiveServer? = null
    private var persistent = false
    private val _receiveTasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val receiveTasks: StateFlow<List<TransferTask>> = _receiveTasks.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra("port", 8080) ?: 8080
        persistent = intent?.getBooleanExtra(EXTRA_PERSISTENT, false) ?: false
        startServer(port)
        startForeground(NOTIFICATION_ID, createNotification())

        // 副机常驻模式：系统杀死后自动重启，且标记为可恢复
        return if (persistent) START_REDELIVER_INTENT else START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer(port: Int) {
        // 已在运行时跳过（setRole 与 initialize 可能先后触发）
        if (server?.wasStarted() == true) return
        server = FileReceiveServer(this, port)
        try {
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 统一使用 IMPORTANCE_LOW：不发出声音；通知栏静音由 setSilent 保证
            val channel = NotificationChannel(
                CHANNEL_ID,
                "文件接收服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持在后台接收文件"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 常驻模式：静音、不可滑动关闭、显示"常驻"文案
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (persistent) "Fangshare 副机常驻" else "Fangshare 文件接收")
            .setContentText(if (persistent) "常驻运行中，随时接收文件" else "正在监听文件传输…")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun updateNotification(text: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Fangshare 文件接收")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build()
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    /**
     * 内部 HTTP 文件接收服务器
     */
    private inner class FileReceiveServer(context: Context, port: Int) :
        NanoHTTPD(port) {

        private val appContext = context.applicationContext

        // 缩略图内存缓存：key=MediaStore id, value=JPEG bytes（上限约 32MB）
        private val thumbCache = android.util.LruCache<Long, ByteArray>(
            (32 * 1024 * 1024) / (400 * 400)  // 估算缓存条目数
        )

        override fun serve(session: IHTTPSession?): Response {
            if (session == null) return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "text/plain",
                "Bad Request"
            )

            return when {
                session.method == Method.GET && session.uri == "/ping" -> {
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        """{"status":"ok","device":"${android.os.Build.MODEL}"}"""
                    )
                }

                session.method == Method.GET && session.uri.startsWith("/files/list") -> {
                    handleFileList(session)
                }

                session.method == Method.GET && session.uri.startsWith("/files/download") -> {
                    handleFileDownload(session)
                }

                session.method == Method.GET && session.uri.startsWith("/photos/list") -> {
                    handlePhotoList(session)
                }

                session.method == Method.GET && session.uri.startsWith("/photos/thumb") -> {
                    handlePhotoThumb(session)
                }

                session.method == Method.POST && session.uri == "/receive" -> {
                    handleFileReceive(session)
                }

                session.method == Method.GET && session.uri == "/info" -> {
                    // 返回设备信息
                    val info = """{"name":"${android.os.Build.MODEL}","type":"android"}"""
                    newFixedLengthResponse(Response.Status.OK, "application/json", info)
                }

                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "Not Found"
                )
            }
        }

        private fun handleFileReceive(session: IHTTPSession): Response {
            return try {
                val files = mutableMapOf<String, String>()
                try {
                    session.parseBody(files)
                } catch (e: Exception) {
                    android.util.Log.e("FileServer", "parseBody failed: ${e.message}", e)
                    return newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        "application/json",
                        """{"status":"error","message":"parseBody failed: ${e.message}"}"""
                    )
                }
                android.util.Log.i("FileServer", "receive: parseBody files=${files.keys} remoteIp=${session.remoteIpAddress}")

                val fileName = session.headers["x-file-name"] ?: "received_file_${System.currentTimeMillis()}"
                val fileSize = session.headers["x-file-size"]?.toLongOrNull() ?: 0
                val senderName = session.headers["x-sender-name"] ?: "Unknown"

                val receiveDir = FileUtils.getReceiveDirectory(appContext)
                if (!receiveDir.exists() && !receiveDir.mkdirs()) {
                    return newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        "application/json",
                        """{"status":"error","message":"cannot create dir $receiveDir"}"""
                    )
                }
                val safeFileName = sanitizeFileName(fileName)
                val outputFile = File(receiveDir, safeFileName)

                // 创建传输任务
                val task = TransferTask(
                    id = UUID.randomUUID().toString(),
                    fileName = safeFileName,
                    filePath = outputFile.absolutePath,
                    fileSize = fileSize,
                    mimeType = FileUtils.getMimeType(safeFileName),
                    status = TransferStatus.TRANSFERRING,
                    direction = TransferDirection.RECEIVING,
                    sourceDevice = com.fangshare.app.model.Device(
                        id = "sender",
                        name = senderName,
                        ipAddress = session.remoteIpAddress ?: "unknown",
                        port = 0
                    )
                )

                _receiveTasks.value = _receiveTasks.value + task

                // 从临时文件复制到目标位置
                val tmpFilePath = files.values.firstOrNull()
                if (tmpFilePath == null) {
                    android.util.Log.e("FileServer", "receive: no tmp file in body, headers=${session.headers}")
                    val failedTask = task.copy(status = TransferStatus.FAILED, errorMessage = "no file in multipart body")
                    _receiveTasks.value = _receiveTasks.value.map { if (it.id == task.id) failedTask else it }
                    return newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        "application/json",
                        """{"status":"error","message":"no file in multipart body"}"""
                    )
                }

                val tmpFile = File(tmpFilePath)
                if (!tmpFile.exists()) {
                    android.util.Log.e("FileServer", "receive: tmp file not found $tmpFilePath")
                    return newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        "application/json",
                        """{"status":"error","message":"tmp file disappeared: $tmpFilePath"}"""
                    )
                }
                // 用流式拷贝避免 EACCES 权限问题（NanoHTTPD tmp 文件可能在受限路径）
                tmpFile.inputStream().use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tmpFile.delete()
                android.util.Log.i("FileServer", "receive: saved $safeFileName (${outputFile.length()} bytes) to ${outputFile.absolutePath}")

                // 发布到系统媒体库（相册可见）
                val publishedUri = publishToMediaStore(outputFile)
                android.util.Log.i("FileServer", "receive: publishToMediaStore=$publishedUri")

                // 更新任务状态
                val updatedTask = task.copy(
                    status = TransferStatus.COMPLETED,
                    progress = 1f,
                    transferredBytes = outputFile.length()
                )
                _receiveTasks.value = _receiveTasks.value.map {
                    if (it.id == task.id) updatedTask else it
                }

                // 通知 UI 有新文件到达
                ReceiveNotifier.notifyFileReceived(updatedTask)

                // 更新通知栏
                updateNotification("收到: $safeFileName")

                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"received","fileName":"$safeFileName"}"""
                )

            } catch (e: Exception) {
                android.util.Log.e("FileServer", "handleFileReceive error", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"status":"error","message":"${e.javaClass.simpleName}: ${e.message}"}"""
                )
            }
        }

        private fun sanitizeFileName(name: String): String {
            return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        }

        /**
         * 将接收的文件发布到系统媒体库，使其在系统相册/图库中可见
         * @return 媒体库 Uri 或 null（发布失败）
         */
        private fun publishToMediaStore(file: File): String? {
            return try {
                val mime = FileUtils.getMimeType(file.name)
                val resolver = appContext.contentResolver
                val sdk = android.os.Build.VERSION.SDK_INT
                val now = System.currentTimeMillis() / 1000

                val collection = when {
                    mime.startsWith("image/") ->
                        if (sdk >= 29) android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    mime.startsWith("video/") ->
                        if (sdk >= 29) android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        else android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    mime.startsWith("audio/") ->
                        if (sdk >= 29) android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        else android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else ->
                        if (sdk >= 29) android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        else android.provider.MediaStore.Files.getContentUri("external")
                }

                val relPath = when {
                    mime.startsWith("image/") -> "Pictures/Fangshare"
                    mime.startsWith("video/") -> "Movies/Fangshare"
                    mime.startsWith("audio/") -> "Music/Fangshare"
                    else -> "Download/Fangshare"
                }

                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(android.provider.MediaStore.MediaColumns.DATE_ADDED, now)
                    put(android.provider.MediaStore.MediaColumns.DATE_MODIFIED, now)
                    if (sdk >= 29) {
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val uri = resolver.insert(collection, values) ?: return null
                val out = resolver.openOutputStream(uri) ?: return null
                out.use { o ->
                    file.inputStream().use { it.copyTo(o) }
                }

                if (sdk >= 29) {
                    values.clear()
                    values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                uri.toString()
            } catch (e: Exception) {
                android.util.Log.w("FileServer", "publishToMediaStore failed: ${e.message}")
                null
            }
        }

        private fun handleFileList(session: IHTTPSession): Response {
            return try {
                val params = session.parameters ?: emptyMap()
                val path = params["path"]?.firstOrNull() ?: ""
                val encodedPath = java.net.URLDecoder.decode(path, "UTF-8")
                val data = queryFilesByPath(encodedPath)
                val json = com.google.gson.GsonBuilder().create().toJson(data)
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    """{"error":"${e.message}"}""")
            }
        }

        private fun queryFilesByPath(path: String): java.util.LinkedHashMap<String, Any> {
            val result = java.util.LinkedHashMap<String, Any>()
            val list = mutableListOf<Map<String, Any>>()
            val seenPaths = mutableSetOf<String>()

            if (path.isEmpty() || path == "/") {
                val roots = listOf("Alarms","Android","Audiobooks","DCIM","Download","Documents","Movies","Music","Notifications","Pictures","Podcasts","Recordings","Ringtones")
                result["currentPath"] = ""; result["parentPath"] = ""
                result["files"] = roots.map { mapOf("name" to it, "path" to "/storage/emulated/0/$it", "isDirectory" to true, "size" to 0L, "lastModified" to 0) }
                return result
            }

            val dir = java.io.File(path)
            val relPath = if (path.startsWith("/storage/emulated/0/")) path.removePrefix("/storage/emulated/0/") + "/" else ""

            // 1) File API
            if (dir.exists() && dir.isDirectory) {
                val raw = dir.listFiles() ?: emptyArray()
                for (f in raw.sortedBy { !it.isDirectory }) {
                    list.add(mapOf("name" to (f.name ?: ""), "path" to f.absolutePath, "isDirectory" to f.isDirectory, "size" to f.length(), "lastModified" to f.lastModified()))
                    seenPaths.add(f.absolutePath.lowercase())
                }
            }

            // 2) MediaStore: 查询该目录下所有文件
            try {
                val uri = android.provider.MediaStore.Files.getContentUri("external")
                val proj = arrayOf(android.provider.MediaStore.Files.FileColumns.DATA, android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME, android.provider.MediaStore.Files.FileColumns.SIZE, android.provider.MediaStore.Files.FileColumns.DATE_MODIFIED)
                val sel = if (relPath.isEmpty()) "${android.provider.MediaStore.Files.FileColumns.DATA} LIKE ?" else "${android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH} = ?"
                val selArgs = if (relPath.isEmpty()) arrayOf("${path.removeSuffix("/")}/%") else arrayOf(relPath)
                // 限制只查直接子文件，排除子目录
                val cursor = appContext.contentResolver.query(uri, proj, sel, selArgs, null)
                cursor?.use { c ->
                    val dc = c.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns.DATA)
                    val nc = c.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val sc = c.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns.SIZE)
                    val mc = c.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns.DATE_MODIFIED)
                    while (c.moveToNext()) {
                        val data = c.getString(dc) ?: continue
                        val parent = java.io.File(data).parentFile?.absolutePath ?: ""
                        if (parent != path) continue // 只要当前目录的直接子文件
                        val absPath = data
                        if (seenPaths.contains(absPath.lowercase())) continue
                        seenPaths.add(absPath.lowercase())
                        list.add(mapOf("name" to (c.getString(nc) ?: ""), "path" to absPath, "isDirectory" to false, "size" to c.getLong(sc), "lastModified" to c.getLong(mc) * 1000L))
                    }
                }
            } catch (_: Exception) {}

            // 3) MediaStore: 查询子目录
            try {
                val dirUri = android.provider.MediaStore.Files.getContentUri("external")
                val dirProj = arrayOf("DISTINCT ${android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH}")
                val dirSel = if (relPath.isEmpty()) "${android.provider.MediaStore.Files.FileColumns.DATA} LIKE ?"
                    else "${android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
                val dirArgs = if (relPath.isEmpty()) arrayOf("${path.removeSuffix("/")}/%/%") else arrayOf("${relPath}%/")
                val dc = appContext.contentResolver.query(dirUri, dirProj, dirSel, dirArgs, null)
                dc?.use { c ->
                    val pc = c.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH)
                    while (c.moveToNext()) {
                        val fullRel = c.getString(pc) ?: continue
                        if (fullRel == relPath) continue
                        val sub = fullRel.removePrefix(relPath).substringBefore("/")
                        if (sub.isEmpty()) continue
                        val subFull = "$path/$sub"
                        if (seenPaths.contains(subFull.lowercase())) continue
                        seenPaths.add(subFull.lowercase())
                        list.add(0, mapOf("name" to sub, "path" to subFull, "isDirectory" to true, "size" to 0L, "lastModified" to 0L))
                    }
                }
            } catch (_: Exception) {}

            val sorted = list.sortedByDescending { it["isDirectory"] as? Boolean ?: false }
            result["currentPath"] = path; result["parentPath"] = dir.parentFile?.absolutePath ?: "/"; result["files"] = sorted
            return result
        }

        private fun encodeUri(realPath: String): String {
            return java.net.URLEncoder.encode(realPath, "UTF-8")
        }

        private fun handlePhotoList(session: IHTTPSession): Response {
            return try {
                val params = session.parameters ?: emptyMap()
                val offset = params["offset"]?.firstOrNull()?.toIntOrNull() ?: 0
                val limit = params["limit"]?.firstOrNull()?.toIntOrNull() ?: 200
                val photos = mutableListOf<Map<String, Any>>()
                val cursor = appContext.contentResolver.query(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        android.provider.MediaStore.Images.Media._ID,
                        android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                        android.provider.MediaStore.Images.Media.DATA,
                        android.provider.MediaStore.Images.Media.SIZE,
                        android.provider.MediaStore.Images.Media.MIME_TYPE,
                        android.provider.MediaStore.Images.Media.DATE_TAKEN
                    ),
                    null, null,
                    "${android.provider.MediaStore.Images.Media.DATE_TAKEN} DESC LIMIT $limit OFFSET $offset"
                )
                cursor?.use {
                    val idCol = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                    val nameCol = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                    val dataCol = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA)
                    val sizeCol = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.SIZE)
                    val mimeCol = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.MIME_TYPE)
                    while (it.moveToNext()) {
                        val id = it.getLong(idCol)
                        photos.add(mapOf(
                            "name" to it.getString(nameCol),
                            "path" to (it.getString(dataCol) ?: ""),
                            "size" to it.getLong(sizeCol),
                            "mimeType" to (it.getString(mimeCol) ?: "image/jpeg"),
                            "thumbUrl" to "/photos/thumb?id=$id&size=400"
                        ))
                    }
                }
                val json = com.google.gson.GsonBuilder().create().toJson(mapOf("photos" to photos))
                newFixedLengthResponse(Response.Status.OK, "application/json", json)
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    """{"error":"${e.message}"}""")
            }
        }

        private fun handlePhotoThumb(session: IHTTPSession): Response {
            return try {
                val params = session.parameters ?: emptyMap()
                val id = params["id"]?.firstOrNull()?.toLongOrNull()
                    ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing id")
                val size = params["size"]?.firstOrNull()?.toIntOrNull() ?: 400

                // 内存缓存命中直接返回（避免重复解码大图）
                thumbCache.get(id)?.let { cached ->
                    val resp = newFixedLengthResponse(
                        Response.Status.OK, "image/jpeg",
                        java.io.ByteArrayInputStream(cached), cached.size.toLong()
                    )
                    resp.addHeader("Cache-Control", "max-age=86400, immutable")
                    return resp
                }

                val uri = android.net.Uri.withAppendedPath(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                android.util.Log.d("FileServer", "thumb: open uri=$uri")

                // 第一次解码仅获取尺寸（不读像素）
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                val boundsInput = appContext.contentResolver.openInputStream(uri)
                if (boundsInput == null) {
                    android.util.Log.e("FileServer", "thumb: openInputStream null for $uri")
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
                }
                boundsInput.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    android.util.Log.e("FileServer", "thumb: bad bounds w=${bounds.outWidth} h=${bounds.outHeight} for $uri")
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "bad image")
                }
                android.util.Log.d("FileServer", "thumb: bounds ${bounds.outWidth}x${bounds.outHeight}")

                // 计算采样率（2 的幂），快速解码出接近目标尺寸的位图，大幅减少内存与耗时
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2

                val opts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 // 缩略图无需 alpha
                }
                val bitmap = appContext.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, opts)
                }
                if (bitmap == null) {
                    android.util.Log.e("FileServer", "thumb: decode failed for $uri")
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "decode failed")
                }

                // 精确缩放至目标尺寸
                val scaled = if (bitmap.width > size || bitmap.height > size) {
                    val scale = size.toFloat() / maxOf(bitmap.width, bitmap.height)
                    android.graphics.Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else bitmap

                val baos = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, baos)
                val bytes = baos.toByteArray()

                if (scaled !== bitmap) bitmap.recycle()
                scaled.recycle()

                thumbCache.put(id, bytes)
                android.util.Log.d("FileServer", "thumb $id -> ${bytes.size} bytes (sample=$sample)")

                val resp = newFixedLengthResponse(
                    Response.Status.OK, "image/jpeg",
                    java.io.ByteArrayInputStream(bytes), bytes.size.toLong()
                )
                resp.addHeader("Cache-Control", "max-age=86400, immutable")
                resp
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
            }
        }

        private fun handleFileDownload(session: IHTTPSession): Response {
            return try {
                val params = session.parameters ?: emptyMap()
                val rawPath = params["path"]?.firstOrNull() ?: return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "text/plain", "missing path")
                val path = java.net.URLDecoder.decode(rawPath, "UTF-8")
                val file = java.io.File(path)
                if (!file.exists() || !file.isFile) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
                }
                val mime = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"
                val fis = java.io.FileInputStream(file)
                newChunkedResponse(Response.Status.OK, mime, fis)
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
            }
        }
    }
}
