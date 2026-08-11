package com.fangshare.app.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.fangshare.app.MainActivity
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 全局异常捕获 — 防止 Honor/华为设备上因 NSD 等系统级异常导致闪退
 * 主要职责：记录崩溃日志 + 对于可恢复的系统崩溃进行优雅降级
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "CrashHandler"
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var applicationContext: Context? = null

    fun init(context: Context) {
        applicationContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.d(TAG, "Global crash handler initialized")
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        Log.e(TAG, "=== UNCAUGHT CRASH ===")
        Log.e(TAG, "Device: ${android.os.Build.BRAND} ${android.os.Build.MODEL}")
        Log.e(TAG, "SDK: ${android.os.Build.VERSION.SDK_INT}")
        Log.e(TAG, "Thread: ${thread.name}")
        Log.e(TAG, "Exception: ${throwable.javaClass.name}")
        Log.e(TAG, "Message: ${throwable.message}")
        Log.e(TAG, stackTrace)

        // 将崩溃日志写入文件供后续分析
        try {
            val ctx = applicationContext
            if (ctx != null) {
                val logDir = ctx.getExternalFilesDir("crash_logs")
                if (logDir != null && !logDir.exists()) logDir.mkdirs()
                val logFile = java.io.File(logDir, "crash_${System.currentTimeMillis()}.txt")
                logFile.writeText(
                    "Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(java.util.Date())}\n" +
                    "Device: ${android.os.Build.BRAND} ${android.os.Build.MODEL}\n" +
                    "SDK: ${android.os.Build.VERSION.SDK_INT}\n" +
                    "Thread: ${thread.name}\n" +
                    "Exception: ${throwable.javaClass.name}\n" +
                    "Message: ${throwable.message}\n\n" +
                    stackTrace
                )
                Log.d(TAG, "Crash log saved: ${logFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save crash log: ${e.message}")
        }

        // 检查是否是可恢复的系统级异常
        val isRecoverable = isRecoverableCrash(throwable)

        if (isRecoverable) {
            Log.w(TAG, "Recoverable crash detected, attempting graceful restart via activity...")
            tryRestartViaActivity()
            return
        }

        // 默认处理：交给系统默认处理器
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun isRecoverableCrash(throwable: Throwable): Boolean {
        val msg = throwable.message ?: ""
        val causeName = throwable.cause?.javaClass?.name ?: ""
        val className = throwable.javaClass.name

        return msg.contains("Nsd", ignoreCase = true) ||
               msg.contains("nsd", ignoreCase = true) ||
               msg.contains("SERVICE_NOT_AVAILABLE") ||
               msg.contains("ForegroundService") ||
               msg.contains("foreground") ||
               msg.contains("multicast") ||
               msg.contains("MulticastLock") ||
               causeName.contains("Nsd") ||
               className.contains("Nsd")
    }

    private fun tryRestartViaActivity() {
        try {
            val ctx = applicationContext ?: return
            // 直接用 startActivity 尝试重启
            val intent = Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            ctx.startActivity(intent)
            Log.d(TAG, "Activity restart initiated")
        } catch (e: Exception) {
            Log.w(TAG, "Activity restart failed: ${e.message}")
        }
        // 等待 500ms 后退出当前进程
        try { Thread.sleep(500) } catch (_: InterruptedException) {}
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
