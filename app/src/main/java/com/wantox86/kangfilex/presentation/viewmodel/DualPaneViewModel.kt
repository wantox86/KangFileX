package com.wantox86.kangfilex.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wantox86.kangfilex.data.local.LocalVfsProvider
import com.wantox86.kangfilex.data.local.StorageVolumeProvider
import com.wantox86.kangfilex.domain.vfs.VfsProvider
import com.wantox86.kangfilex.presentation.model.BreadcrumbSegment
import com.wantox86.kangfilex.presentation.model.MainUiState
import com.wantox86.kangfilex.presentation.model.PaneId
import com.wantox86.kangfilex.presentation.model.PaneUiState
import com.wantox86.kangfilex.presentation.model.RootNetworkSection
import com.wantox86.kangfilex.presentation.model.TreeNodeUiState
import com.wantox86.kangfilex.presentation.tree.TreeStateManager
import com.wantox86.kangfilex.util.Formatters
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent
    data class ScrollToNode(val paneId: PaneId, val index: Int) : UiEvent
}

class DualPaneViewModel(
    private val vfsProvider: VfsProvider,
    private val storageVolumeProvider: StorageVolumeProvider,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val leftTree = TreeStateManager(vfsProvider.providerId)
    private val rightTree = TreeStateManager(vfsProvider.providerId)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var initializedAfterGrant = false

    fun onPermissionChanged(granted: Boolean) {
        val previouslyGranted = _uiState.value.hasStoragePermission
        _uiState.update { it.copy(hasStoragePermission = granted) }

        if (granted && (!previouslyGranted || !initializedAfterGrant)) {
            initializedAfterGrant = true
            loadInitialStorage()
        } else if (!granted) {
            viewModelScope.launch(mainDispatcher) {
                val volumes = withContext(ioDispatcher) { storageVolumeProvider.getVolumes() }
                leftTree.setVolumes(volumes)
                rightTree.setVolumes(volumes)
                syncBothPanes()
            }
        }
    }

    fun setActivePane(paneId: PaneId) {
        if (_uiState.value.activePane != paneId) {
            _uiState.update { it.copy(activePane = paneId) }
        }
    }

    fun toggleActivePane() {
        _uiState.update { it.copy(activePane = it.activePane.opposite()) }
    }

    fun onNodeClick(paneId: PaneId, node: TreeNodeUiState) {
        setActivePane(paneId)
        val tree = treeFor(paneId)

        if (!node.file.isDirectory) {
            File(node.file.path).parent?.let { parent ->
                tree.setFocusedTarget(parent)
                syncPane(paneId)
            }
            val sizeText = Formatters.formatFileSize(node.file.size)
            _events.tryEmit(UiEvent.ShowSnackbar("${node.file.name} ($sizeText)"))
            return
        }

        tree.setFocusedTarget(node.file.path)
        if (tree.isExpanded(node.id)) {
            tree.collapse(node.id)
            syncPane(paneId)
        } else {
            tree.setExpanded(node.id, true)
            if (tree.hasLoadedChildren(node.id)) {
                syncPane(paneId)
            } else {
                loadChildren(paneId, node.id, node.file.path)
            }
        }
    }

    fun onNodeCheckboxToggle(paneId: PaneId, nodeId: String) {
        setActivePane(paneId)
        val tree = treeFor(paneId)
        tree.toggleSelection(nodeId)
        syncPane(paneId)
    }

    fun onBreadcrumbClick(paneId: PaneId, segment: BreadcrumbSegment) {
        setActivePane(paneId)
        val tree = treeFor(paneId)
        tree.setFocusedTarget(segment.path)
        val nodeId = tree.nodeId(segment.path)
        if (!tree.isExpanded(nodeId)) {
            tree.setExpanded(nodeId, true)
            if (!tree.hasLoadedChildren(nodeId)) {
                loadChildren(paneId, nodeId, segment.path)
            }
        }
        syncPane(paneId)
        val index = tree.findNodeIndexByPath(segment.path)
        if (index >= 0) {
            _events.tryEmit(UiEvent.ScrollToNode(paneId, index))
        }
    }

    fun onUpDir(paneId: PaneId = _uiState.value.activePane) {
        val tree = treeFor(paneId)
        val newTarget = tree.navigateUp()
        syncPane(paneId)
        if (newTarget != null) {
            val index = tree.findNodeIndexByPath(newTarget)
            if (index >= 0) {
                _events.tryEmit(UiEvent.ScrollToNode(paneId, index))
            }
        }
    }

    fun onSelectAllOrClear(paneId: PaneId = _uiState.value.activePane) {
        val tree = treeFor(paneId)
        tree.toggleSelectAllOrClear()
        syncPane(paneId)
    }

    fun onCollapseAll(paneId: PaneId = _uiState.value.activePane) {
        treeFor(paneId).collapseAll()
        syncPane(paneId)
    }

    fun onRefresh(paneId: PaneId = _uiState.value.activePane) {
        if (!_uiState.value.hasStoragePermission) {
            _events.tryEmit(UiEvent.ShowSnackbar("Izin akses penyimpanan belum diberikan"))
            return
        }
        viewModelScope.launch(mainDispatcher) {
            refreshPaneInternal(paneId)
        }
    }

    fun onRefreshBoth() {
        if (!_uiState.value.hasStoragePermission) {
            _events.tryEmit(UiEvent.ShowSnackbar("Izin akses penyimpanan belum diberikan"))
            return
        }
        viewModelScope.launch(mainDispatcher) {
            refreshPaneInternal(PaneId.LEFT)
            refreshPaneInternal(PaneId.RIGHT)
        }
    }

    fun onPhase2ActionClick(actionLabel: String) {
        _events.tryEmit(UiEvent.ShowSnackbar("$actionLabel akan diaktifkan di Phase 2"))
    }

    fun onNetworkSectionClick(section: RootNetworkSection) {
        _events.tryEmit(
            UiEvent.ShowSnackbar("${section.title} akan diaktifkan di ${section.targetPhase}")
        )
    }

    private fun loadInitialStorage() {
        viewModelScope.launch(mainDispatcher) {
            vfsProvider.connect()
            val volumes = withContext(ioDispatcher) { storageVolumeProvider.getVolumes() }
            leftTree.setVolumes(volumes)
            rightTree.setVolumes(volumes)

            val primaryRoot = volumes.firstOrNull()?.rootPath
            if (primaryRoot != null) {
                val leftRootId = leftTree.nodeId(primaryRoot)
                val rightRootId = rightTree.nodeId(primaryRoot)
                leftTree.setFocusedTarget(primaryRoot)
                rightTree.setFocusedTarget(primaryRoot)
                leftTree.setExpanded(leftRootId, true)
                rightTree.setExpanded(rightRootId, true)
                syncBothPanes()

                loadChildrenInternal(PaneId.LEFT, leftRootId, primaryRoot)
                loadChildrenInternal(PaneId.RIGHT, rightRootId, primaryRoot)
            } else {
                syncBothPanes()
            }
        }
    }

    private suspend fun refreshPaneInternal(paneId: PaneId) {
        val tree = treeFor(paneId)
        val volumes = withContext(ioDispatcher) { storageVolumeProvider.getVolumes() }
        tree.setVolumes(volumes)
        updatePane(paneId) { it.copy(isLoading = true, errorMessage = null) }

        val expandedPaths = tree.getExpandedPaths()
        if (expandedPaths.isEmpty()) {
            val rootPath = volumes.firstOrNull()?.rootPath
            if (rootPath != null) {
                val rootId = tree.nodeId(rootPath)
                tree.setExpanded(rootId, true)
                loadChildrenInternal(paneId, rootId, rootPath)
            }
        } else {
            for (path in expandedPaths) {
                loadChildrenInternal(paneId, tree.nodeId(path), path)
            }
        }
        updatePane(paneId) { it.copy(isLoading = false) }
        syncPane(paneId)
    }

    private fun loadChildren(paneId: PaneId, nodeId: String, path: String) {
        viewModelScope.launch(mainDispatcher) {
            loadChildrenInternal(paneId, nodeId, path)
        }
    }

    private suspend fun loadChildrenInternal(paneId: PaneId, nodeId: String, path: String) {
        val tree = treeFor(paneId)
        tree.setLoading(nodeId, true)
        syncPane(paneId)

        val result = vfsProvider.listFiles(path)
        result.fold(
            onSuccess = { files ->
                tree.setChildren(nodeId, files)
                updatePane(paneId) { it.copy(errorMessage = null) }
                syncPane(paneId)
            },
            onFailure = { error ->
                tree.setLoading(nodeId, false)
                tree.collapse(nodeId)
                val message = error.message ?: "Gagal membaca folder"
                updatePane(paneId) { it.copy(errorMessage = message) }
                syncPane(paneId)
                _events.tryEmit(UiEvent.ShowSnackbar(message))
            }
        )
    }

    private fun treeFor(paneId: PaneId): TreeStateManager = when (paneId) {
        PaneId.LEFT -> leftTree
        PaneId.RIGHT -> rightTree
    }

    private fun syncBothPanes() {
        syncPane(PaneId.LEFT)
        syncPane(PaneId.RIGHT)
    }

    private fun syncPane(paneId: PaneId) {
        val tree = treeFor(paneId)
        val focusedPath = tree.getFocusedPath()
        val activeVolume = tree.findVolumeForPath(focusedPath)
        val flattened = tree.flatten()
        val breadcrumbs = tree.buildBreadcrumbs()
        val selected = tree.getSelectedIds()
        val childCount = tree.getFocusedDirChildCount()
        val allSelected = tree.isAllSelectedInFocusedDir()
        val clearActionMode = tree.isClearActionMode()

        updatePane(paneId) { current ->
            current.copy(
                storageVolumes = tree.getVolumes(),
                activeVolume = activeVolume,
                focusedPath = focusedPath,
                breadcrumbs = breadcrumbs,
                nodes = flattened,
                selectedIds = selected,
                focusedDirChildCount = childCount,
                isAllInFocusedDirSelected = allSelected,
                isClearActionMode = clearActionMode
            )
        }
    }

    private fun updatePane(paneId: PaneId, transform: (PaneUiState) -> PaneUiState) {
        _uiState.update { state ->
            when (paneId) {
                PaneId.LEFT -> state.copy(leftPane = transform(state.leftPane))
                PaneId.RIGHT -> state.copy(rightPane = transform(state.rightPane))
            }
        }
    }

    class Factory(
        private val storageVolumeProvider: StorageVolumeProvider,
        private val vfsProvider: VfsProvider = LocalVfsProvider()
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DualPaneViewModel(
                vfsProvider = vfsProvider,
                storageVolumeProvider = storageVolumeProvider
            ) as T
        }
    }
}
