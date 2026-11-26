# KangFileX — Phase Plan (Phase 2, 3, 4 & Post-MVP)

Dokumen ini adalah rencana pengerjaan lanjutan **KangFileX** untuk dijalankan via **Workflow Code Factory** setelah Phase 1 selesai.
Spesifikasi produk acuan utama ada di [docs/SPEC.md](SPEC.md).

- **Package / Application ID:** `com.wantox86.kangfilex`
- **Platform:** Android Min SDK 29 (Android 10) / Compile & Target SDK 35 (Android 15)
- **Tech Stack Phase 1:** Kotlin `2.0.21`, AGP `8.7.3`, Jetpack Compose (BOM `2024.12.01`, Material 3), Coroutines `1.9.0`, Clean Architecture + MVVM

---

## 1. Status Project & Roadmap

| Phase | Fokus | Status | Branch / Referensi |
| :--- | :--- | :--- | :--- |
| **Phase 1: Fondasi & UI Core** | Setup Kotlin/Compose, `LocalVfsProvider`, Dual-Pane swipe portrait + side-by-side landscape, Center Action Bar, Tree-View flattened, multi-select, breadcrumb, `MANAGE_EXTERNAL_STORAGE` | ✅ **Selesai & QA PASS** | PR #1 (`feat/phase-1-core-ui`) — **merged** ke `main` |
| **Phase 2: Transfer Engine & Operasi File** | Copy/Move antar pane, Clipboard Copy & Paste, Rename, Delete, New Folder, Conflict Dialog, Streaming Transfer Engine, Foreground Service (`dataSync`) | ✅ **Selesai** (48 tes unit hijau, Review 2 ronde APPROVE, QA emulator `signpdf_qa_api34` lulus; sisa minor: tiket KF-8/KF-9) | PR #2 (`feat/phase-2-transfer-engine`) — **merged** ke `main` |
| **Phase 3: Server Management, SMB & FTP** | Multi-provider VFS registry, Room DB + Tink Credential Vault, UI `+ Add Server`, `SmbVfsProvider` (SMB2/3) & `FtpVfsProvider` (FTP/FTPES/FTPS) | ⏳ **Rencana** | Target branch: `feat/phase-3-smb-ftp` |
| **Phase 4: SSH/SFTP, Hardening & QA** | `SftpVfsProvider` (Password & SSH Key + TOFU Host Key), symlink loop guard, transfer >2 GB, optimasi UI, R8/ProGuard & `RELEASE.md` | ⏳ **Rencana** | Target branch: `feat/phase-4-sftp-hardening` |
| **Post-MVP (Fase 2 Produk)** | Disk Map, WiFi/FTP Server, Archive Explorer, Text/Hex Editor, Cloud Storage, Root Explorer | 📋 **Backlog** | Dikerjakan hanya setelah MVP Phase 4 stabil |

---

## 2. Panduan Environment & Kontrak Teknis (Code Factory)

### 2.1 Environment Build & Emulator QA (MACMINI)
- **Path repo lokal:** `/Volumes/ADATA/Documents/Github/KangFileX` *(hindari scan langsung lewat `~/Documents`)*
- **Environment variables:**
  ```bash
  export JAVA_HOME="/Users/user1/Library/Java/JavaVirtualMachines/azul-21.0.11/Contents/Home"
  export ANDROID_HOME="/Volumes/ADATA/Android/sdk"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  export ANDROID_AVD_HOME="/Volumes/ADATA/Android/avd"
  export PATH="/opt/homebrew/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
  ```
- **AVD QA:** `signpdf_qa_api34` (API 34, 1080×2400)
- **Command verifikasi wajib tiap phase:**
  ```bash
  ./gradlew testDebugUnitTest assembleDebug
  ```
  Output APK debug: `app/build/outputs/apk/debug/app-debug.apk`.

### 2.2 Aturan Git & Pull Request
1. **Jangan pernah push langsung ke `main`.**
2. Setiap phase wajib dimulai dari branch baru berbasis `main` terbaru setelah phase sebelumnya di-merge (mis. `feat/phase-2-transfer-engine`).
3. Push non-interactive memakai helper `gh`:
   ```bash
   GIT_TERMINAL_PROMPT=0 git -c credential.helper= -c 'credential.helper=!/opt/homebrew/bin/gh auth git-credential' push -u origin <branch>
   ```
4. Buat PR ke `main` dengan section **"Cara verifikasi untuk QA"**. Merge ke `main` dilakukan oleh user.
5. **Keamanan:** dilarang keras menulis atau meng-commit credential, password, token, API key, atau `local.properties` ke kode, log, tes, maupun PR.

### 2.3 Kontrak Arsitektur Phase 1 yang Wajib Dipertahankan
- **Portrait Dual-Pane (`DualPaneScreen.kt`):** `PortraitSwipeDualPaneContent` memakai custom `Layout` berbasis pixel (`Constraints.fixed` & placement eksplisit `[0, W-A]` / `[A, W]`). **Jangan** dikembalikan ke `Row(Modifier.requiredWidth(...))` karena di Compose overflow `requiredWidth` akan di-center dan memotong pane.
- **Sync `Sel All` / `Clear` (`TreeStateManager.kt` & `CenterActionBar.kt`):** `TreeStateManager.isClearActionMode()` adalah *single source of truth* untuk `PaneUiState.isClearActionMode` maupun aksi `toggleSelectAllOrClear()`.
- **Offload I/O & Lifecycle (`DualPaneViewModel.kt` & `DualPaneScreen.kt`):** seluruh disk/network I/O wajib di-offload ke `ioDispatcher`; UI mengonsumsi state lewat `collectAsStateWithLifecycle()`.
- **Kontras Status Bar (`MainActivity.kt` & `themes.xml`):** pertahankan `enableEdgeToEdge(SystemBarStyle.dark(...))` dan `isAppearanceLightStatusBars = false` agar ikon status bar terbaca di tema gelap.

---

## 3. Phase 2 — Transfer Engine & Operasi File Dasar

- **Prasyarat:** PR #1 (`feat/phase-1-core-ui`) sudah di-merge ke `main`.
- **Branch:** `feat/phase-2-transfer-engine` (dari `main`).
- **Tujuan:** Mengaktifkan seluruh operasi file lokal dan transfer streaming cross-pane melalui Foreground Service dengan penanganan konflik dan pembatalan yang aman.

### 3.1 Breakdown Task (P2-1 s/d P2-7)

#### `P2-1` — Operasi File Langsung (New Folder, Rename, Delete)
- **New Folder:** dialog input nama folder di `focusedPath` pane aktif (`VfsProvider.createDirectory`).
- **Rename:** aktif saat tepat 1 item dipilih di pane aktif (jika >1 item atau 0 item, beri pesan/state jelas); dialog prefilled dengan nama item saat ini (`VfsProvider.rename`).
- **Delete:** mendukung single & multi-select (baik file maupun folder rekursif). Wajib menampilkan **dialog konfirmasi** berisi jumlah item terpilih dan peringatan khusus bila ada direktori yang akan dihapus rekursif.
- **Validasi nama:** tolak nama kosong/blank, nama mengandung `/`, serta `.` atau `..`, dan tampilkan error validasi inline di dialog sebelum memanggil provider.
- Auto-refresh pane aktif dan pane sebelah (jika sedang menampilkan path yang sama/terdampak) serta bersihkan selection setelah operasi sukses.

#### `P2-2` — Internal Clipboard (`Copy to Clipboard` & `Paste`)
- Buat model `ClipboardState` (`items: List<VfsFile>`, `sourceProviderId: String`, `operation: ClipboardOperation` — `COPY` / `CUT` siap pakai, tombol `Clip` Phase 2 mengisi `COPY`).
- Hubungkan ke `MainUiState.hasClipboardItems` dan tambahkan metadata ringan bila perlu (misal jumlah item clipboard di UI/header atau action bar + opsi clear clipboard).
- Tombol `Paste` di `CenterActionBar` aktif saat `hasClipboardItems == true` dan menempelkan item dari clipboard ke `focusedPath` pane aktif melalui `TransferEngine` (di Phase 2 fokus pada Local Storage; struktur tetap memakai `providerId` agar siap multi-provider di Phase 3).

#### `P2-3` — Streaming Transfer Engine
- Buat `TransferEngine` berbasis `VfsProvider` (`openInputStream`, `openOutputStream`, `listFiles`, `createDirectory`, `delete`, `rename`, `exists`):
  - Streaming per blok buffer **`64 KB`–`256 KB`** (default misal `128 KB`) tanpa menulis file sementara di internal storage — siap untuk file berukuran >4 GB.
  - Gunakan tipe **`Long`** untuk seluruh kalkulasi ukuran file, total bytes, dan byte progress (anti 32-bit overflow).
  - Ekspansi folder secara rekursif saat sumber berisi direktori (hitung daftar entry & total bytes di fase persiapan sebelum copy).
  - **Self-copy / self-subfolder guard:** tolak Copy/Move apabila direktori sumber sama persis dengan target atau target berada di dalam direktori sumber (`targetPath == sourcePath || targetPath.startsWith("$sourcePath/")` pada `providerId` yang sama).
  - **Move optimization:** untuk `Move` pada `providerId` & filesystem yang sama, coba fast-path `VfsProvider.rename`; jika gagal (misal lintas volume/SD card atau lintas protokol di Phase 3–4), fallback ke streaming copy + verifikasi sukses + hapus sumber.
  - **Progress tracking:** `TransferProgress` berisi `jobId`, `operation`, `currentFileName`, `filesCompleted`, `totalFiles`, `bytesTransferred`, `totalBytes`, `speedBytesPerSec`.
  - **Cancel & Partial File Cleanup:** jika job dibatalkan (`CancellationException`) atau gagal di tengah streaming file, **tutup stream dan hapus file parsial yang belum selesai ditulis di tujuan** (`delete(destFilePath, recursive = false)`) lalu bersihkan resource dengan aman.

#### `P2-4` — Conflict Resolution Dialog
- Buat `ConflictAction` (`OVERWRITE`, `SKIP`, `AUTO_RENAME`) dan `ConflictDecision(action: ConflictAction, applyToAll: Boolean)`.
- Jika file tujuan sudah ada (`VfsProvider.exists`):
  - Jika user belum memilih *Apply to all*, suspend transfer sementara menggunakan `CompletableDeferred<ConflictDecision>` dan tampilkan `ConflictResolutionDialog` (menampilkan nama file, ukuran & waktu modifikasi sumber vs tujuan).
  - `OVERWRITE`: buka output stream dengan `overwrite = true`.
  - `SKIP`: lewati file ini (jangan hapus sumber bila operasi `Move`).
  - `AUTO_RENAME`: generate nama unik `nama (1).ext`, `nama (2).ext`, atau `folder (1)` untuk folder konflik tanpa menghapus file lama.
  - Checkbox **"Terapkan ke semua konflik berikutnya" (`applyToAll`)** berlaku untuk sisa item dalam job aktif.

#### `P2-5` — Android Foreground Service & Transfer Queue
- Update `AndroidManifest.xml`:
  - Permission `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, dan `POST_NOTIFICATIONS`.
  - Daftarkan `<service android:name=".service.TransferForegroundService" android:exported="false" android:foregroundServiceType="dataSync" />`.
- Request runtime permission `POST_NOTIFICATIONS` di `MainActivity` untuk Android 13+ (API 33+) secara non-blocking saat mulai transfer.
- Buat `TransferQueueManager` (singleton/scoped di app container agar ViewModel & Service berbagi state yang sama lewat `StateFlow<TransferUiState>`) untuk antrean job berurutan + pending conflict prompt + status selesai/gagal/dibatalkan.
- Implementasi `TransferForegroundService`:
  - Channel notifikasi progres (importance low, non-intrusive) dan summary selesai.
  - Notifikasi persisten menampilkan nama file aktif, persentase progres, kecepatan transfer (`MB/s` / `KB/s`), serta tombol **Cancel** lewat `PendingIntent` action ke Service.
- Buat `TransferProgressDialog`:
  - Menampilkan file aktif, item `x / y`, progress bar, bytes/total, kecepatan transfer, tombol **Minimize** (tutup dialog, transfer tetap lanjut di background + banner/indikator mini opsional), dan tombol **Cancel**.

#### `P2-6` — Integrasi `CenterActionBar`, `DualPaneScreen` & `DualPaneViewModel`
- Ganti `onPhase2ActionClick` placeholder di `CenterActionBar.kt`, `DualPaneScreen.kt`, dan `DualPaneViewModel.kt` menjadi aksi nyata:
  - `Copy`: salin item terpilih dari Pane Aktif ke `focusedPath` Pane Sebelah (validasi `selectedIds` tidak kosong & target `focusedPath` valid).
  - `Move`: pindahkan item terpilih dari Pane Aktif ke `focusedPath` Pane Sebelah.
  - `Clip`: simpan item terpilih dari Pane Aktif ke `ClipboardState`.
  - `Paste`: tempel item dari `ClipboardState` ke `focusedPath` Pane Aktif.
  - `Rename`, `Delete`, `New Dir`: buka dialog terkait lalu eksekusi.
- Auto-refresh pane kiri dan kanan setelah transfer atau operasi file selesai (tetap mempertahankan node yang sedang ter-expand sebisa mungkin).

#### `P2-7` — Unit Tests Phase 2
- `TransferEngineTest`:
  - Copy single file & nested directory dengan verifikasi isi byte-per-byte.
  - Move fast-path `rename` & fallback streaming copy + delete sumber.
  - Conflict handling: `OVERWRITE`, `SKIP` (termasuk memastikan sumber `Move` tidak terhapus saat di-skip), `AUTO_RENAME` (`file (1).txt`, `file (2).txt`), dan `applyToAll = true`.
  - Anti-self-copy/move ke subfolder sendiri.
  - Cancel di tengah streaming → `CancellationException` tertangani bersih & **file parsial tujuan terhapus**.
- Perluasan `DualPaneViewModelTest`:
  - New Folder, Rename (validasi & eksekusi), Delete (konfirmasi & hapus), Clipboard Copy → Paste.

### 3.2 Komponen & File Target Phase 2
- **Modifikasi:**
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/java/com/wantox86/kangfilex/MainActivity.kt`
  - `app/src/main/java/com/wantox86/kangfilex/data/local/LocalVfsProvider.kt` (verifikasi `createDirectory`, `delete`, `rename`, `openOutputStream(overwrite)` untuk edge case folder/file konflik)
  - `app/src/main/java/com/wantox86/kangfilex/presentation/model/PaneUiState.kt`
  - `app/src/main/java/com/wantox86/kangfilex/presentation/tree/TreeStateManager.kt`
  - `app/src/main/java/com/wantox86/kangfilex/presentation/viewmodel/DualPaneViewModel.kt`
  - `app/src/main/java/com/wantox86/kangfilex/presentation/ui/DualPaneScreen.kt`
  - `app/src/main/java/com/wantox86/kangfilex/presentation/ui/components/CenterActionBar.kt`
- **File Baru (disarankan):**
  - `app/src/main/java/com/wantox86/kangfilex/domain/transfer/TransferModels.kt`
  - `app/src/main/java/com/wantox86/kangfilex/domain/transfer/ConflictResolver.kt`
  - `app/src/main/java/com/wantox86/kangfilex/domain/transfer/TransferEngine.kt`
  - `app/src/main/java/com/wantox86/kangfilex/data/transfer/TransferQueueManager.kt`
  - `app/src/main/java/com/wantox86/kangfilex/service/TransferForegroundService.kt`
  - `app/src/main/java/com/wantox86/kangfilex/presentation/ui/components/FileOpDialogs.kt`
  - `app/src/main/java/com/wantox86/kangfilex/presentation/ui/components/ConflictResolutionDialog.kt`
  - `app/src/main/java/com/wantox86/kangfilex/presentation/ui/components/TransferProgressDialog.kt`
  - `app/src/test/java/com/wantox86/kangfilex/domain/transfer/TransferEngineTest.kt`

### 3.3 Acceptance Criteria (Definition of Done)
- Seluruh 7 tombol operasi file Phase 2 (`Copy`, `Move`, `Clip`, `Paste`, `Rename`, `Delete`, `New Dir`) aktif dan berfungsi nyata tanpa placeholder.
- Copy/Move/Paste folder bersarang berjalan secara streaming (tanpa temp file) di dalam `TransferForegroundService` dengan notifikasi progres + tombol Cancel.
- Konflik file memunculkan dialog `Overwrite` / `Skip` / `Auto-Rename` + checkbox `Apply to all` dan hasilnya akurat.
- Pembatalan transfer di tengah jalan membersihkan file parsial di direktori tujuan.
- Self-copy/move folder ke subfoldernya sendiri ditolak dengan pesan error jelas.
- `./gradlew testDebugUnitTest assembleDebug` PASS 100% dan QA emulator PASS.

### 3.4 Skenario QA Emulator (`signpdf_qa_api34`)
1. **New Folder & Validasi:** Buat folder baru di `/sdcard/KangFileX_QA2/src`, coba input kosong/mengandung `/` (ditolak), input valid `sub_test` (berhasil & langsung muncul di tree).
2. **Rename:** Pilih 1 file/folder → tap `Rename` → ubah nama → verifikasi nama berubah di UI & filesystem via `adb shell ls`.
3. **Delete:** Pilih 1 file + 1 folder berisi file → tap `Delete` → verifikasi dialog konfirmasi tampil jumlah item → konfirmasi hapus → verifikasi hilang dari UI & filesystem.
4. **Copy & Move antar Pane (Portrait & Landscape):** Buka `src` di Pane L dan `dst` di Pane R. Multi-select 2 file + 1 folder di Pane L → `Copy` → swipe ke Pane R → verifikasi semua file & subfolder tersalin utuh dan sumber tetap ada. Ulangi dengan `Move` → verifikasi file berpindah & sumber terhapus.
5. **Clipboard Copy & Paste:** Pilih item → tap `Clip` → verifikasi tombol `Paste` aktif → pindah ke folder lain di Pane L atau R → tap `Paste` → verifikasi tersalin.
6. **Conflict Resolution:** Salin file dengan nama sama ke folder tujuan:
   - Uji `Skip` (isi file lama tidak berubah),
   - Uji `Auto-Rename` (muncul `nama (1).ext`),
   - Uji `Overwrite` (isi file ter-update),
   - Uji multi-file conflict dengan centang **Apply to all**.
7. **Large File Streaming, Minimize & Cancel Cleanup:** Buat file dummy ~80 MB via `adb shell dd`, mulai `Copy` antar pane:
   - Verifikasi `TransferProgressDialog` tampil progres & kecepatan,
   - Tap **Minimize** lalu navigasi tree selagi notifikasi Foreground Service berjalan (`adb shell dumpsys activity services` & status bar notification),
   - Mulai ulang transfer file ~80 MB lalu tekan **Cancel** di tengah jalan → verifikasi transfer berhenti dan **tidak ada file parsial tertinggal** di folder tujuan.
8. **Logcat Audit:** 0 `FATAL EXCEPTION`, 0 `AndroidRuntime`, 0 `ANR`.

---

## 4. Phase 3 — Server Management, SMB (v2/v3) & FTP/FTPS

- **Prasyarat:** Phase 2 sudah di-merge ke `main`.
- **Branch:** `feat/phase-3-smb-ftp` (dari `main`).
- **Tujuan:** Menambahkan manajemen server persisten dengan penyimpanan credential terenkripsi serta driver protokol **SMB v2/v3** dan **FTP/FTPS** yang terintegrasi mulus ke Dual-Pane Tree-View & Streaming Transfer Engine.

### 4.1 Breakdown Task (P3-1 s/d P3-7)

#### `P3-1` — Dependensi & Multi-Provider Tree Architecture
- Tambahkan ke `gradle/libs.versions.toml` & `app/build.gradle.kts`:
  - **Room:** `androidx.room:room-runtime`, `androidx.room:room-ktx`, plugin KSP + `androidx.room:room-compiler`.
  - **Security:** `com.google.crypto.tink:tink-android` + Android Keystore (`AndroidKeysetManager`) untuk enkripsi *at-rest* tanpa memakai `EncryptedSharedPreferences` yang sudah deprecated.
  - **SMB:** `com.hierynomus:smbj`.
  - **FTP/FTPS:** `commons-net:commons-net`.
- Buat `VfsProviderRegistry` (`getProvider(providerId): VfsProvider`, `register/unregister`, `connectServer`, `disconnectServer`).
- Refactor `TreeStateManager` & `DualPaneViewModel` agar mendukung **multi-provider root** dalam satu pane (Local Storage + node server SMB/FTP/SFTP tersimpan):
  - Node ID tetap unik dengan format `"$providerId:$path"` (sehingga 2 server berbeda dengan path `/` tidak bertabrakan).
  - Breadcrumb & Storage Header beradaptasi menampilkan URI protokol (misal `smb://192.168.1.5/Share/docs` atau `ftp://host:21/pub`).

#### `P3-2` — Room Database & Credential Vault
- Perluas model domain:
  - `ProtocolType`: pastikan membedakan `LOCAL`, `SMB`, `FTP`, `FTPES` (Explicit TLS), `FTPS` (Implicit TLS), `SFTP` (atau simpan `ftpsMode` eksplisit).
  - `ServerConfig`: tambahkan `domain: String? = null` (SMB), `anonymous: Boolean = false`, `createdAt: Long`, `updatedAt: Long`.
- Buat Room `AppDatabase`, `ServerEntity`, `ServerDao`, dan `ServerRepository`.
- Buat `CredentialVault` (Android Keystore + Google Tink AEAD):
  - Menyimpan secret (password server, dan nantinya di Phase 4 private key + passphrase) secara terenkripsi per `serverId` pada file/preferences terenkripsi lokal.
  - **Aturan keras:** Room hanya menyimpan metadata konfigurasi (`ServerEntity`), **tidak boleh** menyimpan plaintext password/key; logger/snackbar/error message juga **tidak boleh** mencetak credential.

#### `P3-3` — UI Manajemen Server di Root Node
- Aktifkan section `LAN (SMB)` & `FTP / FTPS` di `FilePane.kt`:
  - Menampilkan daftar server tersimpan di bawah masing-masing protokol beserta indikator status (`Connected` / `Disconnected`), tombol `+ Add Server`, serta menu item (`Connect`, `Disconnect`, `Edit`, `Delete`).
- Buat `ServerFormDialog` / bottom sheet sesuai tabel Section 3.1 `docs/SPEC.md`:
  - **SMB (LAN):** Label*, Host/IP*, Port (default `445`), Share Name, Domain, Anonymous/Guest toggle, Username, Password.
  - **FTP / FTPS:** Label*, Host/IP*, Tipe (`FTP` / `FTPES` Explicit / `FTPS` Implicit), Port (otomatis default `21` untuk FTP/FTPES dan `990` untuk FTPS Implicit), Anonymous toggle, Username, Password, Mode (`Passive` default / `Active`), Root Path (default `/`).
  - Sertakan tombol **Test Connection** sebelum Save dengan feedback error yang jelas (timeout, unreachable, auth failed, share not found).

#### `P3-4` — `SmbVfsProvider` (SMB v2 / v3 via `smbj`)
- Implementasi `VfsProvider` menggunakan `SMBClient`:
  - Dukungan SMB2 & SMB3, guest/anonymous (`AuthenticationContext.anonymous()`), username/password/domain.
  - Koneksi ke `DiskShare` berdasarkan `shareName` (jika `shareName` kosong, beri validasi jelas atau enumerasi jika memungkinkan, sesuai constraint `smbj`).
  - Implementasi penuh `listFiles`, `openInputStream`, `openOutputStream(overwrite)`, `createDirectory`, `delete(recursive)`, `rename`, `exists`.
  - Penanganan stream read/write yang aman (close `File` handle SMB saat stream ditutup!).
  - **Auto-reconnect:** cek `Session` / `DiskShare.isConnected()` sebelum setiap operasi; jika terputus akibat idle atau pergantian jaringan, reconnect otomatis 1x sebelum mengeksekusi perintah.

#### `P3-5` — `FtpVfsProvider` (FTP / FTPES / FTPS via `commons-net`)
- Implementasi `VfsProvider` menggunakan `FTPClient` (FTP) dan `FTPSClient(isImplicit)` (FTPES/FTPS):
  - Aktifkan `OPTS UTF8 ON` / `controlEncoding = "UTF-8"` sebelum `connect`/`login` untuk penanganan nama file Unicode & spasi.
  - Set `setFileType(FTP.BINARY_FILE_TYPE)` agar transfer file biner tidak korup.
  - Terapkan `enterLocalPassiveMode()` (default) atau `enterLocalActiveMode()` sesuai `ServerConfig.passiveMode`.
  - **Pitfall krusial `commons-net`:** `openInputStream` & `openOutputStream` wajib membungkus stream (`FilterInputStream` / `FilterOutputStream`) agar pada `close()` memanggil `client.completePendingCommand()` dan melepas mutex koneksi; tanpa ini koneksi FTP akan hang pada perintah berikutnya.
  - Pastikan thread-safety per instance `FtpVfsProvider` (karena satu `FTPClient` tidak thread-safe untuk concurrent commands) serta **auto-reconnect** via `sendNoOp()` / cek `isConnected()`.

#### `P3-6` — Integrasi Cross-Protocol Transfer (`Local <-> SMB <-> FTP`)
- Sambungkan `VfsProviderRegistry` ke `TransferEngine` dan `TransferForegroundService` agar operasi `Copy`, `Move`, `Paste` langsung mengambil source provider & destination provider berdasarkan `VfsFile.providerId`.
- Verifikasi streaming `Local <-> SMB`, `Local <-> FTP`, dan `SMB <-> FTP` berjalan tanpa file sementara lokal.

#### `P3-7` — Unit & Contract Tests Phase 3
- `CredentialVaultTest` / test enkripsi-dekripsi roundtrip & isolasi per `serverId`.
- `ServerDaoTest` / `ServerRepositoryTest` (verifikasi schema Room & memastikan tidak ada plaintext password tersimpan di entity).
- `VfsProviderRegistryTest` (resolve provider, disconnect, cleanup).
- Unit/contract test `SmbVfsProvider` & `FtpVfsProvider` (parsing listing, UTF-8 encoding, wrapper stream `completePendingCommand`, auto-reconnect logic, error handling) serta skenario cross-protocol di `TransferEngineTest` memakai dua fake/in-memory `VfsProvider` berbeda `providerId`.

### 4.2 Komponen & File Target Phase 3
- **Modifikasi:**
  - `gradle/libs.versions.toml`, `app/build.gradle.kts`
  - `domain/model/ProtocolType.kt`, `domain/model/ServerConfig.kt`
  - `domain/transfer/TransferEngine.kt` (resolusi dual-provider via `VfsProviderRegistry`)
  - `presentation/model/RootNetworkSection.kt`, `presentation/tree/TreeStateManager.kt`, `presentation/viewmodel/DualPaneViewModel.kt`
  - `presentation/ui/components/FilePane.kt`
- **File Baru (disarankan):**
  - `domain/vfs/VfsProviderRegistry.kt`
  - `data/db/AppDatabase.kt`, `data/db/ServerEntity.kt`, `data/db/ServerDao.kt`, `data/repository/ServerRepository.kt`
  - `data/security/CredentialVault.kt`
  - `data/smb/SmbVfsProvider.kt`
  - `data/ftp/FtpVfsProvider.kt`
  - `presentation/ui/components/ServerFormDialog.kt`
  - Unit tests di `app/src/test/java/com/wantox86/kangfilex/data/...`

### 4.3 Acceptance Criteria (Definition of Done)
- User dapat menambah, menguji koneksi, mengedit, connect/disconnect, dan menghapus server **SMB** dan **FTP/FTPES/FTPS** langsung dari section jaringan di masing-masing pane.
- Password tersimpan terenkripsi via Android Keystore + Tink (tidak pernah plaintext di DB Room ataupun log).
- Browsing Tree-View (expand/collapse, breadcrumb, multi-select) berjalan mulus untuk root SMB dan FTP/FTPS.
- Transfer streaming lintas protokol (`Local <-> SMB`, `Local <-> FTP`, `SMB <-> FTP`) berfungsi via Foreground Service termasuk pemulihan auto-reconnect saat idle/putus.
- `./gradlew testDebugUnitTest assembleDebug` PASS 100% dan QA emulator PASS.

### 4.4 Skenario QA Emulator (`signpdf_qa_api34`)
1. **CRUD Server & Storage Enkripsi:** Tambah, edit, dan hapus konfigurasi server SMB & FTP; inspeksi SQLite Room via `adb` (atau test) untuk memastikan kolom secret/password tidak ada yang plaintext.
2. **Test Koneksi SMB Nyata (LAN BMAX Read-Only / Temp Terisolasi):** Uji koneksi ke share SMB LAN (`192.168.50.163`, share `FilesShared` guest ok — **hanya untuk read listing atau subfolder QA khusus yang langsung dibersihkan; dilarang menyentuh data pribadi/produksi & jangan simpan credential nyata di repo/log**). Verifikasi browsing folder inline & header `smb://...`.
3. **Test Koneksi FTP / FTPS:** Jalankan FTP/FTPES test server sementara di host MACMINI (port test lokal), hubungkan dari emulator (`10.0.2.2`), uji `Passive` mode, listing nama file berisi spasi & karakter Unicode (`UTF-8`), New Folder, Rename, dan Delete.
4. **Cross-Protocol Streaming Transfer:**
   - Pane L (`Local`) ↔ Pane R (`SMB`) copy & move file + folder bersarang.
   - Pane L (`FTP`) ↔ Pane R (`SMB`) copy streaming langsung tanpa error atau hang (memastikan `completePendingCommand` bekerja).
5. **Auto-Reconnect & Network Drop:** Putus koneksi jaringan sementara di emulator atau restart FTP server uji, tunggu error/idle, lalu lakukan refresh/listing ulang — verifikasi provider melakukan auto-reconnect dan kembali normal tanpa crash.
6. **Logcat & Security Audit:** Pastikan 0 crash/ANR dan tidak ada password/secret yang tercetak di `adb logcat`.

---

## 5. Phase 4 — SSH/SFTP, Hardening & QA Menyeluruh

- **Prasyarat:** Phase 3 sudah di-merge ke `main`.
- **Branch:** `feat/phase-4-sftp-hardening` (dari `main`).
- **Tujuan:** Melengkapi dukungan protokol **SSH / SFTP** dengan autentikasi modern & keamanan TOFU, menuntaskan seluruh edge case (symlink loop, file >2 GB, performa folder besar), serta menyiapkan rilis MVP (`RELEASE.md` + R8/ProGuard).

### 5.1 Breakdown Task (P4-1 s/d P4-6)

#### `P4-1` — `SftpVfsProvider` (`com.github.mwiede:jsch`)
- Tambahkan dependency `com.github.mwiede:jsch` (fork modern JSch dengan dukungan Ed25519, RSA-SHA2, ECDSA).
- Implementasi `VfsProvider` untuk SFTP:
  - Autentikasi **Password** dan **SSH Private Key** (`.pem` / OpenSSH key, dengan/tanpa Passphrase).
  - Dukungan impor isi private key via Storage Access Framework (`ActivityResultContracts.OpenDocument`) di `ServerFormDialog`, lalu disimpan terenkripsi ke `CredentialVault` (jangan hanya menyimpan URI SAF yang bisa kedaluwarsa).
  - Penanganan `rootPath` default (misal `/` atau home directory), escaping/quoting path dengan spasi & karakter Unicode, encoding UTF-8, `SftpProgressMonitor` / buffered stream tanpa loading seluruh file ke RAM, dan **auto-reconnect** saat `Session` / `ChannelSftp` putus.

#### `P4-2` — Verifikasi Host Key TOFU (Trust On First Use)
- Buat tabel Room `KnownHostEntity` (`host`, `port`, `keyType`, `fingerprintSha256`, `trustedAt`) + `KnownHostDao`.
- Implementasi `TofuHostKeyRepository` yang mengaitkan `JSch` `HostKeyRepository` ke mekanisme callback UI:
  - **Koneksi pertama ke host baru:** tampilkan `TofuHostKeyDialog` berisi host, port, tipe kunci, dan **Fingerprint SHA-256** dengan tombol `Trust & Connect` / `Cancel`.
  - **Fingerprint cocok:** langsung lanjut koneksi tanpa prompt ulang.
  - **Fingerprint BERUBAH (Mismatch):** tampilkan dialog peringatan keras potensi MITM (*"Host Key Berubah!"*, tampilkan fingerprint lama vs baru), default **Reject / Disconnect**, dan opsi eksplisit *Replace Trusted Key* hanya jika user sadar melakukan konfirmasi.
- Sediakan menu/dialog **Kelola Known Hosts** untuk melihat & menghapus fingerprint host tersimpan.

#### `P4-3` — Proteksi Symbolic Link Loop & Edge Cases
- Pada `SftpVfsProvider.listFiles()`, isi `VfsFile.isSymlink` secara akurat dari `SftpATTRS.isLink` dan deteksi targetnya (`readlink` / `stat`) tanpa terjebak loop.
- Pada `TransferEngine` (Copy/Move rekursif) dan `SftpVfsProvider.delete(recursive = true)`:
  - Jika sebuah entry adalah symlink (`isSymlink == true`), **jangan telusuri secara rekursif** (hindari infinite loop jika ada symlink mengarah ke direktori induk); lewati atau tangani sesuai kebijakan aman dengan pencatatan yang jelas, serta simpan `visitedCanonicalPaths` sebagai pengaman ganda.
- Tampilkan indikator/badge visual symlink di `TreeNodeItem.kt`.

#### `P4-4` — UI Manajemen Server SSH/SFTP & Known Hosts
- Aktifkan section `SSH / SFTP` di `FilePane.kt` (sekarang sejajar dengan SMB & FTP).
- Perluas `ServerFormDialog` untuk tipe `SFTP`:
  - Host*, Port (default `22`), Auth Type (`Password` / `SSH Private Key`), Username*, Password atau pemilih File Private Key + Passphrase, Root Path.
  - Tombol **Test Connection** memicu alur TOFU Host Key jika host belum pernah dipercaya.

#### `P4-5` — Optimasi Performa UI & Transfer File Besar (>2 GB)
- **Optimasi `LocalVfsProvider.listFiles()`:** di Phase 1, `countDirectChildrenSafe(child)` memanggil `child.listFiles()` secara sinkron untuk setiap subfolder — pada folder dengan ratusan subfolder ini bisa lambat. Optimalkan dengan:
  - Menjadikan perhitungan metadata `childDirCount`/`childFileCount` opsional/lazy atau batch-terbatas/background update setelah listing utama tampil, sehingga listing awal langsung instan 60–120fps.
- **Performa Tree-View `LazyColumn`:** uji dan optimalkan `flatten()` + `TreeNodeItem` untuk folder berisi 1.000+ file agar scroll tetap mulus tanpa frame drop atau alokasi berlebih.
- **Transfer >2 GB:** audit seluruh pipeline (`VfsFile.size`, `TransferProgress`, `StorageHeader`, formatter, stream loop) agar 100% menggunakan `Long` dan memory footprint tetap konstan saat streaming file >2 GB (atau >4 GB).

#### `P4-6` — Hardening, R8/ProGuard & `RELEASE.md`
- Lengkapi `app/proguard-rules.pro` dengan keep rules yang diperlukan oleh `smbj`, `jsch`, `commons-net`, Room, Tink, dan BouncyCastle/security providers agar release build dengan `isMinifyEnabled = true` berjalan tanpa `ClassNotFoundException` / `NoSuchMethodException`.
- Uji build release (`./gradlew assembleRelease` atau debug-signed minified build) untuk memverifikasi R8 tidak merusak protokol jaringan maupun Room.
- Buat `RELEASE.md` di root repo berisi:
  - Langkah bump versi (`versionCode` / `versionName`),
  - Cara build APK/AAB release & signing (menggunakan keystore lokal di luar repo),
  - Verifikasi post-build,
  - Checklist deklarasi Google Play Console untuk `MANAGE_EXTERNAL_STORAGE` (*Core Functionality* File Manager) dan `FOREGROUND_SERVICE_DATA_SYNC`.
- Unit tests Phase 4 (`SftpVfsProviderTest`, `TofuHostKeyRepositoryTest`, symlink loop guard test, large-tree benchmark/test) + regresi penuh semua test Phase 1–4.

### 5.2 Komponen & File Target Phase 4
- **Modifikasi:**
  - `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/proguard-rules.pro`
  - `data/db/AppDatabase.kt` (tambah `KnownHostEntity` + migration)
  - `data/local/LocalVfsProvider.kt` (optimasi child count lazy/non-blocking)
  - `domain/transfer/TransferEngine.kt` (symlink loop guard & canonical visited check)
  - `presentation/ui/components/{FilePane.kt, ServerFormDialog.kt, TreeNodeItem.kt}`
- **File Baru (disarankan):**
  - `data/db/KnownHostEntity.kt`, `data/db/KnownHostDao.kt`
  - `data/sftp/SftpVfsProvider.kt`, `data/sftp/TofuHostKeyRepository.kt`
  - `presentation/ui/components/TofuHostKeyDialog.kt`, `presentation/ui/components/KnownHostsDialog.kt`
  - `RELEASE.md`
  - Unit tests di `app/src/test/java/com/wantox86/kangfilex/data/sftp/...`

### 5.3 Acceptance Criteria (Definition of Done)
- Koneksi SSH/SFTP bekerja dengan autentikasi Password maupun Private Key (`.pem` / Ed25519 / RSA-SHA2 + passphrase).
- Verifikasi TOFU Host Key meminta persetujuan saat koneksi perdana dan memblokir dengan peringatan tegas ketika fingerprint berubah.
- Symlink loop tidak menyebabkan infinite recursion atau crash saat browse, Copy, maupun Delete.
- Transfer file besar (>2 GB) dan cross-protocol (`SMB <-> SFTP`, `Local <-> SFTP`) berjalan stabil dengan memori datar dan progres akurat.
- Folder 1.000+ file ter-render cepat tanpa UI freeze; release build R8/ProGuard lolos verifikasi.
- `./gradlew testDebugUnitTest assembleDebug` PASS 100%, release/R8 build check PASS, dan QA emulator PASS.

### 5.4 Skenario QA Emulator (`signpdf_qa_api34`)
1. **SSH Test Server Sandbox (Host MACMINI):** Jalankan SSHD sandbox lokal sementara dengan user & key dummy khusus QA (termasuk 1 symlink loop sengaja `ln -s .. loop_parent` dan 1 file uji besar) — **jangan gunakan key pribadi/produksi**.
2. **TOFU Flow:**
   - Koneksi perdana via SFTP (Password) → verifikasi `TofuHostKeyDialog` muncul dengan SHA-256 fingerprint → klik Trust → berhasil browse.
   - Putus & sambung ulang → tidak muncul dialog lagi.
   - Simulasikan host key berubah di test server → verifikasi peringatan **Host Key Berubah** muncul dan koneksi ditolak secara default.
   - Uji dialog **Known Hosts** (lihat & hapus host tersimpan).
3. **Private Key Auth:** Impor dummy `.pem` / Ed25519 key (dengan passphrase) lewat file picker → test koneksi SFTP → sukses browse & transfer.
4. **Symlink Loop Guard:** Pada folder berisi symlink ke parent (`loop_parent`), uji `Copy` dan `Delete` rekursif — verifikasi operasi selesai cepat tanpa hang/OutOfMemory.
5. **Cross-Protocol & Stress Test:**
   - Transfer `SMB -> SFTP` dan `Local <-> SFTP` (termasuk pembatalan + cek partial file cleanup).
   - Buat 1.000+ file dummy di local storage emulator, ukur scroll & expand/collapse UI tetap responsif.
   - Uji transfer file besar (misal >2 GB via sparse file / streaming panjang di emulator jika storage mencukupi, atau file besar maksimal sesuai ruang AVD + pantau `adb shell dumpsys meminfo com.wantox86.kangfilex` agar heap stabil tidak bocor).
6. **Regresi Total & Logcat:** Jalankan ulang smoke test fitur Phase 1–3 (Dual-Pane portrait/landscape, multi-select, SMB, FTP, SFTP), pastikan 0 `FATAL EXCEPTION` & 0 `ANR`.

---

## 6. Post-MVP Backlog (Out-of-Scope Phase 2–4)

Sesuai Section 1.3 `docs/SPEC.md`, fitur-fitur berikut **dikeluarkan dari scope MVP** agar Phase 2–4 fokus dan cepat rilis. Masing-masing dapat dipecah menjadi phase tersendiri setelah `v1.0.0` stabil:

1. **Disk Map / Storage Analyzer** — visualisasi pemakaian ruang penyimpanan (treemap / breakdown per folder & kategori).
2. **Built-in WiFi Server / FTP Server** — menjadikan perangkat Android sebagai server HTTP/FTP sementara di LAN.
3. **Archive Explorer** — browse isi, ekstrak, dan buat arsip `ZIP`, `RAR`, dan `7z` secara streaming/inline di Tree-View.
4. **Built-in Text / Hex Editor & Media Viewer Lanjutan** — viewer gambar/audio/video dan editor teks/hex ringan langsung di dalam aplikasi.
5. **Cloud Storage Providers** — driver `VfsProvider` untuk Google Drive, Dropbox, OneDrive, dan S3-compatible object storage.
6. **Root Explorer & App Manager** — akses path root (pada perangkat rooted) dan manajemen/backup APK terpasang.

---

## 7. Catatan Risiko & Pitfall Teknis Lintas Phase

- **`commons-net` FTP Hang:** Jika `InputStream`/`OutputStream` FTP ditutup tanpa memanggil `FTPClient.completePendingCommand()`, perintah FTP selanjutnya akan menggantung tanpa error jelas. Wajib dibungkus pada stream wrapper di Phase 3.
- **Konflik Provider Crypto / `smbj` di Android:** `smbj` dan `jsch` membawa dependensi kriptografi (`BouncyCastle` / `EdDSA`); perhatikan potensi konflik class dengan platform Android serta siapkan packaging exclusions (`META-INF/*`) & R8 rules bila muncul duplikat saat build.
- **Limitasi Foreground Service Android 14+ (API 34/35):** `foregroundServiceType="dataSync"` wajib dideklarasikan baik di `AndroidManifest.xml` maupun saat memanggil `startForeground(..., ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)`, ditambah runtime `POST_NOTIFICATIONS` untuk API 33+.
- **Overflow 32-bit untuk File >2 GB:** `Int.MAX_VALUE` adalah ~2,14 GB; pastikan byte counter, `Content-Length`, progress calculation, dan seek/offset memakai `Long`.
- **Deteksi SD Card / SAF vs Raw File Path:** Di Android 11+, `MANAGE_EXTERNAL_STORAGE` memberi akses luas ke `/storage/emulated/0`, tetapi beberapa vendor SD card memiliki pembatasan write tertentu; pastikan error permission pada target write tertangkap rapi dengan pesan yang jelas ke user.
