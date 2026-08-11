package com.fangshare.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * 获取本机局域网 IPv4 地址
     */
    fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.firstOrNull { addr ->
                    !addr.isLoopbackAddress && addr is Inet4Address
                }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 检查是否连接到 WiFi
     */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 获取 WiFi SSID
     */
    fun getWifiSsid(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.connectionInfo?.ssid?.trim('"')
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取 WiFi 多播锁（华为/荣耀设备 NSD 需要此锁保证组播正常工作）
     */
    fun acquireMulticastLock(context: Context) {
        try {
            if (multicastLock == null) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifiManager?.createMulticastLock("Fangshare-Multicast")
                multicastLock?.setReferenceCounted(false)
            }
            multicastLock?.acquire()
        } catch (e: Exception) {
            android.util.Log.w("NetworkUtils", "acquireMulticastLock failed: ${e.message}")
        }
    }

    /**
     * 释放 WiFi 多播锁
     */
    fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            android.util.Log.w("NetworkUtils", "releaseMulticastLock failed: ${e.message}")
        }
    }
}
