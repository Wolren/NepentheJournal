# Cross-Platform Compilation Guide

Nepenthe Journal targets 3 platforms from a single Kotlin Multiplatform codebase:
- **Desktop** (JVM) — primary dev target, runs on Windows/macOS/Linux
- **Android** — physical device side-load (emulator unavailable on dev machine)
- **iOS** — Xcode build required on macOS; compilation verified via CI

## Must-compile sources

All code under `composeApp/src/commonMain/` **must** compile on all three targets.
Code under `desktopMain`, `androidMain`, `iosMain` is platform-specific.

## Common pitfalls

### expect/actual declarations
- Every `expect` in `commonMain` needs an `actual` in **each** platform source set
- Adding a new `expect` function/class breaks the other two platforms until their `actual` is written
- `expect`/`actual` classes are still Beta in Kotlin — use `-Xexpect-actual-classes` flag in `build.gradle.kts` if the compiler requires it

### Platform-specific APIs
- **No JVM-only APIs** in commonMain: `javax.crypto`, `java.io.File`, `java.net.NetworkInterface`, etc.
  - Use expect/actual wrappers: `PlatformLock` instead of `ReentrantLock`, `PlatformFile` instead of `java.io.File`
- **No Android-only APIs** in commonMain: `android.os.*`, `BackHandler`, `ContentResolver`
- **No iOS-only APIs** in commonMain: `Foundation.NSFileManager`, `UIKit.UIDevice`
- **No Compose Desktop-only APIs** in commonMain: `java.awt.Desktop`, `java.io.File` for file dialogs
- `java.util.zip.ZipOutputStream` is JVM-only — use manual ZIP format in commonMain

### Threading
- `kotlinx.coroutines.Dispatchers.Main` is unavailable on Compose Desktop — use `Dispatchers.Default` or an expect/actual
- `runBlocking` on desktop uses the calling thread — fine for tests, but avoid in UI code
- `ReentrantLock` is JVM-only — use `synchronized()` or `PlatformLock` expect/actual

### Compose Desktop specifics
- `ExposedDropdownMenuBox` + `menuAnchor()` steals focus inside Dialogs on Desktop — use inline `LazyColumn` for search results instead
- `SelectionContainer` crashes Skia software renderer — avoid wrapping `LazyColumn` or `Box` content
- Software renderer must be forced in `Main.kt`: `skiko.renderApi=SOFTWARE` (no GPU passthrough on dev machine)

### Tests
- Desktop tests run via `./gradlew desktopTest` — requires `ulimit -u 16384` on git-bash (default is 256, causes daemon crashes)
- Android tests need a device connected (emulator unavailable)
- iOS tests need macOS with Xcode
- All `commonTest` code must compile on all platforms — no platform-specific test utilities in commonTest

## Verification before commit

Before pushing, run:
```bash
./gradlew compileKotlinDesktop    # Desktop compilation
./gradlew compileKotlinAndroid     # Android compilation (requires SDK)
# iOS compilation requires macOS
```

The CI pipeline (GitHub Actions) runs all three targets — monitor the workflow run after push.
