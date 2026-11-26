package com.wantox86.kangfilex.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wantox86.kangfilex.data.local.LocalVfsProvider
import com.wantox86.kangfilex.data.local.StorageVolumeProvider
import com.wantox86.kangfilex.data.transfer.TransferOutcome
import com.wantox86.kangfilex.data.transfer.TransferQueueManager
import com.wantox86.kangfilex.data.transfer.TransferUiState
import com.wantox86.kangfilex.domain.transfer.ConflictDecision
import com.wantox86.kangfilex.domain.transfer.TransferOperation
import com.wantox86.kangfilex.domain.transfer.TransferRequest
import com.wantox86.kangfilex.domain.vfs.VfsProvider
import com.wantox86.kangfilex.presentation.model.BreadcrumbSegment
import com.wantox86.kangfilex.presentation.model.ClipboardOperation
import com.wantox86.kangfilex.presentation.model.ClipboardState
import com.wantox86.kangfilex.presentation.model.FileNameValidator
import com.wantox86.kangfilex.presentation.model.FileOpDialogState
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
import java.util.UUID

sealed interface UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent
    data class ScrollToNode(val paneId: PaneId, val index: Int) : UiEvent
}

class DualPaneViewModel(
    private val vfsProvider: VfsProvider,
    private val storageVolumeProvider: StorageVolumeProvider,
    private val transferQueueManager: TransferQueueManager = TransferQueueManager.getInstance(),
    private val startTransferService: () -> Unit = {},
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val leftTree = TreeStateManager(vfsProvider.providerId)
    private val rightTree = TreeStateManager(vfsProvider.providerId)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val transferState: StateFlow<TransferUiState> = transferQueueManager.state

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var initializedAfterGrant = false

    init {
        viewModelScope.launch(mainDispatcher) {
            transferQueueManager.outcomes.collect { outcome ->
                handleTransferOutcome(outcome)
            }
        }
    }

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

    fun onAboutClick() {
        _events.tryEmit(
            UiEvent.ShowSnackbar("KangFileX Phase 2: copy, move, rename, delete aktif")
        )
    }

    fun onCopyClick() {
        enqueuePaneTransfer(TransferOperation.COPY)
    }

    fun onMoveClick() {
        enqueuePaneTransfer(TransferOperation.MOVE)
    }

    fun onPasteClick() {
        val clipboard = _uiState.value.clipboard
        if (clipboard == null || clipboard.items.isEmpty()) {
            _events.tryEmit(UiEvent.ShowSnackbar("Clipboard kosong"))
            return
        }
        if (clipboard.sourceProviderId != vfsProvider.providerId) {
            _events.tryEmit(UiEvent.ShowSnackbar("Provider berbeda belum didukung"))
            return
        }
        val destDirPath = treeFor(_uiState.value.activePane).getFocusedPath()
        if (destDirPath.isNullOrBlank()) {
            _events.tryEmit(UiEvent.ShowSnackbar("Pilih folder tujuan dulu"))
            return
        }
        val operation = when (clipboard.operation) {
            ClipboardOperation.COPY -> TransferOperation.COPY
            ClipboardOperation.CUT -> TransferOperation.MOVE
        }
        val request = TransferRequest(
            jobId = UUID.randomUUID().toString(),
            operation = operation,
            sources = clipboard.items,
            destDirPath = destDirPath
        )
        transferQueueManager.enqueue(request)
        if (clipboard.operation == ClipboardOperation.CUT) {
            _uiState.update { it.copy(clipboard = null) }
        }
        startTransferService()
        _events.tryEmit(UiEvent.ShowSnackbar("Transfer dimulai"))
    }

    fun onTransferMinimize() {
        transferQueueManager.setProgressDialogVisible(false)
    }

    fun onTransferShowProgress() {
        transferQueueManager.setProgressDialogVisible(true)
    }

    fun onTransferCancel() {
        transferQueueManager.cancelActive()
    }

    fun onResolveConflict(decision: ConflictDecision) {
        transferQueueManager.resolveConflict(decision)
    }

    fun onNewFolderClick() {
        val focusedPath = treeFor(_uiState.value.activePane).getFocusedPath()
        if (focusedPath == null) {
            _events.tryEmit(UiEvent.ShowSnackbar("Pilih folder tujuan dulu"))
            return
        }
        _uiState.update {
            it.copy(fileOpDialog = FileOpDialogState.NewFolder(parentPath = focusedPath))
        }
    }

    fun onNewFolderConfirm(name: String) {
        val dialog = _uiState.value.fileOpDialog as? FileOpDialogState.NewFolder ?: return
        val validationError = FileNameValidator.validate(name)
        if (validationError != null) {
            _uiState.update { it.copy(fileOpDialog = dialog.copy(error = validationError)) }
            return
        }

        val trimmedName = name.trim()
        val targetPath = joinPath(dialog.parentPath, trimmedName)

        viewModelScope.launch(mainDispatcher) {
            val alreadyExists = withContext(ioDispatcher) { vfsProvider.exists(targetPath) }
            if (alreadyExists) {
                _uiState.update {
                    it.copy(fileOpDialog = dialog.copy(error = "Folder '$trimmedName' sudah ada"))
                }
                return@launch
            }

            val result = withContext(ioDispatcher) { vfsProvider.createDirectory(targetPath) }
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(fileOpDialog = null) }
                    _events.tryEmit(UiEvent.ShowSnackbar("Folder '$trimmedName' berhasil dibuat"))
                    refreshBothPanesInternal()
                },
                onFailure = { error ->
                    val message = error.message ?: "Gagal membuat folder"
                    _uiState.update { it.copy(fileOpDialog = dialog.copy(error = message)) }
                }
            )
        }
    }

    fun onRenameClick() {
        val selectedFiles = treeFor(_uiState.value.activePane).getSelectedFiles()
        if (selectedFiles.size != 1) {
            _events.tryEmit(UiEvent.ShowSnackbar("Pilih tepat 1 item untuk rename"))
            return
        }
        _uiState.update {
            it.copy(fileOpDialog = FileOpDialogState.Rename(file = selectedFiles.first()))
        }
    }

    fun onRenameConfirm(newName: String) {
        val dialog = _uiState.value.fileOpDialog as? FileOpDialogState.Rename ?: return
        val validationError = FileNameValidator.validate(newName)
        if (validationError != null) {
            _uiState.update { it.copy(fileOpDialog = dialog.copy(error = validationError)) }
            return
        }

        val trimmedName = newName.trim()
        if (trimmedName == dialog.file.name) {
            _uiState.update { it.copy(fileOpDialog = null) }
            return
        }

        val parentPath = parentPathOf(dialog.file.path)
        val targetPath = joinPath(parentPath, trimmedName)
        val activePane = _uiState.value.activePane
        val isCaseOnlyRename = trimmedName.equals(dialog.file.name, ignoreCase = true)

        viewModelScope.launch(mainDispatcher) {
            if (!isCaseOnlyRename) {
                val alreadyExists = withContext(ioDispatcher) { vfsProvider.exists(targetPath) }
                if (alreadyExists) {
                    _uiState.update {
                        it.copy(fileOpDialog = dialog.copy(error = "'$trimmedName' sudah ada"))
                    }
                    return@launch
                }
            }

            val result = withContext(ioDispatcher) {
                vfsProvider.rename(dialog.file.path, targetPath)
            }
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(fileOpDialog = null) }
                    treeFor(activePane).clearSelection()
                    refreshBothPanesInternal()
                    _events.tryEmit(UiEvent.ShowSnackbar("Berhasil diubah menjadi '$trimmedName'"))
                },
                onFailure = { error ->
                    val message = error.message ?: "Gagal mengubah nama"
                    _uiState.update { it.copy(fileOpDialog = dialog.copy(error = message)) }
                }
            )
        }
    }

    fun onDeleteClick() {
        val selectedFiles = treeFor(_uiState.value.activePane).getSelectedFiles()
        if (selectedFiles.isEmpty()) {
            _events.tryEmit(UiEvent.ShowSnackbar("Pilih minimal 1 item untuk dihapus"))
            return
        }
        _uiState.update {
            it.copy(
                fileOpDialog = FileOpDialogState.DeleteConfirm(
                    items = selectedFiles,
                    hasDirectories = selectedFiles.any { file -> file.isDirectory }
                )
            )
        }
    }

    fun onDeleteConfirm() {
        val dialog = _uiState.value.fileOpDialog as? FileOpDialogState.DeleteConfirm ?: return
        val activePane = _uiState.value.activePane

        viewModelScope.launch(mainDispatcher) {
            var deletedCount = 0
            var failedCount = 0
            for (item in dialog.items) {
                val result = withContext(ioDispatcher) {
                    vfsProvider.delete(item.path, recursive = item.isDirectory)
                }
                if (result.isSuccess) {
                    deletedCount++
                } else {
                    failedCount++
                }
            }
            _uiState.update { it.copy(fileOpDialog = null) }
            treeFor(activePane).clearSelection()
            refreshBothPanesInternal()
            val message = if (failedCount == 0) {
                "$deletedCount item dihapus"
            } else {
                "$deletedCount dihapus, $failedCount gagal"
            }
            _events.tryEmit(UiEvent.ShowSnackbar(message))
        }
    }

    fun onFileOpDialogDismiss() {
        _uiState.update { it.copy(fileOpDialog = null) }
    }

    fun onClipClick() {
        val selectedFiles = treeFor(_uiState.value.activePane).getSelectedFiles()
        if (selectedFiles.isEmpty()) {
            _events.tryEmit(UiEvent.ShowSnackbar("Pilih minimal 1 item untuk disalin ke clipboard"))
            return
        }
        _uiState.update {
            it.copy(
                clipboard = ClipboardState(
                    items = selectedFiles,
                    sourceProviderId = vfsProvider.providerId,
                    operation = ClipboardOperation.COPY
                )
            )
        }
        _events.tryEmit(UiEvent.ShowSnackbar("${selectedFiles.size} item disalin ke clipboard"))
    }

    fun onClearClipboard() {
        _uiState.update { it.copy(clipboard = null) }
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

    private fun enqueuePaneTransfer(operation: TransferOperation) {
        val activePane = _uiState.value.activePane
        val selectedFiles = treeFor(activePane).getSelectedFiles()
        if (selectedFiles.isEmpty()) {
            _events.tryEmit(UiEvent.ShowSnackbar("Pilih item dulu"))
            return
        }
        val destDirPath = treeFor(activePane.opposite()).getFocusedPath()
        if (destDirPath.isNullOrBlank()) {
            _events.tryEmit(UiEvent.ShowSnackbar("Pilih folder tujuan di pane sebelah"))
            return
        }
        val request = TransferRequest(
            jobId = UUID.randomUUID().toString(),
            operation = operation,
            sources = selectedFiles,
            destDirPath = destDirPath
        )
        transferQueueManager.enqueue(request)
        treeFor(activePane).clearSelection()
        syncPane(activePane)
        startTransferService()
        _events.tryEmit(UiEvent.ShowSnackbar("Transfer dimulai"))
    }

    private suspend fun handleTransferOutcome(outcome: TransferOutcome) {
        leftTree.clearSelection()
        rightTree.clearSelection()
        if (_uiState.value.hasStoragePermission) {
            refreshBothPanesInternal()
        } else {
            syncBothPanes()
        }
        val message = when (outcome) {
            is TransferOutcome.Completed -> {
                val r = outcome.result
                val base = if (r.filesSkipped > 0) {
                    "${r.filesTransferred} file selesai (${r.filesSkipped} dilewati)"
                } else {
                    "${r.filesTransferred} file selesai"
                }
                if (r.errors.isNotEmpty()) {
                    "$base, ${r.errors.size} gagal"
                } else {
                    base
                }
            }
            is TransferOutcome.Failed -> outcome.message
            is TransferOutcome.Cancelled -> "Transfer dibatalkan"
        }
        _events.tryEmit(UiEvent.ShowSnackbar(message))
    }

    private suspend fun refreshBothPanesInternal() {
        refreshPaneInternal(PaneId.LEFT)
        refreshPaneInternal(PaneId.RIGHT)
    }

    private fun joinPath(parent: String, childName: String): String {
        val normalizedParent = if (parent == "/") "" else parent.trimEnd('/')
        return "$normalizedParent/$childName"
    }

    private fun parentPathOf(path: String): String {
        val normalized = if (path == "/") "/" else path.trimEnd('/')
        val lastSlash = normalized.lastIndexOf('/')
        return when {
            lastSlash < 0 -> ""
            lastSlash == 0 -> "/"
            else -> normalized.substring(0, lastSlash)
        }
    }

    private suspend fun refreshPaneInternal(paneId: PaneId) {
        val tree = treeFor(paneId)
        val volumes = withContext(ioDispatcher) { storageVolumeProvider.getVolumes() }
        tree.setVolumes(volumes)
        tree.invalidateCollapsedChildren()
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

        val focusedPath = tree.getFocusedPath()
        if (focusedPath != null) {
            val focusedId = tree.nodeId(focusedPath)
            if (!tree.isExpanded(focusedId) && tree.hasLoadedChildren(focusedId)) {
                loadChildrenInternal(paneId, focusedId, focusedPath)
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
        private val vfsProvider: VfsProvider = LocalVfsProvider(),
        private val transferQueueManager: TransferQueueManager = TransferQueueManager.getInstance(),
        private val startTransferService: () -> Unit = {}
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DualPaneViewModel(
                vfsProvider = vfsProvider,
                storageVolumeProvider = storageVolumeProvider,
                transferQueueManager = transferQueueManager,
                startTransferService = startTransferService
            ) as T
        }
    }
}
