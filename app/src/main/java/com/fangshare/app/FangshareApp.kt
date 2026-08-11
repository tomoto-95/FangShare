package com.fangshare.app

import android.app.Application
import com.fangshare.app.util.CrashHandler

class FangshareApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 初始化全局崩溃处理（防止华为/荣耀设备 NSD 崩溃）
        CrashHandler.init(this)
    }

    companion object {
        lateinit var instance: FangshareApp
            private set
    }
}
