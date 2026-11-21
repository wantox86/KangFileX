package com.wantox86.kangfilex.domain.model

data class StorageVolumeInfo(
    val id: String,
    val label: String,
    val rootPath: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val availableBytes: Long,
    val isRemovable: Boolean = false
) {
    val usedFraction: Float
        get() = if (totalBytes > 0L) {
            (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}
