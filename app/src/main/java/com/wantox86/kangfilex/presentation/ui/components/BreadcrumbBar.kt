package com.wantox86.kangfilex.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wantox86.kangfilex.presentation.model.BreadcrumbSegment
import com.wantox86.kangfilex.presentation.ui.theme.KangColors

@Composable
fun BreadcrumbBar(
    segments: List<BreadcrumbSegment>,
    isActivePane: Boolean,
    onSegmentClick: (BreadcrumbSegment) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(segments) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(KangColors.SurfaceElevated)
            .horizontalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (segments.isEmpty()) {
            Text(
                text = "Root",
                style = MaterialTheme.typography.bodySmall,
                color = KangColors.TextMuted
            )
            return@Row
        }

        segments.forEachIndexed { index, segment ->
            val isLast = index == segments.lastIndex
            if (index > 0) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = KangColors.TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = segment.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isLast && isActivePane -> KangColors.AccentOrange
                    isLast -> KangColors.TextPrimary
                    else -> KangColors.TextSecondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onSegmentClick(segment) }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}
