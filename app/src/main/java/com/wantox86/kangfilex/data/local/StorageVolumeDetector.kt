package com.wantox86.kangfilex.data.local

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.wantox86.kangfilex.domain.model.StorageVolumeInfo
import java.io.File

interface StorageVolumeProvider {
    fun getVolumes(): List<StorageVolumeInfo>
}

class StorageVolumeDetector(
    private val context: Context
) : StorageVolumeProvider {

    override fun getVolumes(): List<StorageVolumeInfo> {
        val volumes = mutableListOf<StorageVolumeInfo>()
        val seenPaths = mutableSetOf<String>()

        val primaryDir = Environment.getExternalStorageDirectory()
        if (primaryDir != null) {
            val primaryPath = primaryDir.absolutePath
            seenPaths.add(primaryPath)
            val stats = readStorageStats(primaryPath)
            volumes.add(
                StorageVolumeInfo(
                    id = "local_internal",
                    label = "Internal Storage",
                    rootPath = primaryPath,
                    totalBytes = stats.totalBytes,
                    usedBytes = stats.usedBytes,
                    availableBytes = stats.availableBytes,
                    isRemovable = false
                )
            )
        }

        // Detect removable SD Card / USB volumes via app-specific external dirs
        val externalDirs = context.getExternalFilesDirs(null)
        externalDirs.drop(1).forEachIndexed { index, appSpecificDir ->
            val rootDir = extractVolumeRoot(appSpecificDir)
            if (rootDir != null && rootDir.exists() && seenPaths.add(rootDir.absolutePath)) {
                val stats = readStorageStats(rootDir.absolutePath)
                val volumeName = rootDir.name.ifBlank { "SD Card ${index + 1}" }
                volumes.add(
                    StorageVolumeInfo(
                        id = "local_removable_${rootDir.name}",
                        label = "SD Card ($volumeName)",
                        rootPath = rootDir.absolutePath,
                        totalBytes = stats.totalBytes,
                        usedBytes = stats.usedBytes,
                        availableBytes = stats.availableBytes,
                        isRemovable = true
                    )
                )
            }
        }

        return volumes
    }

    private fun extractVolumeRoot(appSpecificDir: File?): File? {
        if (appSpecificDir == null) return null
        val path = appSpecificDir.absolutePath
        val marker = "/Android/data/"
        val idx = path.indexOf(marker)
        if (idx <= 0) return null
        return File(path.substring(0, idx))
    }

    companion object {
        data class StorageStats(
            val totalBytes: Long,
            val usedBytes: Long,
            val availableBytes: Long
        )

        fun readStorageStats(path: String): StorageStats {
            return try {
                val statFs = StatFs(path)
                val total = statFs.totalBytes
                val available = statFs.availableBytes
                val used = (total - available).coerceAtLeast(0L)
                StorageStats(
                    totalBytes = total,
                    usedBytes = used,
                    availableBytes = available
                )
            } catch (_: Throwable) {
                StorageStats(0L, 0L, 0L)
            }
        }
    }
}
