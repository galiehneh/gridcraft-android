# GridCraft Android

Android WebView wrapper (Java 17, AGP 9.0.1, minSdk 24, target/compile 35) around a prebuilt Rust/wasm GridCraft UI. Java sources: `app/src/main/java/com/galiehneh/gridcraft/`.

## Commands
- Debug build: `./gradlew assembleDebug`
- Release build: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
- Unit tests: `./gradlew test` (junit 4.13.2 declared; no `app/src/test` yet)
- Lint: `./gradlew lint`
- No dev server.

## Key files
- `MainActivity.java` — WebView, WebViewAssetLoader, VIEW/SEND intents, double-back guard
- `AndroidBridge.java` — JS↔Java bridge
- `FileImporter.java`, `FileSaver.java`, `SaveActions.java` — SAF import, MediaStore Downloads export
- `Mime.java` — MIME mapping
- `assets/android/host.{html,js}` — wrapper iframe (host mode), relay event web → bridge; hand-written, not generated
- `RecentFiles.java`, `SaveBar.java`, `Source.java`, `RateLimit.java`, `PdfPrintAdapter.java` — recents, save snackbar, save source snapshot, recovery cap, print

## Notes
- `app/src/main/assets/web/` is generated (trunk build of upstream `gridcraft-web`). Don't hand-edit; rebuild and copy per README.
- Deps live in `gradle/libs.versions.toml`.
- Release version: bump `versionCode` (YYYYMMDDNN) and `versionName` in `app/build.gradle.kts`. Keep the README spec table in sync.
- Never read, print, or commit `signing/release-signing.properties` or `signing/*.keystore` (gitignored; only `.example` is tracked).
