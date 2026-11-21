package com.wantox86.kangfilex.presentation.model

import com.wantox86.kangfilex.domain.model.StorageVolumeInfo

data class PaneUiState(
    val paneId: PaneId,
    val storageVolumes: List<StorageVolumeInfo> = emptyList(),
    val activeVolume: StorageVolumeInfo? = null,
    val focusedPath: String? = null,
    val breadcrumbs: List<BreadcrumbSegment> = emptyList(),
    val nodes: List<TreeNodeUiState> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val focusedDirChildCount: Pair<Int, Int>? = null,
    val isAllInFocusedDirSelected: Boolean = false,
    val isClearActionMode: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val selectedCount: Int get() = selectedIds.size
    val hasSelection: Boolean get() = selectedIds.isNotEmpty()
}

data class MainUiState(
    val leftPane: PaneUiState = PaneUiState(PaneId.LEFT),
    val rightPane: PaneUiState = PaneUiState(PaneId.RIGHT),
    val activePane: PaneId = PaneId.LEFT,
    val hasStoragePermission: Boolean = false,
    val hasClipboardItems: Boolean = false
) {
    fun pane(id: PaneId): PaneUiState = when (id) {
        PaneId.LEFT -> leftPane
        PaneId.RIGHT -> rightPane
    }

    val activePaneState: PaneUiState get() = pane(activePane)
}
