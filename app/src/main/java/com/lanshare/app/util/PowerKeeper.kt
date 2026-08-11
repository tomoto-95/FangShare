package com.lanshare.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 常驻保活工具 — 仅副机使用
 * 申请忽略电池优化（系统白名单），防止应用在后台被系统杀死
 */
object PowerKeeper {

    /**
     * 是否已加入电池优化白名单
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 如果需要则弹出系统"电池优化"授权对话框
     * 注意：仅在副机角色下调用；用户拒绝后不再重复打扰
     */
    fun requestIgnoreBatteryOptimizationsIfNeeded(context: Context) {
        try {
            if (isIgnoringBatteryOptimizations(context)) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

            // 只在主线程发起
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // 部分 ROM（华为/荣耀）可能没有该 Activity，静默失败
        }
    }

    /**
     * 跳转到应用设置页（用于引导用户手动开启"自启动/后台运行"等开关）
     */
    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}
