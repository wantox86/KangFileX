# KangFileX

**KangFileX** adalah aplikasi *Dual-Pane Network File Manager* untuk Android (terinspirasi dari X-plore File Manager) yang dibangun dengan **Kotlin + Jetpack Compose** dan arsitektur **Clean Architecture + MVVM**.

Spesifikasi lengkap ada di [docs/SPEC.md](docs/SPEC.md) dan rencana detail Phase 2–4 + Post-MVP (acuan Workflow Code Factory) ada di [docs/PHASE_PLAN.md](docs/PHASE_PLAN.md).

## Fitur Utama
- **Dual-Pane Layout Responsif**:
  - **Portrait**: Gesture swipe kiri-kanan halus antara Pane Kiri & Pane Kanan dengan **Center Action Bar** yang ikut bergeser menempel di sisi layar.
  - **Landscape / Tablet**: Side-by-side `Pane Kiri (45%) | Center Action Bar (10%) | Pane Kanan (45%)`.
- **Inline Tree-View Explorer**: Expand/collapse folder secara inline dengan garis hierarki, indentasi kedalaman, highlight border oranye untuk folder tujuan aktif, dan checkbox 1 sentuhan di sisi kanan untuk multi-select.
- **Breadcrumb & Storage Header**: Breadcrumb horizontal clickable + info kapasitas penyimpanan (`StatFs`) dan jumlah subfolder/file.
- **Abstraksi VFS (Virtual File System)**: Interface seragam `VfsProvider` untuk Local Storage, SMB, FTP/FTPS, dan SSH/SFTP.

## Roadmap 4 Phase (MVP)
| Phase | Fokus | Status |
| :--- | :--- | :--- |
| **Phase 1: Fondasi & UI Core** | Project Kotlin/Compose, `LocalVfsProvider`, Dual-Pane swipe + Center Action Bar, Tree-View, Multi-select, Breadcrumb, Storage Permission | ✅ Selesai |
| **Phase 2: Transfer Engine** | Copy/Move antar pane, Clipboard Copy/Paste, Rename, Delete, New Folder, Conflict Resolution Dialog, Foreground Service | 🔜 Berikutnya ([detail](docs/PHASE_PLAN.md#3-phase-2--transfer-engine--operasi-file-dasar)) |
| **Phase 3: SMB & FTP/FTPS** | Manajemen Server (Room + Encrypted Credentials), `SmbVfsProvider` (SMB2/3) & `FtpVfsProvider` | ⏳ Rencana ([detail](docs/PHASE_PLAN.md#4-phase-3--server-management-smb-v2v3--ftpftps)) |
| **Phase 4: SSH/SFTP & QA** | `SftpVfsProvider` (Password & Private Key + TOFU Host Key), uji transfer cross-protocol file besar, optimasi UI | ⏳ Rencana ([detail](docs/PHASE_PLAN.md#5-phase-4--sshsftp-hardening--qa-menyeluruh)) |

## Cara Build & Test

### Prasyarat
- JDK 17 / JDK 21
- Android SDK (`compileSdk = 35`, `minSdk = 29`, `targetSdk = 35`)

### Menjalankan Unit Test
```bash
./gradlew testDebugUnitTest
```

### Build Debug APK
```bash
./gradlew assembleDebug
```
Output APK berada di:
`app/build/outputs/apk/debug/app-debug.apk`
