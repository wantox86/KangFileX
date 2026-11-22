package com.wantox86.kangfilex.presentation.viewmodel

import com.wantox86.kangfilex.data.local.StorageVolumeProvider
import com.wantox86.kangfilex.domain.model.StorageVolumeInfo
import com.wantox86.kangfilex.domain.model.VfsFile
import com.wantox86.kangfilex.domain.vfs.VfsProvider
import com.wantox86.kangfilex.presentation.model.ClipboardOperation
import com.wantox86.kangfilex.presentation.model.FileOpDialogState
import com.wantox86.kangfilex.presentation.model.PaneId
import com.wantox86.kangfilex.presentation.model.RootNetworkSection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class DualPaneViewModelTest {

    private val volume = StorageVolumeInfo(
        id = "local_internal",
        label = "Internal Storage",
        rootPath = "/storage/emulated/0",
        totalBytes = 100_000L,
        usedBytes = 50_000L,
        availableBytes = 50_000L
    )

    private class FakeVfsProvider(
        private val tree: Map<String, List<VfsFile>>
    ) : VfsProvider {
        val createdDirectories = mutableListOf<String>()
        val renamedPaths = mutableListOf<Pair<String, String>>()
        val deletedPaths = mutableListOf<Pair<String, Boolean>>()
        val existingPaths = mutableSetOf<String>().apply {
            addAll(tree.keys)
            tree.values.forEach { list -> list.forEach { add(it.path) } }
        }

        override val providerId: String = "local"
        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
        override suspend fun isConnected(): Boolean = true
        override suspend fun listFiles(path: String): Result<List<VfsFile>> =
            Result.success(tree[path].orEmpty())
        override suspend fun openInputStream(path: String): InputStream = ByteArrayInputStream(ByteArray(0))
        override suspend fun openOutputStream(path: String, overwrite: Boolean): OutputStream = ByteArrayOutputStream()
        override suspend fun createDirectory(path: String): Result<Unit> {
            createdDirectories.add(path)
            existingPaths.add(path)
            return Result.success(Unit)
        }
        override suspend fun delete(path: String, recursive: Boolean): Result<Unit> {
            deletedPaths.add(path to recursive)
            existingPaths.remove(path)
            return Result.success(Unit)
        }
        override suspend fun rename(oldPath: String, newPath: String): Result<Unit> {
            renamedPaths.add(oldPath to newPath)
            existingPaths.remove(oldPath)
            existingPaths.add(newPath)
            return Result.success(Unit)
        }
        override suspend fun exists(path: String): Boolean = existingPaths.contains(path)
    }

    private fun createViewModelWithProvider(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): Pair<DualPaneViewModel, FakeVfsProvider> {
        val root = volume.rootPath
        val download = "$root/Download"
        val provider = FakeVfsProvider(
            mapOf(
                root to listOf(
                    VfsFile("Download", download, true, 0L, 0L, "local", childDirCount = 0, childFileCount = 1),
                    VfsFile("notes.txt", "$root/notes.txt", false, 512L, 1000L, "local")
                ),
                download to listOf(
                    VfsFile("app.apk", "$download/app.apk", false, 2048L, 2000L, "local")
                )
            )
        )
        val volumeProvider = object : StorageVolumeProvider {
            override fun getVolumes(): List<StorageVolumeInfo> = listOf(volume)
        }
        val vm = DualPaneViewModel(
            vfsProvider = provider,
            storageVolumeProvider = volumeProvider,
            mainDispatcher = dispatcher,
            ioDispatcher = dispatcher
        )
        return vm to provider
    }

    private fun createViewModel(dispatcher: kotlinx.coroutines.CoroutineDispatcher): DualPaneViewModel =
        createViewModelWithProvider(dispatcher).first

    @Test
    fun permissionGrantLoadsBothPanesAndSupportsExpandSelectAndUpDir() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = createViewModel(dispatcher)

        vm.onPermissionChanged(true)
        advanceUntilIdle()

        val initialState = vm.uiState.value
        assertTrue(initialState.hasStoragePermission)
        assertEquals(PaneId.LEFT, initialState.activePane)
        assertEquals(
            listOf("Internal Storage", "Download", "notes.txt"),
            initialState.leftPane.nodes.map { it.file.name }
        )

        // Expand Download inline on Left Pane
        val downloadNode = initialState.leftPane.nodes.first { it.file.name == "Download" }
        vm.onNodeClick(PaneId.LEFT, downloadNode)
        advanceUntilIdle()

        val expandedState = vm.uiState.value.leftPane
        assertEquals(
            listOf("Internal Storage", "Download", "app.apk", "notes.txt"),
            expandedState.nodes.map { it.file.name }
        )
        assertEquals("/storage/emulated/0/Download", expandedState.focusedPath)
        assertEquals(listOf("Internal Storage", "Download"), expandedState.breadcrumbs.map { it.label })

        // Select All in focused dir (Download -> app.apk)
        assertFalse(vm.uiState.value.leftPane.isClearActionMode)
        vm.onSelectAllOrClear(PaneId.LEFT)
        assertEquals(1, vm.uiState.value.leftPane.selectedCount)
        assertTrue(vm.uiState.value.leftPane.isAllInFocusedDirSelected)
        assertTrue(vm.uiState.value.leftPane.isClearActionMode)

        // Clear selection
        vm.onSelectAllOrClear(PaneId.LEFT)
        assertEquals(0, vm.uiState.value.leftPane.selectedCount)
        assertFalse(vm.uiState.value.leftPane.isClearActionMode)

        // Up Dir collapses Download and returns focus to Internal Storage
        vm.onUpDir(PaneId.LEFT)
        assertEquals("/storage/emulated/0", vm.uiState.value.leftPane.focusedPath)
        assertFalse(vm.uiState.value.leftPane.nodes.any { it.file.name == "app.apk" })
    }

    @Test
    fun switchActivePaneAndEmitPhaseEvents() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = createViewModel(dispatcher)

        assertEquals(PaneId.LEFT, vm.uiState.value.activePane)
        vm.toggleActivePane()
        assertEquals(PaneId.RIGHT, vm.uiState.value.activePane)
        vm.setActivePane(PaneId.LEFT)
        assertEquals(PaneId.LEFT, vm.uiState.value.activePane)

        var received: UiEvent? = null
        val job = launch(dispatcher) {
            received = vm.events.first()
        }
        advanceUntilIdle()
        vm.onPhase2ActionClick("Copy")
        advanceUntilIdle()
        job.join()

        assertTrue(received is UiEvent.ShowSnackbar)
        assertTrue((received as UiEvent.ShowSnackbar).message.contains("Phase 2"))

        var netEvent: UiEvent? = null
        val netJob = launch(dispatcher) {
            netEvent = vm.events.first()
        }
        advanceUntilIdle()
        vm.onNetworkSectionClick(RootNetworkSection.SMB)
        advanceUntilIdle()
        netJob.join()

        assertTrue(netEvent is UiEvent.ShowSnackbar)
        assertTrue((netEvent as UiEvent.ShowSnackbar).message.contains("Phase 3"))
    }

    @Test
    fun newFolderValidatesNamesAndCreatesDirectory() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (vm, provider) = createViewModelWithProvider(dispatcher)

        vm.onPermissionChanged(true)
        advanceUntilIdle()

        vm.onNewFolderClick()
        val initialDialog = vm.uiState.value.fileOpDialog
        assertTrue(initialDialog is FileOpDialogState.NewFolder)
        assertEquals("/storage/emulated/0", (initialDialog as FileOpDialogState.NewFolder).parentPath)
        assertNull(initialDialog.error)

        // Invalid names rejected without calling provider
        for (invalid in listOf("", "   ", "a/b", ".", "..")) {
            vm.onNewFolderConfirm(invalid)
            advanceUntilIdle()
            val dialog = vm.uiState.value.fileOpDialog as? FileOpDialogState.NewFolder
            assertNotNull("Dialog should stay open for invalid name '$invalid'", dialog)
            assertNotNull("Dialog should show error for invalid name '$invalid'", dialog?.error)
        }
        assertTrue(provider.createdDirectories.isEmpty())

        // Existing target name rejected inline
        vm.onNewFolderConfirm("Download")
        advanceUntilIdle()
        val existsDialog = vm.uiState.value.fileOpDialog as? FileOpDialogState.NewFolder
        assertNotNull(existsDialog)
        assertNotNull(existsDialog?.error)
        assertTrue(provider.createdDirectories.isEmpty())

        // Valid name creates directory and closes dialog
        vm.onNewFolderConfirm("sub_test")
        advanceUntilIdle()
        assertEquals(listOf("/storage/emulated/0/sub_test"), provider.createdDirectories)
        assertNull(vm.uiState.value.fileOpDialog)
    }

    @Test
    fun renameRequiresSingleSelectionValidatesAndRenames() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (vm, provider) = createViewModelWithProvider(dispatcher)

        vm.onPermissionChanged(true)
        advanceUntilIdle()

        // 0 items selected -> snackbar, no dialog
        var zeroEvent: UiEvent? = null
        val zeroJob = launch(dispatcher) { zeroEvent = vm.events.first() }
        advanceUntilIdle()
        vm.onRenameClick()
        advanceUntilIdle()
        zeroJob.join()
        assertTrue(zeroEvent is UiEvent.ShowSnackbar)
        assertNull(vm.uiState.value.fileOpDialog)

        // >1 items selected -> snackbar, no dialog
        val downloadNode = vm.uiState.value.leftPane.nodes.first { it.file.name == "Download" }
        val notesNode = vm.uiState.value.leftPane.nodes.first { it.file.name == "notes.txt" }
        vm.onNodeCheckboxToggle(PaneId.LEFT, downloadNode.id)
        vm.onNodeCheckboxToggle(PaneId.LEFT, notesNode.id)

        var multiEvent: UiEvent? = null
        val multiJob = launch(dispatcher) { multiEvent = vm.events.first() }
        advanceUntilIdle()
        vm.onRenameClick()
        advanceUntilIdle()
        multiJob.join()
        assertTrue(multiEvent is UiEvent.ShowSnackbar)
        assertNull(vm.uiState.value.fileOpDialog)

        // Exactly 1 item selected -> opens Rename dialog
        vm.onNodeCheckboxToggle(PaneId.LEFT, downloadNode.id) // unselect Download, keep notes.txt
        vm.onRenameClick()
        val renameDialog = vm.uiState.value.fileOpDialog as? FileOpDialogState.Rename
        assertNotNull(renameDialog)
        assertEquals("notes.txt", renameDialog?.file?.name)

        // Invalid names rejected
        for (invalid in listOf("", "a/b", "..")) {
            vm.onRenameConfirm(invalid)
            advanceUntilIdle()
            val dialog = vm.uiState.value.fileOpDialog as? FileOpDialogState.Rename
            assertNotNull(dialog)
            assertNotNull(dialog?.error)
        }
        assertTrue(provider.renamedPaths.isEmpty())

        // Same name closes dialog without provider call
        vm.onRenameConfirm("notes.txt")
        advanceUntilIdle()
        assertNull(vm.uiState.value.fileOpDialog)
        assertTrue(provider.renamedPaths.isEmpty())

        // Reopen and rename to valid new name
        vm.onRenameClick()
        vm.onRenameConfirm("notes_renamed.txt")
        advanceUntilIdle()
        assertEquals(
            listOf("/storage/emulated/0/notes.txt" to "/storage/emulated/0/notes_renamed.txt"),
            provider.renamedPaths
        )
        assertNull(vm.uiState.value.fileOpDialog)
        assertEquals(0, vm.uiState.value.leftPane.selectedCount)
    }

    @Test
    fun deleteShowsConfirmDialogAndDeletesRecursivelyForDirectories() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (vm, provider) = createViewModelWithProvider(dispatcher)

        vm.onPermissionChanged(true)
        advanceUntilIdle()

        // No selection -> no dialog
        vm.onDeleteClick()
        assertNull(vm.uiState.value.fileOpDialog)

        // Select 1 file + 1 directory
        val downloadNode = vm.uiState.value.leftPane.nodes.first { it.file.name == "Download" }
        val notesNode = vm.uiState.value.leftPane.nodes.first { it.file.name == "notes.txt" }
        vm.onNodeCheckboxToggle(PaneId.LEFT, downloadNode.id)
        vm.onNodeCheckboxToggle(PaneId.LEFT, notesNode.id)

        vm.onDeleteClick()
        val confirmDialog = vm.uiState.value.fileOpDialog as? FileOpDialogState.DeleteConfirm
        assertNotNull(confirmDialog)
        assertEquals(2, confirmDialog?.items?.size)
        assertTrue(confirmDialog?.hasDirectories == true)

        vm.onDeleteConfirm()
        advanceUntilIdle()

        assertEquals(
            listOf(
                "/storage/emulated/0/Download" to true,
                "/storage/emulated/0/notes.txt" to false
            ),
            provider.deletedPaths
        )
        assertNull(vm.uiState.value.fileOpDialog)
        assertEquals(0, vm.uiState.value.leftPane.selectedCount)
    }

    @Test
    fun clipboardClipRequiresSelectionAndClearResetsState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (vm, _) = createViewModelWithProvider(dispatcher)

        vm.onPermissionChanged(true)
        advanceUntilIdle()

        // Without selection -> clipboard stays empty
        vm.onClipClick()
        assertFalse(vm.uiState.value.hasClipboardItems)
        assertNull(vm.uiState.value.clipboard)

        // With selection -> clipboard populated with COPY operation
        val notesNode = vm.uiState.value.leftPane.nodes.first { it.file.name == "notes.txt" }
        vm.onNodeCheckboxToggle(PaneId.LEFT, notesNode.id)
        vm.onClipClick()

        assertTrue(vm.uiState.value.hasClipboardItems)
        val clipboard = vm.uiState.value.clipboard
        assertNotNull(clipboard)
        assertEquals(listOf(notesNode.file), clipboard?.items)
        assertEquals("local", clipboard?.sourceProviderId)
        assertEquals(ClipboardOperation.COPY, clipboard?.operation)

        // Clear clipboard
        vm.onClearClipboard()
        assertFalse(vm.uiState.value.hasClipboardItems)
        assertNull(vm.uiState.value.clipboard)
    }
}
