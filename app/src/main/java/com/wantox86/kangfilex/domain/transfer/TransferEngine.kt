package com.wantox86.kangfilex.domain.transfer

import com.wantox86.kangfilex.domain.model.VfsFile
import com.wantox86.kangfilex.domain.vfs.VfsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class TransferEngine(
    private val sourceProvider: VfsProvider,
    private val destProvider: VfsProvider = sourceProvider,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {

    init {
        require(bufferSize > 0) { "Ukuran buffer harus lebih besar dari 0" }
    }

    suspend fun execute(
        request: TransferRequest,
        onProgress: (TransferProgress) -> Unit = {},
        onConflict: suspend (ConflictInfo) -> ConflictDecision
    ): TransferResult = withContext(ioDispatcher) {
        if (request.sources.isEmpty()) {
            throw TransferException("Daftar file sumber tidak boleh kosong")
        }

        val destDir = ConflictResolver.normalizePath(request.destDirPath)
        if (destDir.isEmpty()) {
            throw TransferException("Direktori tujuan tidak valid")
        }

        val sameProvider = sourceProvider.providerId == destProvider.providerId

        // Guard self-copy / subfolder BEFORE any write I/O
        if (sameProvider) {
            for (source in request.sources) {
                if (source.isDirectory) {
                    val normalizedSource = ConflictResolver.normalizePath(source.path)
                    val isSelf = destDir == normalizedSource
                    val isSubfolder = if (normalizedSource == "/") {
                        destDir.startsWith("/") && destDir != "/"
                    } else {
                        destDir.startsWith("$normalizedSource/")
                    }
                    if (isSelf || isSubfolder) {
                        throw TransferException(
                            "Tidak bisa menyalin/memindahkan folder '${source.name}' ke dirinya sendiri atau subfoldernya"
                        )
                    }
                }
            }
        }

        if (!destProvider.exists(destDir)) {
            destProvider.createDirectory(destDir).getOrElse { cause ->
                throw TransferException("Gagal membuat direktori tujuan", cause)
            }
        }

        // Phase 1: Preparation - recursively expand directories and count total files & bytes
        val plannedSources = request.sources.map { planEntry(it) }
        val totalFiles = plannedSources.sumOf { it.totalFileCount }
        val totalBytes = plannedSources.sumOf { it.totalByteSize }

        val session = ExecutionSession(
            request = request,
            sameProvider = sameProvider,
            totalFiles = totalFiles,
            totalBytes = totalBytes,
            startTimeMs = nowMillis(),
            onProgress = onProgress,
            onConflict = onConflict
        )

        for (planned in plannedSources) {
            coroutineContext.ensureActive()
            session.processEntry(
                entry = planned,
                targetDirPath = destDir,
                allowFastPathMove = true
            )
        }

        if (!session.hasEmittedProgress) {
            session.emitProgress(currentFileName = "", force = true)
        }

        TransferResult(
            filesTransferred = session.filesTransferred,
            filesSkipped = session.filesSkipped,
            bytesTransferred = session.transferredBytes,
            errors = session.errors.toList()
        )
    }

    private suspend fun planEntry(file: VfsFile): PlannedEntry {
        coroutineContext.ensureActive()
        if (!file.isDirectory) {
            return PlannedEntry(
                file = file,
                children = emptyList(),
                totalFileCount = 1,
                totalByteSize = file.size.coerceAtLeast(0L)
            )
        }
        if (file.isSymlink) {
            return PlannedEntry(
                file = file,
                children = emptyList(),
                totalFileCount = 0,
                totalByteSize = 0L
            )
        }
        val children = sourceProvider.listFiles(file.path).getOrElse { cause ->
            throw TransferException("Gagal membaca isi folder '${file.name}'", cause)
        }
        val plannedChildren = children.map { planEntry(it) }
        return PlannedEntry(
            file = file,
            children = plannedChildren,
            totalFileCount = plannedChildren.sumOf { it.totalFileCount },
            totalByteSize = plannedChildren.sumOf { it.totalByteSize }
        )
    }

    private suspend fun findExistingFile(destPath: String): VfsFile? {
        val parent = ConflictResolver.parentPath(destPath)
        val targetName = ConflictResolver.fileName(destPath)
        val normalizedDest = ConflictResolver.normalizePath(destPath)
        if (parent.isEmpty()) return null
        return destProvider.listFiles(parent).getOrNull()?.firstOrNull { item ->
            ConflictResolver.normalizePath(item.path) == normalizedDest || item.name == targetName
        }
    }

    private suspend fun verifyDestinationFile(
        destPath: String,
        expectedSize: Long,
        copiedBytes: Long
    ): Boolean {
        if (copiedBytes != expectedSize) return false
        val destFile = findExistingFile(destPath)
        if (destFile != null) {
            return !destFile.isDirectory && destFile.size == expectedSize
        }
        return destProvider.exists(destPath)
    }

    private inner class ExecutionSession(
        private val request: TransferRequest,
        private val sameProvider: Boolean,
        private val totalFiles: Int,
        private val totalBytes: Long,
        private val startTimeMs: Long,
        private val onProgress: (TransferProgress) -> Unit,
        private val onConflict: suspend (ConflictInfo) -> ConflictDecision
    ) {
        var filesTransferred: Int = 0
            private set
        var filesSkipped: Int = 0
            private set
        var transferredBytes: Long = 0L
            private set
        var hasEmittedProgress: Boolean = false
            private set
        val errors: MutableList<String> = mutableListOf()

        private var filesCompleted: Int = 0
        private var progressBytes: Long = 0L
        private var lastProgressEmitMs: Long = Long.MIN_VALUE
        private var rememberedAction: ConflictAction? = null

        suspend fun processEntry(
            entry: PlannedEntry,
            targetDirPath: String,
            allowFastPathMove: Boolean
        ): Boolean {
            coroutineContext.ensureActive()

            val source = entry.file
            val initialDestPath = ConflictResolver.joinPath(targetDirPath, source.name)

            val finalDestPath: String
            val overwrite: Boolean
            val isMergeDir: Boolean

            if (destProvider.exists(initialDestPath)) {
                val action = resolveConflictAction(source, initialDestPath)
                when (action) {
                    ConflictAction.SKIP -> {
                        filesSkipped += entry.totalFileCount
                        filesCompleted += entry.totalFileCount
                        progressBytes += entry.totalByteSize
                        emitProgress(currentFileName = source.name, force = true)
                        return false
                    }
                    ConflictAction.AUTO_RENAME -> {
                        val unique = ConflictResolver.uniqueName(
                            provider = destProvider,
                            dirPath = targetDirPath,
                            originalName = source.name,
                            isDirectory = source.isDirectory
                        )
                        finalDestPath = ConflictResolver.joinPath(targetDirPath, unique)
                        overwrite = false
                        isMergeDir = false
                    }
                    ConflictAction.OVERWRITE -> {
                        finalDestPath = initialDestPath
                        overwrite = true
                        isMergeDir = source.isDirectory
                    }
                }
            } else {
                finalDestPath = initialDestPath
                overwrite = false
                isMergeDir = false
            }

            // Guard against overwriting an entry onto itself (e.g. same parent directory)
            if (sameProvider &&
                ConflictResolver.normalizePath(finalDestPath) == ConflictResolver.normalizePath(source.path)
            ) {
                filesTransferred += entry.totalFileCount
                filesCompleted += entry.totalFileCount
                transferredBytes += entry.totalByteSize
                progressBytes += entry.totalByteSize
                emitProgress(currentFileName = source.name, force = true)
                return true
            }

            // Fast-path MOVE via provider rename when no overwrite/merge is needed
            if (request.operation == TransferOperation.MOVE &&
                sameProvider &&
                allowFastPathMove &&
                !overwrite &&
                !isMergeDir
            ) {
                val renameResult = sourceProvider.rename(source.path, finalDestPath)
                if (renameResult.isSuccess) {
                    filesTransferred += entry.totalFileCount
                    filesCompleted += entry.totalFileCount
                    transferredBytes += entry.totalByteSize
                    progressBytes += entry.totalByteSize
                    emitProgress(currentFileName = source.name, force = true)
                    return true
                }
            }

            return if (source.isDirectory) {
                processDirectory(entry, finalDestPath, isMergeDir)
            } else {
                processFile(source, finalDestPath, overwrite)
            }
        }

        private suspend fun processDirectory(
            entry: PlannedEntry,
            destDirPath: String,
            isMergeDir: Boolean
        ): Boolean {
            val source = entry.file
            if (!isMergeDir) {
                val createResult = destProvider.createDirectory(destDirPath)
                if (createResult.isFailure) {
                    val reason = createResult.exceptionOrNull()?.message ?: "kesalahan I/O"
                    errors.add("Gagal membuat folder '${source.name}': $reason")
                    return false
                }
            }

            var allChildrenSucceeded = true
            for (child in entry.children) {
                coroutineContext.ensureActive()
                val childOk = processEntry(
                    entry = child,
                    targetDirPath = destDirPath,
                    allowFastPathMove = false
                )
                if (!childOk) {
                    allChildrenSucceeded = false
                }
            }

            if (request.operation == TransferOperation.MOVE && allChildrenSucceeded) {
                val deleteResult = sourceProvider.delete(source.path, recursive = true)
                if (deleteResult.isFailure) {
                    errors.add("Gagal menghapus folder sumber '${source.name}'")
                    return false
                }
            }

            return allChildrenSucceeded
        }

        private suspend fun processFile(
            source: VfsFile,
            destFilePath: String,
            overwrite: Boolean
        ): Boolean {
            var outputOpened = false
            var copiedBytesForFile = 0L
            try {
                sourceProvider.openInputStream(source.path).use { input ->
                    destProvider.openOutputStream(destFilePath, overwrite).use { output ->
                        outputOpened = true
                        val buffer = ByteArray(bufferSize)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            coroutineContext.ensureActive()
                            output.write(buffer, 0, read)
                            val readLong = read.toLong()
                            copiedBytesForFile += readLong
                            transferredBytes += readLong
                            progressBytes += readLong
                            emitProgress(currentFileName = source.name, force = false)
                        }
                        output.flush()
                    }
                }
            } catch (ce: CancellationException) {
                if (outputOpened) {
                    withContext(NonCancellable) {
                        runCatching { destProvider.delete(destFilePath, recursive = false) }
                    }
                }
                throw ce
            } catch (e: Exception) {
                if (outputOpened) {
                    withContext(NonCancellable) {
                        runCatching { destProvider.delete(destFilePath, recursive = false) }
                    }
                }
                transferredBytes -= copiedBytesForFile
                progressBytes -= copiedBytesForFile
                errors.add("Gagal menyalin '${source.name}': ${e.message ?: "kesalahan I/O"}")
                return false
            }

            if (request.operation == TransferOperation.MOVE) {
                val verified = verifyDestinationFile(
                    destPath = destFilePath,
                    expectedSize = source.size.coerceAtLeast(0L),
                    copiedBytes = copiedBytesForFile
                )
                if (!verified) {
                    errors.add("Verifikasi ukuran file '${source.name}' gagal setelah transfer")
                    return false
                }
                val deleteResult = sourceProvider.delete(source.path, recursive = false)
                if (deleteResult.isFailure) {
                    errors.add("Gagal menghapus file sumber '${source.name}'")
                    filesTransferred++
                    filesCompleted++
                    emitProgress(currentFileName = source.name, force = true)
                    return false
                }
            }

            filesTransferred++
            filesCompleted++
            emitProgress(currentFileName = source.name, force = true)
            return true
        }

        private suspend fun resolveConflictAction(
            source: VfsFile,
            destPath: String
        ): ConflictAction {
            rememberedAction?.let { return it }
            val existingDest = findExistingFile(destPath)
            val decision = onConflict(
                ConflictInfo(
                    sourceFile = source,
                    destPath = destPath,
                    existingDest = existingDest
                )
            )
            if (decision.applyToAll) {
                rememberedAction = decision.action
            }
            return decision.action
        }

        fun emitProgress(currentFileName: String, force: Boolean) {
            val now = nowMillis()
            if (!force && lastProgressEmitMs != Long.MIN_VALUE && (now - lastProgressEmitMs) < PROGRESS_THROTTLE_MS) {
                return
            }
            lastProgressEmitMs = now
            hasEmittedProgress = true
            val elapsedMs = now - startTimeMs
            val speedBytesPerSec = if (elapsedMs > 0L) {
                (progressBytes.toDouble() * 1000.0 / elapsedMs.toDouble()).toLong()
            } else {
                0L
            }
            onProgress(
                TransferProgress(
                    jobId = request.jobId,
                    operation = request.operation,
                    currentFileName = currentFileName,
                    filesCompleted = filesCompleted,
                    totalFiles = totalFiles,
                    bytesTransferred = progressBytes,
                    totalBytes = totalBytes,
                    speedBytesPerSec = speedBytesPerSec
                )
            )
        }
    }

    private data class PlannedEntry(
        val file: VfsFile,
        val children: List<PlannedEntry>,
        val totalFileCount: Int,
        val totalByteSize: Long
    )

    companion object {
        const val DEFAULT_BUFFER_SIZE: Int = 128 * 1024
        private const val PROGRESS_THROTTLE_MS: Long = 100L
    }
}
