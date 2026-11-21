package com.wantox86.kangfilex.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wantox86.kangfilex.presentation.model.BreadcrumbSegment
import com.wantox86.kangfilex.presentation.model.PaneUiState
import com.wantox86.kangfilex.presentation.model.RootNetworkSection
import com.wantox86.kangfilex.presentation.model.TreeNodeUiState
import com.wantox86.kangfilex.presentation.ui.theme.KangColors

@Composable
fun FilePane(
    paneState: PaneUiState,
    isActivePane: Boolean,
    hasStoragePermission: Boolean,
    listState: LazyListState,
    onPaneFocused: () -> Unit,
    onRequestPermission: () -> Unit,
    onBreadcrumbClick: (BreadcrumbSegment) -> Unit,
    onNodeClick: (TreeNodeUiState) -> Unit,
    onNodeCheckboxToggle: (String) -> Unit,
    onNetworkSectionClick: (RootNetworkSection) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KangColors.Background)
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onPaneFocused
            )
    ) {
        // Active pane top accent bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (isActivePane) KangColors.AccentOrange else KangColors.Divider)
        )

        BreadcrumbBar(
            segments = paneState.breadcrumbs,
            isActivePane = isActivePane,
            onSegmentClick = onBreadcrumbClick
        )

        HorizontalDivider(color = KangColors.Divider, thickness = 1.dp)

        StorageHeader(
            focusedPath = paneState.focusedPath,
            volume = paneState.activeVolume,
            childCount = paneState.focusedDirChildCount
        )

        if (paneState.isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = KangColors.AccentOrange,
                trackColor = KangColors.Divider
            )
        } else {
            HorizontalDivider(color = KangColors.Divider, thickness = 1.dp)
        }

        if (!hasStoragePermission) {
            PermissionBanner(onRequestPermissionClick = onRequestPermission)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(
                items = paneState.nodes,
                key = { it.id }
            ) { node ->
                TreeNodeItem(
                    node = node,
                    isActivePane = isActivePane,
                    onNodeClick = { onNodeClick(node) },
                    onCheckboxToggle = { onNodeCheckboxToggle(node.id) }
                )
            }

            // Network Protocol Root Sections (Preparation for Phase 3 & 4)
            item(key = "network_header_${paneState.paneId}") {
                Column(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                    HorizontalDivider(color = KangColors.Divider, thickness = 1.dp)
                    Text(
                        text = "JARINGAN & SERVER",
                        style = MaterialTheme.typography.labelSmall,
                        color = KangColors.TextMuted,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            items(
                items = RootNetworkSection.entries,
                key = { "net_${paneState.paneId}_${it.name}" }
            ) { section ->
                NetworkSectionItem(
                    section = section,
                    onClick = { onNetworkSectionClick(section) }
                )
            }

            item(key = "bottom_spacer_${paneState.paneId}") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun NetworkSectionItem(
    section: RootNetworkSection,
    onClick: () -> Unit
) {
    val icon = when (section) {
        RootNetworkSection.SMB -> Icons.Default.Lan
        RootNetworkSection.FTP -> Icons.Default.CloudQueue
        RootNetworkSection.SFTP -> Icons.Default.Terminal
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(KangColors.Surface.copy(alpha = 0.7f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = section.title,
            tint = KangColors.NetworkGreen,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.bodyMedium,
                color = KangColors.TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = section.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = KangColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(KangColors.SurfaceElevated)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = section.targetPhase,
                style = MaterialTheme.typography.labelSmall,
                color = KangColors.TextMuted
            )
        }
    }
}
