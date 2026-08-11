package com.lanshare.app.model

import com.google.gson.annotations.SerializedName

/**
 * 局域网设备信息
 */
data class Device(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("ipAddress")
    val ipAddress: String,

    @SerializedName("port")
    val port: Int = 8080,

    @SerializedName("deviceType")
    val deviceType: String = "android",

    @SerializedName("groupId")
    val groupId: String? = null,

    @SerializedName("lastSeen")
    val lastSeen: Long = System.currentTimeMillis(),

    @SerializedName("isOnline")
    val isOnline: Boolean = true
) {
    val displayUrl: String get() = "http://$ipAddress:$port"

    val isInGroup: Boolean get() = groupId != null

    companion object {
        fun localDevice(name: String, ip: String, port: Int, groupId: String? = null): Device {
            return Device(
                id = "${ip}_$port",
                name = name,
                ipAddress = ip,
                port = port,
                groupId = groupId
            )
        }
    }
}
