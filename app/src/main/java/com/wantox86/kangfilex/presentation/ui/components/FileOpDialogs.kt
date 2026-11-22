package com.wantox86.kangfilex.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wantox86.kangfilex.presentation.model.FileOpDialogState
import com.wantox86.kangfilex.presentation.ui.theme.KangColors

@Composable
fun FileOpDialogHost(
    state: FileOpDialogState?,
    onNewFolderConfirm: (String) -> Unit,
    onRenameConfirm: (String) -> Unit,
    onDeleteConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        null -> Unit
        is FileOpDialogState.NewFolder -> {
            NewFolderDialog(
                state = state,
                onConfirm = onNewFolderConfirm,
                onDismiss = onDismiss
            )
        }
        is FileOpDialogState.Rename -> {
            RenameDialog(
                state = state,
                onConfirm = onRenameConfirm,
                onDismiss = onDismiss
            )
        }
        is FileOpDialogState.DeleteConfirm -> {
            DeleteConfirmDialog(
                state = state,
                onConfirm = onDeleteConfirm,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
fun NewFolderDialog(
    state: FileOpDialogState.NewFolder,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var folderName by remember(state.parentPath) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KangColors.Surface,
        titleContentColor = KangColors.TextPrimary,
        textContentColor = KangColors.TextSecondary,
        title = {
            Text(
                text = "Folder Baru",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = KangColors.TextPrimary
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Lokasi: ${state.parentPath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = KangColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Nama folder") },
                    isError = state.error != null,
                    supportingText = state.error?.let { errorText ->
                        {
                            Text(
                                text = errorText,
                                color = KangColors.ErrorRed,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(folderName) }),
                    colors = dialogTextFieldColors()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(folderName) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = KangColors.AccentOrange,
                    contentColor = KangColors.Background
                )
            ) {
                Text("Buat", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", color = KangColors.TextSecondary)
            }
        }
    )
}

@Composable
fun RenameDialog(
    state: FileOpDialogState.Rename,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val initialSelectionEnd = remember(state.file.path) {
        if (state.file.isDirectory) {
            state.file.name.length
        } else {
            val dotIndex = state.file.name.lastIndexOf('.')
            if (dotIndex > 0) dotIndex else state.file.name.length
        }
    }
    var textFieldValue by remember(state.file.path) {
        mutableStateOf(
            TextFieldValue(
                text = state.file.name,
                selection = TextRange(0, initialSelectionEnd)
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KangColors.Surface,
        titleContentColor = KangColors.TextPrimary,
        textContentColor = KangColors.TextSecondary,
        title = {
            Text(
                text = "Ubah Nama",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = KangColors.TextPrimary
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Nama baru") },
                    isError = state.error != null,
                    supportingText = state.error?.let { errorText ->
                        {
                            Text(
                                text = errorText,
                                color = KangColors.ErrorRed,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(textFieldValue.text) }),
                    colors = dialogTextFieldColors()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(textFieldValue.text) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = KangColors.AccentOrange,
                    contentColor = KangColors.Background
                )
            ) {
                Text("Simpan", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", color = KangColors.TextSecondary)
            }
        }
    )
}

@Composable
fun DeleteConfirmDialog(
    state: FileOpDialogState.DeleteConfirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val count = state.items.size
    val summaryText = if (count == 1) {
        "Apakah Anda yakin ingin menghapus \"${state.items.first().name}\" (1 item)?"
    } else {
        "Apakah Anda yakin ingin menghapus $count item terpilih?"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KangColors.Surface,
        titleContentColor = KangColors.TextPrimary,
        textContentColor = KangColors.TextSecondary,
        title = {
            Text(
                text = "Konfirmasi Hapus",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = KangColors.TextPrimary
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KangColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (state.hasDirectories) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(KangColors.ErrorRed.copy(alpha = 0.14f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Peringatan",
                            tint = KangColors.ErrorRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Peringatan: Folder akan dihapus secara rekursif beserta seluruh isinya. Tindakan ini tidak dapat dibatalkan.",
                            style = MaterialTheme.typography.bodySmall,
                            color = KangColors.ErrorRed
                        )
                    }
                } else {
                    Text(
                        text = "Tindakan ini tidak dapat dibatalkan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KangColors.TextMuted
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = KangColors.ErrorRed,
                    contentColor = KangColors.TextPrimary
                )
            ) {
                Text("Hapus", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", color = KangColors.TextSecondary)
            }
        }
    )
}

@Composable
private fun dialogTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = KangColors.TextPrimary,
    unfocusedTextColor = KangColors.TextPrimary,
    focusedContainerColor = KangColors.SurfaceElevated,
    unfocusedContainerColor = KangColors.SurfaceElevated,
    errorContainerColor = KangColors.SurfaceElevated,
    cursorColor = KangColors.AccentOrange,
    focusedBorderColor = KangColors.AccentOrange,
    unfocusedBorderColor = KangColors.Divider,
    errorBorderColor = KangColors.ErrorRed,
    focusedLabelColor = KangColors.AccentOrange,
    unfocusedLabelColor = KangColors.TextSecondary,
    errorLabelColor = KangColors.ErrorRed
)
