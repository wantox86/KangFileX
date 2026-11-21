package com.wantox86.kangfilex.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wantox86.kangfilex.presentation.model.PaneId
import com.wantox86.kangfilex.presentation.ui.theme.KangColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KangTopBar(
    activePane: PaneId,
    selectedCount: Int,
    onBackUpClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onTogglePaneClick: () -> Unit,
    onRefreshBothClick: () -> Unit,
    onCollapseAllClick: () -> Unit,
    onAboutClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = KangColors.Surface,
            titleContentColor = KangColors.TextPrimary,
            navigationIconContentColor = KangColors.TextPrimary,
            actionIconContentColor = KangColors.TextPrimary
        ),
        navigationIcon = {
            IconButton(onClick = onBackUpClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Up Directory"
                )
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "KangFileX",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                val paneLabel = if (activePane == PaneId.LEFT) "L" else "R"
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(KangColors.AccentOrangeSubtle)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (selectedCount > 0) "$paneLabel • $selectedCount dipilih" else "Pane $paneLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = KangColors.AccentOrange,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onRefreshClick) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh Pane Aktif",
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onTogglePaneClick) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = "Pindah Pane",
                    modifier = Modifier.size(22.dp)
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Menu Lainnya"
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Refresh Kedua Pane") },
                        onClick = {
                            menuExpanded = false
                            onRefreshBothClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Collapse Semua Folder") },
                        onClick = {
                            menuExpanded = false
                            onCollapseAllClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Tentang KangFileX") },
                        onClick = {
                            menuExpanded = false
                            onAboutClick()
                        }
                    )
                }
            }
        }
    )
}
