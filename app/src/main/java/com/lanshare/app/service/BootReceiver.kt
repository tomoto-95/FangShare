package com.lanshare.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启接收器 — 仅副机（CLIENT）模式生效
 * 副机重启后自动恢复常驻接收服务，实现"永不关闭"
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        try {
            // 仅副机角色自启
            val prefs = context.getSharedPreferences("lan_share_prefs", Context.MODE_PRIVATE)
            val savedRole = prefs.getString("device_role", null)
            if (savedRole != "CLIENT") return

            val serviceIntent = Intent(context, FileServerService::class.java).apply {
                putExtra("port", 8080)
                putExtra(FileServerService.EXTRA_PERSISTENT, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {
            // 华为/荣耀可能拦截自启动，静默失败（需用户在设置中允许自启动）
        }
    }
}
