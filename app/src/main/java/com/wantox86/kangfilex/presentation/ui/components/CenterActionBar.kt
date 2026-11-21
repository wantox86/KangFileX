package com.wantox86.kangfilex.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wantox86.kangfilex.presentation.model.PaneId
import com.wantox86.kangfilex.presentation.ui.theme.KangColors

@Composable
fun CenterActionBar(
    activePane: PaneId,
    hasSelection: Boolean,
    isAllSelected: Boolean,
    hasClipboardItems: Boolean,
    onDirectionClick: () -> Unit,
    onUpDirClick: () -> Unit,
    onSelectAllOrClearClick: () -> Unit,
    onPhase2ActionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(KangColors.ActionBarBackground)
            .verticalScroll(scrollState)
            .padding(vertical = 6.dp, horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Direction indicator (shows target pane for Copy/Move; also taps to switch pane)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(KangColors.AccentOrangeSubtle)
                .clickable(onClick = onDirectionClick)
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (activePane == PaneId.LEFT) {
                        Icons.AutoMirrored.Filled.ArrowForward
                    } else {
                        Icons.AutoMirrored.Filled.ArrowBack
                    },
                    contentDescription = "Arah Transfer",
                    tint = KangColors.AccentOrange,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = if (activePane == PaneId.LEFT) "Ke R" else "Ke L",
                    style = MaterialTheme.typography.labelSmall,
                    color = KangColors.AccentOrange,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        HorizontalDivider(color = KangColors.Divider, thickness = 1.dp)

        // Phase 1 active: Up Dir
        ActionBarButton(
            icon = Icons.Default.KeyboardArrowUp,
            label = "Up Dir",
            enabled = true,
            highlight = false,
            onClick = onUpDirClick
        )

        // Phase 2 actions
        ActionBarButton(
            icon = Icons.Default.FileCopy,
            label = "Copy",
            enabled = true,
            muted = !hasSelection,
            onClick = { onPhase2ActionClick("Copy antar pane") }
        )

        ActionBarButton(
            icon = Icons.AutoMirrored.Filled.DriveFileMove,
            label = "Move",
            enabled = true,
            muted = !hasSelection,
            onClick = { onPhase2ActionClick("Move antar pane") }
        )

        ActionBarButton(
            icon = Icons.Default.ContentCopy,
            label = "Clip",
            enabled = true,
            muted = !hasSelection,
            onClick = { onPhase2ActionClick("Copy to Clipboard") }
        )

        ActionBarButton(
            icon = Icons.Default.ContentPaste,
            label = "Paste",
            enabled = hasClipboardItems,
            muted = !hasClipboardItems,
            onClick = { onPhase2ActionClick("Paste") }
        )

        ActionBarButton(
            icon = Icons.Default.DriveFileRenameOutline,
            label = "Rename",
            enabled = true,
            muted = !hasSelection,
            onClick = { onPhase2ActionClick("Rename") }
        )

        ActionBarButton(
            icon = Icons.Default.Delete,
            label = "Delete",
            enabled = true,
            muted = !hasSelection,
            tintOverride = if (hasSelection) KangColors.ErrorRed else null,
            onClick = { onPhase2ActionClick("Delete") }
        )

        ActionBarButton(
            icon = Icons.Default.CreateNewFolder,
            label = "New Dir",
            enabled = true,
            onClick = { onPhase2ActionClick("New Folder") }
        )

        HorizontalDivider(color = KangColors.Divider, thickness = 1.dp)

        // Phase 1 active: Select All / Clear
        val showClear = isAllSelected || hasSelection
        ActionBarButton(
            icon = if (showClear) Icons.Default.Deselect else Icons.Default.SelectAll,
            label = if (showClear) "Clear" else "Sel All",
            enabled = true,
            highlight = showClear,
            onClick = onSelectAllOrClearClick
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun ActionBarButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    highlight: Boolean = false,
    muted: Boolean = false,
    tintOverride: Color? = null,
    onClick: () -> Unit
) {
    val contentColor = when {
        !enabled -> KangColors.TextMuted.copy(alpha = 0.45f)
        tintOverride != null -> tintOverride
        highlight -> KangColors.AccentOrange
        muted -> KangColors.TextSecondary
        else -> KangColors.TextPrimary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (highlight) KangColors.AccentOrangeSubtle else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(19.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center
        )
    }
}
