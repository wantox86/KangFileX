package com.wantox86.kangfilex.presentation.model

import com.wantox86.kangfilex.domain.model.VfsFile

enum class ClipboardOperation {
    COPY,
    CUT
}

data class ClipboardState(
    val items: List<VfsFile>,
    val sourceProviderId: String,
    val operation: ClipboardOperation
)

sealed interface FileOpDialogState {
    data class NewFolder(
        val parentPath: String,
        val error: String? = null
    ) : FileOpDialogState

    data class Rename(
        val file: VfsFile,
        val error: String? = null
    ) : FileOpDialogState

    data class DeleteConfirm(
        val items: List<VfsFile>,
        val hasDirectories: Boolean
    ) : FileOpDialogState
}

object FileNameValidator {
    fun validate(name: String): String? {
        if (name.contains('\u0000')) {
            return "Nama tidak boleh mengandung karakter NUL"
        }
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "Nama tidak boleh kosong"
            trimmed.contains('/') -> "Nama tidak boleh mengandung karakter '/'"
            trimmed == "." || trimmed == ".." -> "Nama tidak boleh '.' atau '..'"
            else -> null
        }
    }
}
