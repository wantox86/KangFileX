package com.wantox86.kangfilex.presentation.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val leftListState = rememberLazyListState()
    val rightListState = rememberLazyListState()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.ShowSnackbar -> {
                    launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(
                            message = event.message,
                            duration = SnackbarDuration.Short
                        )
                    }
                }
                is UiEvent.ScrollToNode -> {
                    val targetList = if (event.paneId == PaneId.LEFT) leftListState else rightListState
                    launch {
                        targetList.animateScrollToItem(event.index.coerceAtLeast(0))
                    }
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
                    screenWidthPx = constraints.maxWidth,
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
            isClearActionMode = uiState.activePaneState.isClearActionMode,
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
    screenWidthPx: Int,
    uiState: MainUiState,
    leftListState: androidx.compose.foundation.lazy.LazyListState,
    rightListState: androidx.compose.foundation.lazy.LazyListState,
    viewModel: DualPaneViewModel,
    onRequestStoragePermission: () -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val barWidthDp = (maxWidthDp * 0.16f).coerceIn(58.dp, 72.dp)
    val barWidthPx = with(density) { barWidthDp.roundToPx() }.coerceIn(0, screenWidthPx)
    val paneWidthPx = (screenWidthPx - barWidthPx).coerceAtLeast(1)
    val paneWidthPxFloat = paneWidthPx.toFloat()

    // offset = 0f -> Left Pane [0, W - A] + Center Action Bar [W - A, W]
    // offset = -(W - A) -> Center Action Bar [0, A] + Right Pane [A, W]
    val offsetX = remember {
        Animatable(if (uiState.activePane == PaneId.LEFT) 0f else -paneWidthPxFloat)
    }

    LaunchedEffect(uiState.activePane, paneWidthPxFloat) {
        val target = if (uiState.activePane == PaneId.LEFT) 0f else -paneWidthPxFloat
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
            val clamped = (offsetX.value + delta).coerceIn(-paneWidthPxFloat, 0f)
            offsetX.snapTo(clamped)
        }
    }

    Layout(
        content = {
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
                onNetworkSectionClick = { viewModel.onNetworkSectionClick(it) }
            )

            CenterActionBar(
                activePane = uiState.activePane,
                hasSelection = uiState.activePaneState.hasSelection,
                isClearActionMode = uiState.activePaneState.isClearActionMode,
                hasClipboardItems = uiState.hasClipboardItems,
                onDirectionClick = { viewModel.toggleActivePane() },
                onUpDirClick = { viewModel.onUpDir() },
                onSelectAllOrClearClick = { viewModel.onSelectAllOrClear() },
                onPhase2ActionClick = { viewModel.onPhase2ActionClick(it) }
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
                onNetworkSectionClick = { viewModel.onNetworkSectionClick(it) }
            )
        },
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
                        offsetX.value < -paneWidthPxFloat / 2f -> PaneId.RIGHT
                        else -> PaneId.LEFT
                    }
                    viewModel.setActivePane(targetPane)
                    val targetOffset = if (targetPane == PaneId.LEFT) 0f else -paneWidthPxFloat
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
    ) { measurables, constraints ->
        val screenWidth = constraints.maxWidth
        val screenHeight = constraints.maxHeight
        val actualBarWidth = barWidthPx.coerceIn(0, screenWidth)
        val actualPaneWidth = (screenWidth - actualBarWidth).coerceAtLeast(0)

        val leftPlaceable = measurables[0].measure(
            Constraints.fixed(width = actualPaneWidth, height = screenHeight)
        )
        val barPlaceable = measurables[1].measure(
            Constraints.fixed(width = actualBarWidth, height = screenHeight)
        )
        val rightPlaceable = measurables[2].measure(
            Constraints.fixed(width = actualPaneWidth, height = screenHeight)
        )

        layout(screenWidth, screenHeight) {
            val x = offsetX.value.roundToInt().coerceIn(-actualPaneWidth, 0)
            leftPlaceable.place(x = x, y = 0)
            barPlaceable.place(x = x + actualPaneWidth, y = 0)
            rightPlaceable.place(x = x + actualPaneWidth + actualBarWidth, y = 0)
        }
    }
}
