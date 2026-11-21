package com.wantox86.kangfilex.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wantox86.kangfilex.presentation.model.TreeNodeUiState
import com.wantox86.kangfilex.presentation.ui.theme.KangColors
import com.wantox86.kangfilex.util.Formatters

private val INDENT_STEP = 18.dp

@Composable
fun TreeNodeItem(
    node: TreeNodeUiState,
    isActivePane: Boolean,
    onNodeClick: () -> Unit,
    onCheckboxToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showTargetBorder = node.isFocusedTarget && node.file.isDirectory
    val borderColor = if (isActivePane) KangColors.AccentOrange else KangColors.AccentOrange.copy(alpha = 0.45f)
    val bgColor = when {
        node.isSelected -> KangColors.SelectedTint
        showTargetBorder -> KangColors.AccentOrangeSubtle.copy(alpha = if (isActivePane) 0.14f else 0.06f)
        else -> Color.Transparent
    }

    val shape = RoundedCornerShape(6.dp)
    val baseModifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp, vertical = 1.dp)
        .clip(shape)
        .background(bgColor)
        .then(
            if (showTargetBorder) {
                Modifier.border(width = 1.5.dp, color = borderColor, shape = shape)
            } else {
                Modifier
            }
        )
        .clickable(onClick = onNodeClick)
        .drawBehind {
            if (node.depth > 0) {
                val stepPx = INDENT_STEP.toPx()
                val strokeWidth = 1.dp.toPx()
                val lineColor = KangColors.TreeConnector
                for (level in 1..node.depth) {
                    val x = (level - 1) * stepPx + stepPx / 2f + 4.dp.toPx()
                    drawLine(
                        color = lineColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = strokeWidth
                    )
                }
                val branchX = (node.depth - 1) * stepPx + stepPx / 2f + 4.dp.toPx()
                val midY = size.height / 2f
                drawLine(
                    color = lineColor,
                    start = Offset(branchX, midY),
                    end = Offset(branchX + stepPx * 0.55f, midY),
                    strokeWidth = strokeWidth
                )
            }
        }

    Row(
        modifier = baseModifier
            .padding(
                start = (node.depth * 18 + 6).dp,
                end = 4.dp,
                top = 6.dp,
                bottom = 6.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Expand / Collapse indicator
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                node.isLoadingChildren -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = KangColors.AccentOrange
                    )
                }
                node.file.isDirectory -> {
                    Icon(
                        imageVector = if (node.isExpanded) {
                            Icons.Default.KeyboardArrowDown
                        } else {
                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                        },
                        contentDescription = if (node.isExpanded) "Collapse" else "Expand",
                        tint = if (node.isExpanded) KangColors.AccentOrange else KangColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Node Icon
        val (icon, iconTint) = resolveNodeIcon(node)
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Name & Metadata subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (node.depth == 0 || node.isFocusedTarget) FontWeight.SemiBold else FontWeight.Normal,
                color = if (node.depth == 0) KangColors.StorageCyan else KangColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = buildSubtitle(node)
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = KangColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // One-tap multi-select checkbox on the right (skip for root volume node at depth 0)
        if (node.depth > 0) {
            Checkbox(
                checked = node.isSelected,
                onCheckedChange = { onCheckboxToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = KangColors.AccentOrange,
                    uncheckedColor = KangColors.TextMuted,
                    checkmarkColor = KangColors.Background
                ),
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

private fun resolveNodeIcon(node: TreeNodeUiState): Pair<ImageVector, Color> {
    if (node.depth == 0) {
        val isSd = node.file.name.contains("SD Card", ignoreCase = true)
        return (if (isSd) Icons.Default.SdStorage else Icons.Default.Storage) to KangColors.StorageCyan
    }
    if (node.file.isDirectory) {
        return (if (node.isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder) to KangColors.FolderYellow
    }
    val ext = node.file.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "apk", "aab" -> Icons.Default.Android to KangColors.NetworkGreen
        "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp" -> Icons.Default.Image to Color(0xFF4DD0E1)
        "mp4", "mkv", "avi", "mov", "webm" -> Icons.Default.VideoFile to Color(0xFFBA68C8)
        "mp3", "wav", "flac", "ogg", "m4a", "aac" -> Icons.Default.AudioFile to Color(0xFFF06292)
        "zip", "rar", "7z", "tar", "gz", "xz" -> Icons.Default.FolderZip to Color(0xFFFFD54F)
        "pdf", "txt", "md", "json", "xml", "log", "csv", "kt", "java" -> Icons.Default.Description to Color(0xFF90CAF9)
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to KangColors.FileIconDefault
    }
}

private fun buildSubtitle(node: TreeNodeUiState): String {
    if (node.file.isDirectory) {
        val counts = Formatters.formatFolderCounts(node.file.childDirCount, node.file.childFileCount)
        return if (node.depth == 0) {
            "${node.file.path} • $counts"
        } else {
            counts
        }
    }
    val size = Formatters.formatFileSize(node.file.size)
    val time = Formatters.formatTimestamp(node.file.lastModified)
    return if (time.isNotEmpty()) "$size • $time" else size
}
