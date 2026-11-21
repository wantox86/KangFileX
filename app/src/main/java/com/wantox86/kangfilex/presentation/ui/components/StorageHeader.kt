package com.wantox86.kangfilex.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wantox86.kangfilex.domain.model.StorageVolumeInfo
import com.wantox86.kangfilex.presentation.ui.theme.KangColors
import com.wantox86.kangfilex.util.Formatters

@Composable
fun StorageHeader(
    focusedPath: String?,
    volume: StorageVolumeInfo?,
    childCount: Pair<Int, Int>?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(KangColors.Surface)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = focusedPath ?: volume?.rootPath ?: "Belum ada storage",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = KangColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (childCount != null) {
                Text(
                    text = Formatters.formatFolderCounts(childCount.first, childCount.second),
                    style = MaterialTheme.typography.labelSmall,
                    color = KangColors.TextSecondary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        if (volume != null && volume.totalBytes > 0L) {
            Spacer(modifier = Modifier.height(5.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LinearProgressIndicator(
                    progress = { volume.usedFraction },
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (volume.usedFraction > 0.88f) KangColors.ErrorRed else KangColors.AccentOrange,
                    trackColor = KangColors.Divider
                )
                Text(
                    text = "${Formatters.formatFileSize(volume.usedBytes)} / ${Formatters.formatFileSize(volume.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = KangColors.TextSecondary
                )
            }
        }
    }
}
