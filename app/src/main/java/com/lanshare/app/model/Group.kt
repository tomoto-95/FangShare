package com.lanshare.app.model

import java.util.UUID

/**
 * 设备分组（纯本地管理）
 */
data class DeviceGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val deviceIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) {
    val memberCount: Int get() = deviceIds.size
}
