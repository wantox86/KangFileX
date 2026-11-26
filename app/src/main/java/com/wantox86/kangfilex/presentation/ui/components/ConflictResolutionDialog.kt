package com.wantox86.kangfilex.presentation.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wantox86.kangfilex.domain.model.VfsFile
import com.wantox86.kangfilex.domain.transfer.ConflictAction
import com.wantox86.kangfilex.domain.transfer.ConflictDecision
import com.wantox86.kangfilex.domain.transfer.ConflictInfo
import com.wantox86.kangfilex.presentation.ui.theme.KangColors
import com.wantox86.kangfilex.util.Formatters

@Composable
fun ConflictResolutionDialog(
    info: ConflictInfo,
    onDecision: (ConflictDecision) -> Unit
) {
    var applyToAll by remember(info) { mutableStateOf(false) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        containerColor = KangColors.Surface,
        titleContentColor = KangColors.TextPrimary,
        textContentColor = KangColors.TextSecondary,
        title = {
            Text(
                text = "File Sudah Ada",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = KangColors.TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = info.sourceFile.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = KangColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (isLandscape) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FileComparisonCard(
                            label = "Sumber",
                            file = info.sourceFile,
                            modifier = Modifier.weight(1f)
                        )

                        FileComparisonCard(
                            label = "Tujuan",
                            file = info.existingDest,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    FileComparisonCard(
                        label = "Sumber",
                        file = info.sourceFile
                    )

                    FileComparisonCard(
                        label = "Tujuan",
                        file = info.existingDest
                    )
                }

                HorizontalDivider(color = KangColors.Divider, thickness = 1.dp)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { applyToAll = !applyToAll }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = applyToAll,
                        onCheckedChange = { applyToAll = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = KangColors.AccentOrange,
                            uncheckedColor = KangColors.TextSecondary,
                            checkmarkColor = KangColors.Background
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Terapkan ke semua konflik berikutnya",
                        style = MaterialTheme.typography.bodySmall,
                        color = KangColors.TextPrimary
                    )
                }
            }
        },
        confirmButton = {
            if (isLandscape) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            onDecision(
                                ConflictDecision(
                                    action = ConflictAction.OVERWRITE,
                                    applyToAll = applyToAll
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = KangColors.AccentOrange,
                            contentColor = KangColors.Background
                        )
                    ) {
                        Text(
                            text = "Timpa",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            onDecision(
                                ConflictDecision(
                                    action = ConflictAction.AUTO_RENAME,
                                    applyToAll = applyToAll
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        border = BorderStroke(1.dp, KangColors.Divider),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = KangColors.TextPrimary
                        )
                    ) {
                        Text(
                            text = "Ganti Nama",
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    TextButton(
                        onClick = {
                            onDecision(
                                ConflictDecision(
                                    action = ConflictAction.SKIP,
                                    applyToAll = applyToAll
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Lewati",
                            color = KangColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onDecision(
                                ConflictDecision(
                                    action = ConflictAction.OVERWRITE,
                                    applyToAll = applyToAll
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = KangColors.AccentOrange,
                            contentColor = KangColors.Background
                        )
                    ) {
                        Text("Timpa", fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = {
                            onDecision(
                                ConflictDecision(
                                    action = ConflictAction.AUTO_RENAME,
                                    applyToAll = applyToAll
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, KangColors.Divider),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = KangColors.TextPrimary
                        )
                    ) {
                        Text("Ganti Nama Otomatis", fontWeight = FontWeight.Medium)
                    }

                    TextButton(
                        onClick = {
                            onDecision(
                                ConflictDecision(
                                    action = ConflictAction.SKIP,
                                    applyToAll = applyToAll
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Lewati", color = KangColors.TextSecondary)
                    }
                }
            }
        }
    )
}

@Composable
private fun FileComparisonCard(
    label: String,
    file: VfsFile?,
    modifier: Modifier = Modifier
) {
    val sizeText = when {
        file == null -> "tidak diketahui"
        file.isDirectory -> "Folder"
        else -> Formatters.formatFileSize(file.size)
    }
    val modifiedText = when {
        file == null -> "tidak diketahui"
        else -> Formatters.formatTimestamp(file.lastModified).ifBlank { "tidak diketahui" }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(KangColors.SurfaceElevated)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = KangColors.AccentOrange
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Ukuran: $sizeText",
            style = MaterialTheme.typography.bodySmall,
            color = KangColors.TextPrimary
        )
        Text(
            text = "Diubah: $modifiedText",
            style = MaterialTheme.typography.bodySmall,
            color = KangColors.TextSecondary
        )
    }
}
