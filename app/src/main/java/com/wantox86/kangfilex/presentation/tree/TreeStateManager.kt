package com.wantox86.kangfilex.presentation.tree

import com.wantox86.kangfilex.domain.model.StorageVolumeInfo
import com.wantox86.kangfilex.domain.model.VfsFile
import com.wantox86.kangfilex.presentation.model.BreadcrumbSegment
import com.wantox86.kangfilex.presentation.model.TreeNodeUiState
import java.io.File

class TreeStateManager(
    private val providerId: String
) {
    private var volumes: List<StorageVolumeInfo> = emptyList()
    private var rootFiles: List<VfsFile> = emptyList()
    private val childrenByNodeId: MutableMap<String, List<VfsFile>> = linkedMapOf()
    private val expandedIds: MutableSet<String> = linkedSetOf()
    private val loadingIds: MutableSet<String> = linkedSetOf()
    private val selectedIds: MutableSet<String> = linkedSetOf()
    private var focusedTargetPath: String? = null

    fun setVolumes(newVolumes: List<StorageVolumeInfo>) {
        volumes = newVolumes
        rootFiles = newVolumes.map { vol ->
            VfsFile(
                name = vol.label,
                path = vol.rootPath,
                isDirectory = true,
                size = vol.totalBytes,
                lastModified = 0L,
                providerId = providerId
            )
        }
        val currentFocused = focusedTargetPath
        if (currentFocused == null || findMatchingVolumeForPath(currentFocused) == null) {
            focusedTargetPath = newVolumes.firstOrNull()?.rootPath
        }
    }

    fun getVolumes(): List<StorageVolumeInfo> = volumes

    fun getFocusedPath(): String? = focusedTargetPath

    fun setFocusedTarget(path: String) {
        focusedTargetPath = normalizePath(path)
    }

    fun isExpanded(nodeId: String): Boolean = expandedIds.contains(nodeId)

    fun hasLoadedChildren(nodeId: String): Boolean = childrenByNodeId.containsKey(nodeId)

    fun getExpandedPaths(): List<String> {
        val prefix = "$providerId:"
        return expandedIds.mapNotNull { id ->
            if (id.startsWith(prefix)) id.removePrefix(prefix) else null
        }
    }

    fun setLoading(nodeId: String, loading: Boolean) {
        if (loading) {
            loadingIds.add(nodeId)
        } else {
            loadingIds.remove(nodeId)
        }
    }

    fun setExpanded(nodeId: String, expanded: Boolean) {
        if (expanded) {
            expandedIds.add(nodeId)
        } else {
            expandedIds.remove(nodeId)
        }
    }

    fun setChildren(parentNodeId: String, children: List<VfsFile>) {
        val sorted = children.sortedWith(FILE_COMPARATOR)
        childrenByNodeId[parentNodeId] = sorted
        loadingIds.remove(parentNodeId)
        // Update root metadata counts if parentNodeId is a root node
        val rootIdx = rootFiles.indexOfFirst { nodeId(it) == parentNodeId }
        if (rootIdx >= 0) {
            val dirs = sorted.count { it.isDirectory }
            val files = sorted.size - dirs
            rootFiles = rootFiles.toMutableList().apply {
                this[rootIdx] = this[rootIdx].copy(
                    childDirCount = dirs,
                    childFileCount = files
                )
            }
        }
    }

    fun collapse(nodeId: String) {
        expandedIds.remove(nodeId)
        loadingIds.remove(nodeId)
    }

    fun collapseAll() {
        expandedIds.clear()
        loadingIds.clear()
        volumes.firstOrNull()?.let { focusedTargetPath = it.rootPath }
    }

    fun toggleSelection(nodeId: String) {
        if (!selectedIds.add(nodeId)) {
            selectedIds.remove(nodeId)
        }
    }

    fun clearSelection() {
        selectedIds.clear()
    }

    fun getSelectedIds(): Set<String> = selectedIds.toSet()

    fun isAllSelectedInFocusedDir(): Boolean {
        val focused = focusedTargetPath ?: return false
        val children = childrenByNodeId[nodeId(focused)] ?: return false
        if (children.isEmpty()) return false
        return children.all { selectedIds.contains(nodeId(it)) }
    }

    /**
     * Returns true if calling [toggleSelectAllOrClear] will clear the current selection
     * rather than selecting all children in the focused directory.
     */
    fun isClearActionMode(): Boolean {
        val focused = focusedTargetPath
        val children = focused?.let { childrenByNodeId[nodeId(it)] }.orEmpty()
        val allFocusedSelected = children.isNotEmpty() && children.all { selectedIds.contains(nodeId(it)) }
        return allFocusedSelected || (selectedIds.isNotEmpty() && children.isEmpty())
    }

    /**
     * Toggles Select All / Clear:
     * - If [isClearActionMode] is true -> clear all selections.
     * - Otherwise select all direct children of the focused directory.
     */
    fun toggleSelectAllOrClear(): Boolean {
        val focused = focusedTargetPath
        val children = focused?.let { childrenByNodeId[nodeId(it)] }.orEmpty()

        return if (isClearActionMode()) {
            selectedIds.clear()
            false
        } else if (children.isNotEmpty()) {
            children.forEach { selectedIds.add(nodeId(it)) }
            true
        } else {
            false
        }
    }

    /**
     * Navigates up one directory level from the current focused path.
     * - If focused path is below its volume root: collapses focused folder (if expanded) and moves focus to parent.
     * - If focused path is already at volume root and expanded: collapses the volume root.
     * Returns the new focused path.
     */
    fun navigateUp(): String? {
        val current = focusedTargetPath ?: return null
        val volume = findVolumeForPath(current) ?: return current
        val rootPath = normalizePath(volume.rootPath)
        val normalizedCurrent = normalizePath(current)

        val currentId = nodeId(normalizedCurrent)
        if (normalizedCurrent == rootPath) {
            if (expandedIds.contains(currentId)) {
                expandedIds.remove(currentId)
            }
            return rootPath
        }

        expandedIds.remove(currentId)
        val parentPath = File(normalizedCurrent).parent?.let(::normalizePath) ?: rootPath
        val target = if (parentPath.startsWith(rootPath)) parentPath else rootPath
        focusedTargetPath = target
        return target
    }

    fun findVolumeForPath(path: String?): StorageVolumeInfo? {
        if (path == null) return volumes.firstOrNull()
        return findMatchingVolumeForPath(path) ?: volumes.firstOrNull()
    }

    private fun findMatchingVolumeForPath(path: String): StorageVolumeInfo? {
        val normalized = normalizePath(path)
        return volumes
            .sortedByDescending { it.rootPath.length }
            .firstOrNull { vol ->
                val root = normalizePath(vol.rootPath)
                normalized == root || normalized.startsWith("$root/")
            }
    }

    fun getFocusedDirChildCount(): Pair<Int, Int>? {
        val focused = focusedTargetPath ?: return null
        val children = childrenByNodeId[nodeId(focused)] ?: return null
        val dirs = children.count { it.isDirectory }
        val files = children.size - dirs
        return dirs to files
    }

    fun buildBreadcrumbs(): List<BreadcrumbSegment> {
        val focused = focusedTargetPath ?: return emptyList()
        val volume = findVolumeForPath(focused) ?: return emptyList()
        val rootPath = normalizePath(volume.rootPath)
        val normalizedFocused = normalizePath(focused)

        val segments = mutableListOf(
            BreadcrumbSegment(
                label = volume.label,
                path = rootPath,
                isRoot = true
            )
        )

        if (normalizedFocused == rootPath || !normalizedFocused.startsWith("$rootPath/")) {
            return segments
        }

        val relative = normalizedFocused.removePrefix("$rootPath/")
        var runningPath = rootPath
        for (part in relative.split('/').filter { it.isNotEmpty() }) {
            runningPath = "$runningPath/$part"
            segments.add(
                BreadcrumbSegment(
                    label = part,
                    path = runningPath,
                    isRoot = false
                )
            )
        }
        return segments
    }

    fun flatten(): List<TreeNodeUiState> {
        val result = mutableListOf<TreeNodeUiState>()
        for (root in rootFiles) {
            appendNodeRecursively(file = root, depth = 0, out = result)
        }
        return result
    }

    fun findNodeIndexByPath(path: String): Int {
        val targetId = nodeId(normalizePath(path))
        return flatten().indexOfFirst { it.id == targetId }
    }

    fun nodeId(file: VfsFile): String = nodeId(file.path)

    fun nodeId(path: String): String = "$providerId:${normalizePath(path)}"

    private fun appendNodeRecursively(
        file: VfsFile,
        depth: Int,
        out: MutableList<TreeNodeUiState>
    ) {
        val id = nodeId(file)
        val normalizedPath = normalizePath(file.path)
        val expanded = expandedIds.contains(id)
        val loading = loadingIds.contains(id)
        val selected = selectedIds.contains(id)
        val focused = focusedTargetPath != null && normalizePath(focusedTargetPath!!) == normalizedPath

        out.add(
            TreeNodeUiState(
                id = id,
                file = file,
                depth = depth,
                isExpanded = expanded,
                isLoadingChildren = loading,
                isSelected = selected,
                isFocusedTarget = focused
            )
        )

        if (file.isDirectory && expanded) {
            val children = childrenByNodeId[id].orEmpty()
            for (child in children) {
                appendNodeRecursively(file = child, depth = depth + 1, out = out)
            }
        }
    }

    private fun normalizePath(path: String): String {
        if (path == "/") return "/"
        return path.trimEnd('/')
    }

    companion object {
        val FILE_COMPARATOR: Comparator<VfsFile> =
            compareByDescending<VfsFile> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }
}
