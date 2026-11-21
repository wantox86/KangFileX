package com.wantox86.kangfilex.presentation.tree

import com.wantox86.kangfilex.domain.model.StorageVolumeInfo
import com.wantox86.kangfilex.domain.model.VfsFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeStateManagerTest {

    private val internalVolume = StorageVolumeInfo(
        id = "local_internal",
        label = "Internal Storage",
        rootPath = "/storage/emulated/0",
        totalBytes = 100_000L,
        usedBytes = 40_000L,
        availableBytes = 60_000L
    )

    private fun dir(path: String, name: String) = VfsFile(
        name = name,
        path = path,
        isDirectory = true,
        size = 0L,
        lastModified = 0L,
        providerId = "local"
    )

    private fun file(path: String, name: String, size: Long = 100L) = VfsFile(
        name = name,
        path = path,
        isDirectory = false,
        size = size,
        lastModified = 1000L,
        providerId = "local"
    )

    @Test
    fun flattenOrdersInlineChildrenWithDepthAndFocusedTarget() {
        val manager = TreeStateManager("local")
        manager.setVolumes(listOf(internalVolume))

        val rootId = manager.nodeId("/storage/emulated/0")
        val downloadPath = "/storage/emulated/0/Download"
        val downloadId = manager.nodeId(downloadPath)

        manager.setExpanded(rootId, true)
        manager.setChildren(
            rootId,
            listOf(
                file("/storage/emulated/0/readme.txt", "readme.txt"),
                dir(downloadPath, "Download"),
                dir("/storage/emulated/0/android", "android")
            )
        )

        manager.setExpanded(downloadId, true)
        manager.setChildren(
            downloadId,
            listOf(
                file("$downloadPath/app.apk", "app.apk"),
                dir("$downloadPath/Telegram", "Telegram")
            )
        )
        manager.setFocusedTarget(downloadPath)

        val flattened = manager.flatten()
        assertEquals(
            listOf("Internal Storage", "android", "Download", "Telegram", "app.apk", "readme.txt"),
            flattened.map { it.file.name }
        )
        assertEquals(listOf(0, 1, 1, 2, 2, 1), flattened.map { it.depth })
        assertTrue(flattened[2].isFocusedTarget)
        assertFalse(flattened[0].isFocusedTarget)
    }

    @Test
    fun breadcrumbsAndNavigateUpWorkCorrectly() {
        val manager = TreeStateManager("local")
        manager.setVolumes(listOf(internalVolume))

        val downloadPath = "/storage/emulated/0/Download"
        val telegramPath = "$downloadPath/Telegram"
        manager.setExpanded(manager.nodeId("/storage/emulated/0"), true)
        manager.setExpanded(manager.nodeId(downloadPath), true)
        manager.setExpanded(manager.nodeId(telegramPath), true)
        manager.setFocusedTarget(telegramPath)

        val crumbs = manager.buildBreadcrumbs()
        assertEquals(listOf("Internal Storage", "Download", "Telegram"), crumbs.map { it.label })

        val afterUp1 = manager.navigateUp()
        assertEquals(downloadPath, afterUp1)
        assertFalse(manager.isExpanded(manager.nodeId(telegramPath)))
        assertEquals(listOf("Internal Storage", "Download"), manager.buildBreadcrumbs().map { it.label })

        val afterUp2 = manager.navigateUp()
        assertEquals("/storage/emulated/0", afterUp2)
        assertFalse(manager.isExpanded(manager.nodeId(downloadPath)))

        // At root, Up Dir collapses the root node
        assertTrue(manager.isExpanded(manager.nodeId("/storage/emulated/0")))
        manager.navigateUp()
        assertFalse(manager.isExpanded(manager.nodeId("/storage/emulated/0")))
    }

    @Test
    fun multiSelectSelectAllAndClear() {
        val manager = TreeStateManager("local")
        manager.setVolumes(listOf(internalVolume))

        val rootPath = "/storage/emulated/0"
        val rootId = manager.nodeId(rootPath)
        val f1 = file("$rootPath/a.txt", "a.txt")
        val f2 = file("$rootPath/b.txt", "b.txt")

        manager.setExpanded(rootId, true)
        manager.setChildren(rootId, listOf(f1, f2))
        manager.setFocusedTarget(rootPath)

        manager.toggleSelection(manager.nodeId(f1))
        assertEquals(1, manager.getSelectedIds().size)
        assertFalse(manager.isAllSelectedInFocusedDir())

        // Select all picks remaining items in focused dir
        assertTrue(manager.toggleSelectAllOrClear())
        assertEquals(2, manager.getSelectedIds().size)
        assertTrue(manager.isAllSelectedInFocusedDir())

        // Calling again clears all
        assertFalse(manager.toggleSelectAllOrClear())
        assertTrue(manager.getSelectedIds().isEmpty())
    }

    @Test
    fun isClearActionModeSyncsWithToggleSelectAllOrClear() {
        val manager = TreeStateManager("local")
        manager.setVolumes(listOf(internalVolume))

        val rootPath = "/storage/emulated/0"
        val rootId = manager.nodeId(rootPath)
        val emptyDirPath = "$rootPath/Empty"
        val emptyDirId = manager.nodeId(emptyDirPath)
        val f1 = file("$rootPath/a.txt", "a.txt")
        val f2 = file("$rootPath/b.txt", "b.txt")
        val f3 = file("$rootPath/c.txt", "c.txt")

        manager.setExpanded(rootId, true)
        manager.setChildren(rootId, listOf(f1, f2, f3))
        manager.setChildren(emptyDirId, emptyList())
        manager.setFocusedTarget(rootPath)

        // 1. Empty selection -> Sel All mode
        assertFalse(manager.isClearActionMode())

        // 2. Partial selection (1 of 3) -> still Sel All mode; clicking selects all
        manager.toggleSelection(manager.nodeId(f1))
        assertEquals(1, manager.getSelectedIds().size)
        assertFalse(manager.isClearActionMode())
        assertTrue(manager.toggleSelectAllOrClear())
        assertEquals(3, manager.getSelectedIds().size)

        // 3. Full selection (3 of 3) -> Clear mode; clicking clears all
        assertTrue(manager.isClearActionMode())
        assertFalse(manager.toggleSelectAllOrClear())
        assertTrue(manager.getSelectedIds().isEmpty())
        assertFalse(manager.isClearActionMode())

        // 4. Selection in another folder while focused folder is empty -> Clear mode
        manager.toggleSelection(manager.nodeId(f2))
        manager.setFocusedTarget(emptyDirPath)
        assertTrue(manager.isClearActionMode())
        assertFalse(manager.toggleSelectAllOrClear())
        assertTrue(manager.getSelectedIds().isEmpty())
        assertFalse(manager.isClearActionMode())
    }

    @Test
    fun setVolumesResetsFocusedTargetWhenActiveVolumeRemoved() {
        val sdCardVolume = StorageVolumeInfo(
            id = "local_sd",
            label = "SD Card",
            rootPath = "/storage/ABCD-1234",
            totalBytes = 200_000L,
            usedBytes = 50_000L,
            availableBytes = 150_000L,
            isRemovable = true
        )
        val manager = TreeStateManager("local")
        manager.setVolumes(listOf(internalVolume, sdCardVolume))
        manager.setFocusedTarget("/storage/ABCD-1234/Music")
        assertEquals("/storage/ABCD-1234/Music", manager.getFocusedPath())

        // SD card unmounted -> focused target must reset to remaining primary volume root
        manager.setVolumes(listOf(internalVolume))
        assertEquals("/storage/emulated/0", manager.getFocusedPath())
    }
}
