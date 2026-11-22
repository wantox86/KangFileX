package com.wantox86.kangfilex.presentation.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wantox86.kangfilex.data.transfer.TransferJobUi
import com.wantox86.kangfilex.data.transfer.TransferUiState
import com.wantox86.kangfilex.domain.transfer.ConflictDecision
import com.wantox86.kangfilex.domain.transfer.TransferOperation
import com.wantox86.kangfilex.presentation.ui.theme.KangColors
import com.wantox86.kangfilex.util.Formatters

@Composable
fun TransferDialogsHost(
    state: TransferUiState,
    onMinimize: () -> Unit,
    onCancel: () -> Unit,
    onResolveConflict: (ConflictDecision) -> Unit
) {
    val conflict = state.pendingConflict
    val activeJob = state.activeJob
    when {
        conflict != null -> {
            ConflictResolutionDialog(
                info = conflict,
                onDecision = onResolveConflict
            )
        }
        state.isProgressDialogVisible && activeJob != null -> {
            TransferProgressDialog(
                job = activeJob,
                queuedCount = state.queuedCount,
                onMinimize = onMinimize,
                onCancel = onCancel
            )
        }
    }
}

@Composable
fun TransferProgressDialog(
    job: TransferJobUi,
    queuedCount: Int,
    onMinimize: () -> Unit,
    onCancel: () -> Unit
) {
    val progress = job.progress
    val titleText = when (job.operation) {
        TransferOperation.COPY -> "Menyalin File"
        TransferOperation.MOVE -> "Memindahkan File"
    }

    val currentFileText = progress?.currentFileName?.takeIf { it.isNotBlank() }
        ?: "Menyiapkan transfer..."

    val fileCountText = if (progress != null) {
        "${progress.filesCompleted} / ${progress.totalFiles} file"
    } else {
        "Menghitung file..."
    }

    val fraction: Float? = when {
        progress == null -> null
        progress.totalBytes > 0L -> {
            val clamped = progress.bytesTransferred.coerceIn(0L, progress.totalBytes)
            (clamped.toDouble() / progress.totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        }
        progress.totalFiles > 0 -> {
            val clamped = progress.filesCompleted.coerceIn(0, progress.totalFiles)
            (clamped.toFloat() / progress.totalFiles.toFloat()).coerceIn(0f, 1f)
        }
        else -> 0f
    }

    val bytesText = if (progress != null) {
        "${Formatters.formatFileSize(progress.bytesTransferred)} / ${Formatters.formatFileSize(progress.totalBytes)}"
    } else {
        "0 B / 0 B"
    }

    val speedText = if (progress != null) {
        "${Formatters.formatFileSize(progress.speedBytesPerSec.coerceAtLeast(0L))}/s"
    } else {
        "0 B/s"
    }

    AlertDialog(
        onDismissRequest = onMinimize,
        containerColor = KangColors.Surface,
        titleContentColor = KangColors.TextPrimary,
        textContentColor = KangColors.TextSecondary,
        title = {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = KangColors.TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = currentFileText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KangColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = fileCountText,
                    style = MaterialTheme.typography.bodySmall,
                    color = KangColors.TextSecondary
                )

                if (fraction == null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = KangColors.AccentOrange,
                        trackColor = KangColors.SurfaceElevated
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = KangColors.AccentOrange,
                        trackColor = KangColors.SurfaceElevated
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = bytesText,
                        style = MaterialTheme.typography.bodySmall,
                        color = KangColors.TextSecondary
                    )
                    Text(
                        text = speedText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = KangColors.AccentOrange
                    )
                }

                if (queuedCount > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$queuedCount tugas menunggu di antrean",
                        style = MaterialTheme.typography.labelSmall,
                        color = KangColors.TextMuted
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(
                    containerColor = KangColors.ErrorRed,
                    contentColor = KangColors.TextPrimary
                )
            ) {
                Text("Cancel", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onMinimize) {
                Text("Minimize", color = KangColors.TextSecondary)
            }
        }
    )
}
