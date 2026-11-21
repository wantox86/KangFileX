package com.wantox86.kangfilex.presentation.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.wantox86.kangfilex.presentation.model.MainUiState
import com.wantox86.kangfilex.presentation.model.PaneId
import com.wantox86.kangfilex.presentation.ui.components.CenterActionBar
import com.wantox86.kangfilex.presentation.ui.components.FilePane
import com.wantox86.kangfilex.presentation.ui.components.KangTopBar
import com.wantox86.kangfilex.presentation.ui.theme.KangColors
import com.wantox86.kangfilex.presentation.viewmodel.DualPaneViewModel
import com.wantox86.kangfilex.presentation.viewmodel.UiEvent
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun DualPaneScreen(
    viewModel: DualPaneViewModel,
    onRequestStoragePermission: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val leftListState = rememberLazyListState()
    val rightListState = rememberLazyListState()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.ShowSnackbar -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Short
                    )
                }
                is UiEvent.ScrollToNode -> {
                    val targetList = if (event.paneId == PaneId.LEFT) leftListState else rightListState
                    targetList.animateScrollToItem(event.index.coerceAtLeast(0))
                }
            }
        }
    }

    Scaffold(
        containerColor = KangColors.Background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            KangTopBar(
                activePane = uiState.activePane,
                selectedCount = uiState.activePaneState.selectedCount,
                onBackUpClick = { viewModel.onUpDir() },
                onRefreshClick = { viewModel.onRefresh() },
                onTogglePaneClick = { viewModel.toggleActivePane() },
                onRefreshBothClick = { viewModel.onRefreshBoth() },
                onCollapseAllClick = { viewModel.onCollapseAll() },
                onAboutClick = {
                    viewModel.onPhase2ActionClick("KangFileX v0.1.0 (Phase 1) — fitur lengkap")
                }
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isWideLayout = maxWidth >= 600.dp

            if (isWideLayout) {
                LandscapeDualPaneContent(
                    uiState = uiState,
                    leftListState = leftListState,
                    rightListState = rightListState,
                    viewModel = viewModel,
                    onRequestStoragePermission = onRequestStoragePermission
                )
            } else {
                PortraitSwipeDualPaneContent(
                    maxWidthDp = maxWidth,
                    uiState = uiState,
                    leftListState = leftListState,
                    rightListState = rightListState,
                    viewModel = viewModel,
                    onRequestStoragePermission = onRequestStoragePermission
                )
            }
        }
    }
}

@Composable
private fun LandscapeDualPaneContent(
    uiState: MainUiState,
    leftListState: androidx.compose.foundation.lazy.LazyListState,
    rightListState: androidx.compose.foundation.lazy.LazyListState,
    viewModel: DualPaneViewModel,
    onRequestStoragePermission: () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        FilePane(
            paneState = uiState.leftPane,
            isActivePane = uiState.activePane == PaneId.LEFT,
            hasStoragePermission = uiState.hasStoragePermission,
            listState = leftListState,
            onPaneFocused = { viewModel.setActivePane(PaneId.LEFT) },
            onRequestPermission = onRequestStoragePermission,
            onBreadcrumbClick = { viewModel.onBreadcrumbClick(PaneId.LEFT, it) },
            onNodeClick = { viewModel.onNodeClick(PaneId.LEFT, it) },
            onNodeCheckboxToggle = { viewModel.onNodeCheckboxToggle(PaneId.LEFT, it) },
            onNetworkSectionClick = { viewModel.onNetworkSectionClick(it) },
            modifier = Modifier.weight(0.45f)
        )

        VerticalDivider(color = KangColors.Divider, thickness = 1.dp)

        CenterActionBar(
            activePane = uiState.activePane,
            hasSelection = uiState.activePaneState.hasSelection,
            isAllSelected = uiState.activePaneState.isAllInFocusedDirSelected,
            hasClipboardItems = uiState.hasClipboardItems,
            onDirectionClick = { viewModel.toggleActivePane() },
            onUpDirClick = { viewModel.onUpDir() },
            onSelectAllOrClearClick = { viewModel.onSelectAllOrClear() },
            onPhase2ActionClick = { viewModel.onPhase2ActionClick(it) },
            modifier = Modifier.weight(0.10f)
        )

        VerticalDivider(color = KangColors.Divider, thickness = 1.dp)

        FilePane(
            paneState = uiState.rightPane,
            isActivePane = uiState.activePane == PaneId.RIGHT,
            hasStoragePermission = uiState.hasStoragePermission,
            listState = rightListState,
            onPaneFocused = { viewModel.setActivePane(PaneId.RIGHT) },
            onRequestPermission = onRequestStoragePermission,
            onBreadcrumbClick = { viewModel.onBreadcrumbClick(PaneId.RIGHT, it) },
            onNodeClick = { viewModel.onNodeClick(PaneId.RIGHT, it) },
            onNodeCheckboxToggle = { viewModel.onNodeCheckboxToggle(PaneId.RIGHT, it) },
            onNetworkSectionClick = { viewModel.onNetworkSectionClick(it) },
            modifier = Modifier.weight(0.45f)
        )
    }
}

@Composable
private fun PortraitSwipeDualPaneContent(
    maxWidthDp: androidx.compose.ui.unit.Dp,
    uiState: MainUiState,
    leftListState: androidx.compose.foundation.lazy.LazyListState,
    rightListState: androidx.compose.foundation.lazy.LazyListState,
    viewModel: DualPaneViewModel,
    onRequestStoragePermission: () -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val barWidthDp = (maxWidthDp * 0.16f).coerceIn(58.dp, 72.dp)
    val paneWidthDp = (maxWidthDp - barWidthDp).coerceAtLeast(200.dp)
    val paneWidthPx = with(density) { paneWidthDp.toPx() }

    // offset = 0f -> Left Pane + Center Action Bar visible
    // offset = -paneWidthPx -> Center Action Bar + Right Pane visible
    val offsetX = remember { Animatable(0f) }

    LaunchedEffect(uiState.activePane, paneWidthPx) {
        val target = if (uiState.activePane == PaneId.LEFT) 0f else -paneWidthPx
        if (offsetX.value != target) {
            offsetX.animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    val dragState = rememberDraggableState { delta ->
        scope.launch {
            val clamped = (offsetX.value + delta).coerceIn(-paneWidthPx, 0f)
            offsetX.snapTo(clamped)
        }
    }

    Box(
        contentAlignment = Alignment.TopStart,
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStopped = { velocity ->
                    val targetPane = when {
                        velocity < -500f -> PaneId.RIGHT
                        velocity > 500f -> PaneId.LEFT
                        offsetX.value < -paneWidthPx / 2f -> PaneId.RIGHT
                        else -> PaneId.LEFT
                    }
                    viewModel.setActivePane(targetPane)
                    val targetOffset = if (targetPane == PaneId.LEFT) 0f else -paneWidthPx
                    scope.launch {
                        offsetX.animateTo(
                            targetValue = targetOffset,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        )
                    }
                }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .requiredWidth(paneWidthDp * 2 + barWidthDp)
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
        ) {
            FilePane(
                paneState = uiState.leftPane,
                isActivePane = uiState.activePane == PaneId.LEFT,
                hasStoragePermission = uiState.hasStoragePermission,
                listState = leftListState,
                onPaneFocused = { viewModel.setActivePane(PaneId.LEFT) },
                onRequestPermission = onRequestStoragePermission,
                onBreadcrumbClick = { viewModel.onBreadcrumbClick(PaneId.LEFT, it) },
                onNodeClick = { viewModel.onNodeClick(PaneId.LEFT, it) },
                onNodeCheckboxToggle = { viewModel.onNodeCheckboxToggle(PaneId.LEFT, it) },
                onNetworkSectionClick = { viewModel.onNetworkSectionClick(it) },
                modifier = Modifier
                    .width(paneWidthDp)
                    .fillMaxHeight()
            )

            CenterActionBar(
                activePane = uiState.activePane,
                hasSelection = uiState.activePaneState.hasSelection,
                isAllSelected = uiState.activePaneState.isAllInFocusedDirSelected,
                hasClipboardItems = uiState.hasClipboardItems,
                onDirectionClick = { viewModel.toggleActivePane() },
                onUpDirClick = { viewModel.onUpDir() },
                onSelectAllOrClearClick = { viewModel.onSelectAllOrClear() },
                onPhase2ActionClick = { viewModel.onPhase2ActionClick(it) },
                modifier = Modifier
                    .width(barWidthDp)
                    .fillMaxHeight()
            )

            FilePane(
                paneState = uiState.rightPane,
                isActivePane = uiState.activePane == PaneId.RIGHT,
                hasStoragePermission = uiState.hasStoragePermission,
                listState = rightListState,
                onPaneFocused = { viewModel.setActivePane(PaneId.RIGHT) },
                onRequestPermission = onRequestStoragePermission,
                onBreadcrumbClick = { viewModel.onBreadcrumbClick(PaneId.RIGHT, it) },
                onNodeClick = { viewModel.onNodeClick(PaneId.RIGHT, it) },
                onNodeCheckboxToggle = { viewModel.onNodeCheckboxToggle(PaneId.RIGHT, it) },
                onNetworkSectionClick = { viewModel.onNetworkSectionClick(it) },
                modifier = Modifier
                    .width(paneWidthDp)
                    .fillMaxHeight()
            )
        }
    }
}
