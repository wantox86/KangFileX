# App Specification: Dual-Pane Network File Manager (Android MVP)

**Referensi UI/UX:** X-plore File Manager (Android)  
**Target Platform:** Android (Min SDK 29 / Android 10, Target SDK 35 / Android 15)  
**Fokus MVP:** Navigasi Dual-Pane (Swipe Kiri-Kanan), Tree-View Explorer, Copy-Paste lintas protokol (**Local, SMB, FTP/FTPS, SSH/SFTP**).

---

## 1. Ringkasan Produk & Scope MVP

### 1.1 Tujuan Produk
Membangun aplikasi *file manager* Android untuk *power-user* yang mengutamakan kecepatan navigasi dan kemudahan pemindahan file antar penyimpanan lokal maupun server jaringan menggunakan konsep **Dual-Pane + Tree View + Center Action Bar**.

### 1.2 In-Scope (MVP)
- **UI Dual-Pane** dengan gestur swipe kiri/kanan untuk berpindah antara Pane Kiri dan Pane Kanan.
- **Center Action Bar** vertikal yang selalu menempel di antara kedua pane untuk akses aksi cepat.
- **Tree-View Folder Explorer** (expand/collapse inline dengan garis hierarki dan indentasi).
- **Multi-Select** cepat menggunakan checkbox (`✓`) di sisi kanan setiap item.
- **Operasi File Dasar:** Copy (antar pane), Move (antar pane), Copy to Clipboard & Paste, Rename, Delete, New Folder.
- **Dukungan Protokol Jaringan:**
  - Local Storage (Internal Storage & SD Card)
  - SMB / CIFS (v2 & v3 untuk LAN/NAS/Windows Share)
  - FTP & FTPS (Explicit & Implicit)
  - SSH / SFTP (Password & Private Key Authentication)
- **Transfer Engine & Queue:** Mendukung transfer *cross-protocol* (streaming) di background menggunakan Android Foreground Service.

### 1.3 Out-of-Scope (Fase 2 / Post-MVP)
- Disk Map / Storage Analyzer.
- Built-in WiFi Server / FTP Server.
- Archive Explorer (ZIP/RAR/7z extraction & creation).
- Built-in Text/Hex Editor & Media Viewer lanjutan.
- Cloud Storage (Google Drive, Dropbox, OneDrive, S3).
- Root Explorer & App Manager.

---

## 2. Spesifikasi UI/UX

### 2.1 Wireframe Konsep Layout

#### Mode Portrait (Smartphone)
Saat **Pane Kiri** aktif, Center Action Bar berada di sisi kanan (~15% lebar layar). Saat user **swipe ke kiri**, layar bergeser halus ke **Pane Kanan**, dan Center Action Bar ikut bergeser menempel di sisi kiri layar.

```text
   [ PANE KIRI AKTIF ]                    [ PANE KANAN AKTIF ]
+-----------------------+-----+        +-----+-----------------------+
| <- App Name      [⋮]  |     |        |     | <- App Name      [⋮]  |
+-----------------------+     |        |     +-----------------------+
| Internal > Download   |  -> |        | <-  | sftp://192.168.1.10   |
+-----------------------+-----+        +-----+-----------------------+
| /storage/emulated/0   | Up  |        | Up  | /var/www/html         |
| [====-----] 331/481GB | Dir |        | Dir |                       |
+-----------------------+-----+        +-----+-----------------------+
| |- [>] Android     [v]|     |  SWIPE |     | |- [>] assets      [ ]|
| |- [v] Download    [v]| Copy| <----> | Copy| |- [ ] index.php   [ ]|
| |  |- [ ] app.apk  [v]|     |        |     | |- [ ] config.json [ ]|
| |  |- [>] Telegram [ ]| Move|        | Move| |                     |
| |- [>] DCIM        [ ]|     |        |     | |                     |
|                       |Paste|        |Paste| |                     |
|                       |     |        |     | |                     |
|                       | Del |        | Del | |                     |
+-----------------------+-----+        +-----+-----------------------+
```

#### Mode Landscape / Tablet
Menampilkan **Pane Kiri (45%) | Center Action Bar (10%) | Pane Kanan (45%)** secara bersamaan (*side-by-side*).

### 2.2 Komponen Utama per Pane
1. **Top App Bar:** Tombol navigasi/back, nama aplikasi, tombol refresh/quick-pane toggle, dan menu overflow (`⋮`).
2. **Breadcrumb Bar:** Menampilkan jalur folder aktif (contoh: `Internal shared... > Download > Telegram`). Setiap segmen dapat diklik untuk langsung melompat/scroll ke folder tersebut.
3. **Storage / Connection Header:**
   - Menampilkan path root atau URI server (misal `/storage/emulated/0` atau `smb://192.168.1.5/Share`).
   - Metadata jumlah subfolder & file, serta progress bar kapasitas penyimpanan (khusus storage yang mendukung info kapasitas).
4. **Tree-View List:**
   - Klik pada folder akan melakukan **expand/collapse secara inline** (bukan membuka layar baru).
   - Menampilkan garis konektor vertikal dan indentasi sesuai kedalaman (*depth*) folder.
   - Ikon indikator panah kecil di kiri ikon folder untuk menandakan status expand/collapse.
   - Subtitle metadata di bawah nama folder (jumlah folder & file) atau ukuran & tanggal modifikasi untuk file.
   - **Checkbox (`✓`) di sisi kanan** setiap baris untuk multi-select satu sentuhan.
   - **Active Highlight:** Garis tepi (border) berwarna oranye/aksen untuk menandai folder tujuan (*target directory*) yang sedang aktif.

### 2.3 Center Action Bar (Vertikal & Scrollable)
Menampilkan panah indikator arah (menunjukkan ke pane mana aksi Copy/Move akan dieksekusi) dan tombol aksi MVP:
- **Up Dir:** Collapse/lompat ke parent directory.
- **Copy:** Menyalin item terpilih dari Pane Aktif langsung ke folder aktif di **Pane Sebelah**.
- **Move:** Memindahkan item terpilih dari Pane Aktif ke folder aktif di **Pane Sebelah**.
- **Copy to Clipboard:** Menyimpan referensi item terpilih ke clipboard internal.
- **Paste:** Menempelkan item dari clipboard ke folder aktif saat ini (aktif/muncul jika clipboard terisi).
- **Rename:** Mengubah nama 1 item terpilih.
- **Delete:** Menghapus item terpilih (dengan dialog konfirmasi).
- **New Folder:** Membuat folder baru di direktori aktif.
- **Select All / Clear:** Memilih semua item dalam folder aktif atau menghapus semua centang.

---

## 3. Spesifikasi Fungsional

### 3.1 Manajemen Koneksi & Root Node
Pada level root di setiap pane, user melihat daftar sumber penyimpanan:
- **Internal Storage** (dan SD Card jika terdeteksi)
- **LAN (SMB)** $\rightarrow$ berisi daftar server tersimpan + tombol `+ Add Server`
- **FTP** $\rightarrow$ berisi daftar server tersimpan + tombol `+ Add Server`
- **SSH / SFTP** $\rightarrow$ berisi daftar server tersimpan + tombol `+ Add Server`

#### Parameter Konfigurasi Server
| Protokol | Field Wajib | Field Opsional |
| :--- | :--- | :--- |
| **SMB (LAN)** | Label, Host/IP | Port (default `445`), Share Name, Domain, Username, Password, Anonymous/Guest toggle |
| **FTP / FTPS** | Label, Host/IP, Tipe (FTP / FTPES / FTPS) | Port (default `21`), Username, Password, Anonymous toggle, Active/Passive mode (default Passive), Root Path |
| **SSH / SFTP** | Label, Host/IP, Auth Type (Password / Private Key) | Port (default `22`), Username, Password, Private Key File (`.pem`/OpenSSH), Passphrase, Root Path |

*Catatan Keamanan SSH:* Implementasikan **TOFU (Trust On First Use)** untuk memverifikasi *Host Key Fingerprint* saat pertama kali terhubung ke server baru, serta peringatan jika fingerprint berubah.

### 3.2 Copy-Paste & Transfer Engine
1. **Cross-Protocol Streaming:**
   - Mendukung salin/pindah antar semua kombinasi sumber: `Local <-> Local`, `Local <-> Remote`, `Remote A <-> Remote B` (contoh: `SMB -> SFTP`).
   - Transfer dilakukan secara *streaming* per blok buffer (misal `64 KB`–`256 KB`) tanpa menyimpan file sementara di internal storage perangkat, sehingga aman untuk file berukuran besar (>4 GB).
2. **Conflict Resolution:**
   - Jika file tujuan sudah ada, tampilkan dialog pilihan: **Overwrite**, **Skip**, atau **Auto-Rename** (misal `file (1).ext`), dilengkapi checkbox **"Apply to all"**.
3. **Background Execution & Queue:**
   - Berjalan di atas Android **Foreground Service** (`foregroundServiceType="dataSync"`).
   - Menampilkan notifikasi persisten dengan nama file, persentase progres, kecepatan transfer (MB/s), dan tombol *Cancel*.
   - Dialog progres di dalam aplikasi dapat di-minimize ke background agar user tetap bisa browsing folder lain.

---

## 4. Arsitektur Teknis

### 4.1 Tech Stack Rekomendasi
- **Bahasa:** Kotlin (Coroutines + StateFlow/SharedFlow).
- **UI Framework:** Jetpack Compose (menggunakan custom `HorizontalPager` / `AnchoredDraggable` untuk efek Dual-Pane + Center Action Bar, serta `LazyColumn` untuk Tree-View).
- **Arsitektur:** Clean Architecture + MVVM (Presentation $\rightarrow$ Domain / VFS $\rightarrow$ Data / Protocol Drivers).
- **Database & Security:**
  - **Room Database:** Menyimpan metadata konfigurasi server.
  - **Android Keystore + Google Tink / EncryptedSharedPreferences:** Enkripsi *at-rest* untuk password server, passphrase, dan SSH Private Key.

### 4.2 Library Protokol (Android / JVM)
- **SMB (v2/v3):** `com.hierynomus:smbj`
- **SSH / SFTP:** `com.github.mwiede:jsch` (fork modern yang mendukung algoritma kunci terbaru seperti Ed25519/RSA-SHA2) atau `com.hierynomus:sshj`
- **FTP / FTPS:** `commons-net:commons-net` (Apache Commons Net)

### 4.3 Abstraksi Virtual File System (VFS)
Semua protokol dibungkus di balik satu interface seragam agar UI dan Transfer Engine tidak bergantung pada detail masing-masing protokol.

```kotlin
enum class ProtocolType { LOCAL, SMB, FTP, FTPS, SFTP }

data class ServerConfig(
    val id: String,
    val label: String,
    val protocol: ProtocolType,
    val host: String,
    val port: Int,
    val rootPath: String = "/",
    val username: String? = null,
    val authType: AuthType = AuthType.PASSWORD,
    val shareName: String? = null, // Khusus SMB
    val passiveMode: Boolean = true // Khusus FTP
    // Password & Private Key disimpan terenkripsi terpisah berdasarkan `id`
)

data class VfsFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val providerId: String,
    val isSymlink: Boolean = false,
    val childDirCount: Int? = null,
    val childFileCount: Int? = null
)

interface VfsProvider {
    val providerId: String
    suspend fun connect(): Result<Unit>
    suspend fun disconnect()
    suspend fun isConnected(): Boolean
    suspend fun listFiles(path: String): Result<List<VfsFile>>
    suspend fun openInputStream(path: String): InputStream
    suspend fun openOutputStream(path: String, overwrite: Boolean): OutputStream
    suspend fun createDirectory(path: String): Result<Unit>
    suspend fun delete(path: String, recursive: Boolean): Result<Unit>
    suspend fun rename(oldPath: String, newPath: String): Result<Unit>
    suspend fun exists(path: String): Boolean
}
```

### 4.4 State Management untuk Tree-View
Agar `LazyColumn` tetap berjalan lancar di 60–120fps untuk ribuan file, struktur pohon (*tree*) diratakan (*flattened*) menjadi list linear:

```kotlin
data class TreeNodeUiState(
    val id: String, // unique key: "$providerId:$path"
    val file: VfsFile,
    val depth: Int,
    val isExpanded: Boolean = false,
    val isLoadingChildren: Boolean = false,
    val isSelected: Boolean = false,
    val isFocusedTarget: Boolean = false
)
```

---

## 5. Permission & Penanganan Edge Cases

1. **Android Storage Permission (Android 11+):**
   - Membutuhkan permission `MANAGE_EXTERNAL_STORAGE` (*All Files Access*) agar dapat mengakses `/storage/emulated/0` secara penuh.
   - *(Catatan Play Store)*: Wajib menyertakan deklarasi *Core Functionality* sebagai File Manager saat submit ke Google Play Console.
2. **Auto-Reconnect & Session Timeout:**
   - Koneksi FTP/SFTP/SMB rentan terputus saat idle atau saat perangkat berpindah jaringan (WiFi $\leftrightarrow$ Seluler). Setiap `VfsProvider` wajib memiliki mekanisme cek koneksi dan *auto-reconnect* otomatis sebelum eksekusi perintah.
3. **Symbolic Link Loop (SSH/SFTP):**
   - Saat melakukan operasi Copy atau Delete rekursif pada server Linux, deteksi `isSymlink` agar tidak terjadi *infinite loop* jika ada symlink yang mengarah ke direktori induk.
4. **Karakter Khusus & Encoding:**
   - Pastikan FTP mengaktifkan `UTF-8` (`OPTS UTF8 ON`) dan penanganan nama file yang mengandung spasi atau karakter Unicode berjalan aman di semua protokol.
5. **Partial File Cleanup:**
   - Jika transfer dibatalkan (*Cancel*) atau gagal di tengah jalan, file parsial yang belum selesai ditulis di tujuan harus dibersihkan (atau diberi opsi retry/delete).

---

## 6. Roadmap Pengerjaan MVP (Estimasi 4–6 Minggu)

| Fase | Estimasi | Deliverable Utama |
| :--- | :--- | :--- |
| **Sprint 1: Fondasi & UI Core** | Minggu 1–2 | Setup project Kotlin/Compose, `LocalVfsProvider`, UI Dual-Pane swipe kiri-kanan + Center Action Bar, Tree-View (expand/collapse + multi-select + breadcrumb). |
| **Sprint 2: Transfer Engine** | Minggu 2–3 | Implementasi Copy/Move antar pane, Clipboard Copy/Paste, Rename, Delete, New Folder, Conflict Resolution Dialog, Foreground Service & Notifikasi Progres. |
| **Sprint 3: SMB & FTP** | Minggu 3–4 | UI Manajemen Server (Room + Encrypted Credentials), implementasi `SmbVfsProvider` (SMB2/3) dan `FtpVfsProvider` (FTP/FTPS). |
| **Sprint 4: SSH/SFTP & QA** | Minggu 5–6 | Implementasi `SftpVfsProvider` (Password & SSH Key + TOFU Host Key), pengujian transfer cross-protocol file besar (>2GB), handling putus koneksi, dan optimasi performa UI. |