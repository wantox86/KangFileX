package com.wantox86.kangfilex.presentation.model

import com.wantox86.kangfilex.domain.model.ProtocolType

enum class RootNetworkSection(
    val title: String,
    val subtitle: String,
    val protocol: ProtocolType,
    val targetPhase: String
) {
    SMB(
        title = "LAN (SMB)",
        subtitle = "Windows Share, NAS, Samba (v2/v3)",
        protocol = ProtocolType.SMB,
        targetPhase = "Phase 3"
    ),
    FTP(
        title = "FTP / FTPS",
        subtitle = "FTP, FTPES Explicit & FTPS Implicit",
        protocol = ProtocolType.FTP,
        targetPhase = "Phase 3"
    ),
    SFTP(
        title = "SSH / SFTP",
        subtitle = "Password & Private Key + TOFU",
        protocol = ProtocolType.SFTP,
        targetPhase = "Phase 4"
    )
}
