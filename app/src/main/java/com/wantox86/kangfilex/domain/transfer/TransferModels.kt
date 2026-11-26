package com.wantox86.kangfilex.domain.transfer

import com.wantox86.kangfilex.domain.model.VfsFile

enum class TransferOperation {
    COPY,
    MOVE
}

enum class ConflictAction {
    OVERWRITE,
    SKIP,
    AUTO_RENAME
}

data class ConflictDecision(
    val action: ConflictAction,
    val applyToAll: Boolean = false
)

data class ConflictInfo(
    val sourceFile: VfsFile,
    val destPath: String,
    val existingDest: VfsFile?
)

data class TransferProgress(
    val jobId: String,
    val operation: TransferOperation,
    val currentFileName: String,
    val filesCompleted: Int,
    val totalFiles: Int,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long
)

data class TransferRequest(
    val jobId: String,
    val operation: TransferOperation,
    val sources: List<VfsFile>,
    val destDirPath: String
)

data class TransferResult(
    val filesTransferred: Int,
    val filesSkipped: Int,
    val bytesTransferred: Long,
    val errors: List<String>
)

class TransferException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
