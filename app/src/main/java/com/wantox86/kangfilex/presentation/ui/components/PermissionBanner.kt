package com.wantox86.kangfilex.presentation.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wantox86.kangfilex.presentation.ui.theme.KangColors

@Composable
fun PermissionBanner(
    onRequestPermissionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(10.dp),
        colors = CardDefaults.cardColors(containerColor = KangColors.SurfaceElevated),
        border = BorderStroke(1.dp, KangColors.AccentOrange),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = KangColors.AccentOrange,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Izin Akses Semua File Diperlukan",
                    style = MaterialTheme.typography.titleSmall,
                    color = KangColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "KangFileX membutuhkan izin All Files Access agar dapat menjelajahi Internal Storage & SD Card secara penuh.",
                style = MaterialTheme.typography.bodySmall,
                color = KangColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onRequestPermissionClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = KangColors.AccentOrange,
                    contentColor = KangColors.Background
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "Buka Pengaturan Izin",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
