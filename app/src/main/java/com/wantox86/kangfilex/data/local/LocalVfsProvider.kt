package com.wantox86.kangfilex.data.local

import com.wantox86.kangfilex.domain.model.VfsFile
import com.wantox86.kangfilex.domain.vfs.VfsProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class LocalVfsProvider(
    override val providerId: String = DEFAULT_PROVIDER_ID,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val renameOp: (File, File) -> Boolean = { from, to -> from.renameTo(to) }
) : VfsProvider {

    private val connected = AtomicBoolean(true)

    override suspend fun connect(): Result<Unit> {
        connected.set(true)
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        connected.set(false)
    }

    override suspend fun isConnected(): Boolean = connected.get()

    override suspend fun listFiles(path: String): Result<List<VfsFile>> = withContext(ioDispatcher) {
        runCatching {
            val dir = File(path)
            if (!dir.exists()) {
                throw FileNotFoundException("Directory does not exist: $path")
            }
            if (!dir.isDirectory) {
                throw IOException("Path is not a directory: $path")
            }
            if (!dir.canRead()) {
                throw SecurityException("Permission denied reading directory: $path")
            }

            val children = dir.listFiles()
                ?: throw SecurityException("Unable to list files in directory: $path")

            children.map { child ->
                val isSymlink = isSymbolicLinkSafe(child)
                val isDir = child.isDirectory
                val counts = if (isDir && !isSymlink) {
                    countDirectChildrenSafe(child)
                } else {
                    null
                }
                VfsFile(
                    name = child.name,
                    path = child.absolutePath,
                    isDirectory = isDir,
                    size = if (isDir) 0L else child.length(),
                    lastModified = child.lastModified(),
                    providerId = providerId,
                    isSymlink = isSymlink,
                    childDirCount = counts?.first,
                    childFileCount = counts?.second
                )
            }.sortedWith(
                compareByDescending<VfsFile> { it.isDirectory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
        }
    }

    override suspend fun openInputStream(path: String): InputStream = withContext(ioDispatcher) {
        val file = File(path)
        if (!file.exists()) {
            throw FileNotFoundException("File not found: $path")
        }
        if (file.isDirectory) {
            throw IOException("Cannot open input stream for a directory: $path")
        }
        FileInputStream(file)
    }

    override suspend fun openOutputStream(path: String, overwrite: Boolean): OutputStream =
        withContext(ioDispatcher) {
            val file = File(path)
            if (file.exists() && !overwrite) {
                throw IOException("File already exists and overwrite is disabled: $path")
            }
            if (file.isDirectory) {
                throw IOException("Cannot open output stream for a directory: $path")
            }
            file.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IOException("Failed to create parent directories for: $path")
                }
            }
            FileOutputStream(file, false)
        }

    override suspend fun createDirectory(path: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val dir = File(path)
            if (dir.exists()) {
                if (dir.isDirectory) return@runCatching
                throw IOException("A file already exists at path: $path")
            }
            if (!dir.mkdirs()) {
                throw IOException("Failed to create directory: $path")
            }
        }
    }

    override suspend fun delete(path: String, recursive: Boolean): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val target = File(path)
                if (!target.exists() && !isSymbolicLinkSafe(target)) {
                    throw FileNotFoundException("Target does not exist: $path")
                }

                // Never traverse into symlink targets during recursive deletion
                if (isSymbolicLinkSafe(target)) {
                    if (!target.delete()) {
                        throw IOException("Failed to delete symlink: $path")
                    }
                    return@runCatching
                }

                if (target.isDirectory) {
                    val entries = target.listFiles()
                    if (!recursive && !entries.isNullOrEmpty()) {
                        throw IOException("Directory is not empty: $path")
                    }
                    val deleted = if (recursive) {
                        deleteDirectorySafely(target)
                    } else {
                        target.delete()
                    }
                    if (!deleted) {
                        throw IOException("Failed to delete directory: $path")
                    }
                } else {
                    if (!target.delete()) {
                        throw IOException("Failed to delete file: $path")
                    }
                }
            }
        }

    override suspend fun rename(oldPath: String, newPath: String): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val source = File(oldPath)
                val dest = File(newPath)
                if (!source.exists()) {
                    throw FileNotFoundException("Source does not exist: $oldPath")
                }
                if (dest.exists()) {
                    if (isCaseOnlySameFileRename(source, dest)) {
                        renameCaseOnly(source, dest)
                        return@runCatching
                    }
                    throw IOException("Destination already exists: $newPath")
                }
                if (!renameOp(source, dest)) {
                    throw IOException("Failed to rename $oldPath to $newPath")
                }
            }
        }

    override suspend fun exists(path: String): Boolean = withContext(ioDispatcher) {
        val target = File(path)
        target.exists() || isSymbolicLinkSafe(target)
    }

    private fun deleteDirectorySafely(dir: File): Boolean {
        val children = dir.listFiles()
        if (children != null) {
            for (child in children) {
                val ok = if (isSymbolicLinkSafe(child) || !child.isDirectory) {
                    child.delete()
                } else {
                    deleteDirectorySafely(child)
                }
                if (!ok) return false
            }
        }
        return dir.delete()
    }

    private fun renameCaseOnly(source: File, dest: File) {
        val parent = source.absoluteFile.parentFile
            ?: throw IOException("Cannot determine parent directory for ${source.path}")

        var tmp: File? = null
        repeat(5) {
            if (tmp == null) {
                val candidate = File(parent, ".kfx-rename-${UUID.randomUUID()}")
                if (!candidate.exists() && !isSymbolicLinkSafe(candidate)) {
                    tmp = candidate
                }
            }
        }
        val tempFile = tmp
            ?: throw IOException("Failed to allocate temporary name for ${source.path}")

        if (!renameOp(source, tempFile)) {
            throw IOException("Failed to rename ${source.path} to ${dest.path} (step 1)")
        }

        if (!renameOp(tempFile, dest)) {
            try {
                renameOp(tempFile, source)
            } catch (_: Throwable) {
                // Best-effort rollback; always report step 2 failure below.
            }
            throw IOException(
                "Failed to rename ${source.path} to ${dest.path} (step 2), restored original name"
            )
        }

        val actualNames = parent.list()
        if (actualNames == null || !actualNames.contains(dest.name)) {
            throw IOException(
                "Rename succeeded but filesystem name did not change to '${dest.name}'"
            )
        }
    }

    private fun isCaseOnlySameFileRename(source: File, dest: File): Boolean {
        if (!dest.name.equals(source.name, ignoreCase = true)) return false
        val sameParent = try {
            val sourceParent = source.parentFile?.canonicalFile ?: source.absoluteFile.parentFile
            val destParent = dest.parentFile?.canonicalFile ?: dest.absoluteFile.parentFile
            sourceParent != null && sourceParent == destParent
        } catch (_: Throwable) {
            source.absoluteFile.parentFile == dest.absoluteFile.parentFile
        }
        if (!sameParent) return false
        if (isSymbolicLinkSafe(dest)) return false
        return try {
            Files.isSameFile(source.toPath(), dest.toPath())
        } catch (_: Throwable) {
            false
        }
    }

    private fun isSymbolicLinkSafe(file: File): Boolean {
        return try {
            Files.isSymbolicLink(file.toPath())
        } catch (_: Throwable) {
            false
        }
    }

    private fun countDirectChildrenSafe(dir: File): Pair<Int, Int>? {
        return try {
            if (!dir.canRead()) return null
            val list = dir.listFiles() ?: return null
            var dirs = 0
            var files = 0
            for (item in list) {
                if (item.isDirectory) dirs++ else files++
            }
            dirs to files
        } catch (_: SecurityException) {
            null
        }
    }

    companion object {
        const val DEFAULT_PROVIDER_ID = "local"
    }
}
