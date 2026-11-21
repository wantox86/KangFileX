package com.wantox86.kangfilex.presentation.viewmodel

import com.wantox86.kangfilex.data.local.StorageVolumeProvider
import com.wantox86.kangfilex.domain.model.StorageVolumeInfo
import com.wantox86.kangfilex.domain.model.VfsFile
import com.wantox86.kangfilex.domain.vfs.VfsProvider
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
        override val providerId: String = "local"
        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
        override suspend fun isConnected(): Boolean = true
        override suspend fun listFiles(path: String): Result<List<VfsFile>> =
            Result.success(tree[path].orEmpty())
        override suspend fun openInputStream(path: String): InputStream = ByteArrayInputStream(ByteArray(0))
        override suspend fun openOutputStream(path: String, overwrite: Boolean): OutputStream = ByteArrayOutputStream()
        override suspend fun createDirectory(path: String): Result<Unit> = Result.success(Unit)
        override suspend fun delete(path: String, recursive: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun rename(oldPath: String, newPath: String): Result<Unit> = Result.success(Unit)
        override suspend fun exists(path: String): Boolean = tree.containsKey(path)
    }

    private fun createViewModel(dispatcher: kotlinx.coroutines.CoroutineDispatcher): DualPaneViewModel {
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
        return DualPaneViewModel(
            vfsProvider = provider,
            storageVolumeProvider = volumeProvider,
            mainDispatcher = dispatcher
        )
    }

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
        vm.onSelectAllOrClear(PaneId.LEFT)
        assertEquals(1, vm.uiState.value.leftPane.selectedCount)
        assertTrue(vm.uiState.value.leftPane.isAllInFocusedDirSelected)

        // Clear selection
        vm.onSelectAllOrClear(PaneId.LEFT)
        assertEquals(0, vm.uiState.value.leftPane.selectedCount)

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
}
