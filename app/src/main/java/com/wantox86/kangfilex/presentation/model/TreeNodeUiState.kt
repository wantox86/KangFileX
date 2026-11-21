package com.wantox86.kangfilex.presentation.model

import com.wantox86.kangfilex.domain.model.VfsFile

data class TreeNodeUiState(
    val id: String,
    val file: VfsFile,
    val depth: Int,
    val isExpanded: Boolean = false,
    val isLoadingChildren: Boolean = false,
    val isSelected: Boolean = false,
    val isFocusedTarget: Boolean = false
)
