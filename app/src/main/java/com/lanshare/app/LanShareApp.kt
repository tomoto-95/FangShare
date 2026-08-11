package com.lanshare.app

import android.app.Application
import com.lanshare.app.util.CrashHandler

class LanShareApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 初始化全局崩溃处理（防止华为/荣耀设备 NSD 崩溃）
        CrashHandler.init(this)
    }

    companion object {
        lateinit var instance: LanShareApp
            private set
    }
}
