package com.wantox86.kangfilex.domain.transfer

import com.wantox86.kangfilex.domain.vfs.VfsProvider

object ConflictResolver {

    fun candidateName(originalName: String, isDirectory: Boolean, index: Int): String {
        require(index >= 1) { "Indeks penamaan harus >= 1" }
        if (isDirectory) {
            return "$originalName ($index)"
        }
        val lastDot = originalName.lastIndexOf('.')
        return if (lastDot in 1 until originalName.lastIndex) {
            val baseName = originalName.substring(0, lastDot)
            val extension = originalName.substring(lastDot)
            "$baseName ($index)$extension"
        } else {
            "$originalName ($index)"
        }
    }

    suspend fun uniqueName(
        provider: VfsProvider,
        dirPath: String,
        originalName: String,
        isDirectory: Boolean
    ): String {
        var index = 1
        while (index < Int.MAX_VALUE) {
            val candidate = candidateName(originalName, isDirectory, index)
            val candidatePath = joinPath(dirPath, candidate)
            if (!provider.exists(candidatePath)) {
                return candidate
            }
            index++
        }
        throw TransferException("Gagal membuat nama unik untuk '$originalName'")
    }

    internal fun normalizePath(path: String): String {
        val trimmed = path.trimEnd('/')
        return if (trimmed.isEmpty()) {
            if (path.startsWith("/")) "/" else ""
        } else {
            trimmed
        }
    }

    internal fun joinPath(parentDir: String, childName: String): String {
        val cleanParent = normalizePath(parentDir)
        val cleanChild = childName.trimStart('/')
        return when {
            cleanParent.isEmpty() -> cleanChild
            cleanParent == "/" -> "/$cleanChild"
            else -> "$cleanParent/$cleanChild"
        }
    }

    internal fun parentPath(path: String): String {
        val normalized = normalizePath(path)
        if (normalized.isEmpty() || normalized == "/") return normalized
        val lastSlash = normalized.lastIndexOf('/')
        return when {
            lastSlash < 0 -> ""
            lastSlash == 0 -> "/"
            else -> normalized.substring(0, lastSlash)
        }
    }

    internal fun fileName(path: String): String {
        val normalized = normalizePath(path)
        val lastSlash = normalized.lastIndexOf('/')
        return if (lastSlash >= 0) normalized.substring(lastSlash + 1) else normalized
    }
}
