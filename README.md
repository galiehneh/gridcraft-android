# GridCraft Android

> **The Open-Source Spreadsheet for Android** — Clean-room reimplementation of modern spreadsheet suites rebuilt in Rust & WebAssembly.

Porting & Packaging dari upstream [storytold/gridcraft](https://github.com/storytold/gridcraft) untuk perangkat Android. Berjalan 100% offline di sisi klien tanpa ketergantungan server eksternal, telemetry, atau akun.

---

## Fitur Utama

- **100% Offline & Private:** Semua pemrosesan lembar kerja (.xlsx, .xlsm, .xls, .xlsb, .ods, .csv, .tsv, .txt, .json) dieksekusi langsung di perangkat Anda via WebAssembly engine.
- **Peralatan Lengkap Spreadsheet:**
  - Formula Engine: Kompatibel dengan fungsi standar spreadsheet (SUM, AVERAGE, VLOOKUP, INDEX, MATCH, dll.), evaluasi cepat, multi-sheet references.
  - Dukungan Format Luas: Membaca & menulis .xlsx, .xlsm, .xlsb, .ods, .csv, .tsv; ekspor ke PDF (selectable vector text).
  - High Performance: Grid rendering hardware-accelerated via WebGL2/WebGPU canvas dengan touch scrolling halus dan pinch-to-zoom.
- **Android Storage Integration:**
  - Terintegrasi langsung dengan Android Storage Access Framework (SAF) untuk memilih berkas dari memori internal / SD Card / cloud storage.
  - Fitur simpan/ekspor berkas mengalir langsung ke folder publik `Download/` perangkat Android via MediaStore API.
- **System Integration:**
  - Mendukung intent `ACTION_VIEW` dan `ACTION_SEND` (Share sheet target) untuk membuka file spreadsheet langsung dari pengelola berkas.
  - Double-back exit guard di halaman beranda untuk mencegah aplikasi tertutup tanpa sengaja.

---

## Arsitektur & Teknologi

| Komponen | Spesifikasi / Teknologi |
|---|---|
| **Core Engine** | Pure Rust (`gridcraft-engine`, `gridcraft-core`, `gridcraft-model`, `gridcraft-calc`, `gridcraft-xlsx`, `gridcraft-chart`) dikompilasi ke WebAssembly (`wasm32-unknown-unknown`) |
| **GUI Framework** | `egui` / `eframe` (v0.36) via WebGL2/WebGPU canvas |
| **Android Wrapper** | Native Android WebView (`com.galiehneh.gridcraft`) |
| **Asset Interception** | `WebViewAssetLoader` menyajikan aset lokal offline dengan MIME `application/wasm` dan CORS headers |
| **Download Handler** | JavaScript Blob Bridge ke Android MediaStore (`Environment.DIRECTORY_DOWNLOADS`) |

---

## Arsitektur Host (Android)

`app/src/main/assets/android/host.html` + `host.js` membungkus app web dalam iframe (`index.html?host=parent`) dan meneruskan event `gridcraft:ready/failed/dirty/save` ke `GridCraftAndroid` (status siap, dirty, simpan chunked, print PDF). Save menimpa file asli (dengan konfirmasi sekali per dokumen) bila izin tulis ada; selain itu ke Downloads / Simpan sebagai.

## Keterbatasan yang Diketahui

Butuh rebuild wasm (upstream), belum dikerjakan: drag-select mode edit di ponsel, pinch tanpa hover, ukuran touch target ribbon, menu konteks long-press, bottom sheet panel, autosave snapshot, Copy/Cut dari menu native.

## Spesifikasi Build

- **Application ID:** `com.galiehneh.gridcraft`
- **Version Name:** `2026.10.17`
- **Version Code:** `2026101701`
- **Minimum SDK:** Android 7.0 (API 24)
- **Target SDK:** Android 15 (API 35)
- **Compile SDK:** 35

---

## Cara Build dari Source

### Prasyarat
- JDK 17
- Android SDK (Platform 35, Build-Tools 35.0.0+)
- Rust + wasm32-unknown-unknown + trunk

### Kompilasi WASM
```bash
cd /root/hermes_user_files/gridcraft-src/apps/gridcraft-web
trunk build --release
cp -r ../../dist/web/* /home/user/gridcraft-android/app/src/main/assets/web/
```

### Kompilasi APK Android
```bash
cd /home/user/gridcraft-android
./gradlew assembleRelease
```
Output APK release: `app/build/outputs/apk/release/app-release.apk`
