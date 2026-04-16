# CLAUDE.md — LR Reader Codebase Guide

## Project Overview

**LR Reader** is an Android client for [LANraragi](https://github.com/Difegue/LANraragi), a self-hosted manga/archive management server. It is forked from [EhViewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ) and retains the EhViewer framework as its UI/reading foundation while replacing all E-Hentai API calls with LANraragi (LRR) REST API calls.

- **Application ID:** `com.lanraragi.reader`
- **Namespace:** `com.hippo.ehviewer` (legacy, retained from EhViewer)
- **Current Version:** 1.11.6 (versionCode 11106 — formula: `MAJOR*10000 + MINOR*100 + PATCH`)
- **License:** GPLv3

---

## Technology Stack

| Layer | Technology | Version |
|---|---|---|
| Languages | Java / Kotlin hybrid (52% Kotlin by file count) | Kotlin 2.1.0 |
| Android SDK | compileSdk 35, minSdk 28 | Android 9+ |
| JDK | Java 21 | sourceCompatibility VERSION_21 |
| Build | Gradle + AGP 8.13.2 | `./gradlew` + Version Catalog (`libs.versions.toml`) |
| Network | OkHttp | 4.12.0 |
| API Serialization | kotlinx-serialization | 1.8.1 (all JSON, Gson removed) |
| Database | Room + KSP | 2.6.1, schema v21 (exported to `app/schemas/`) |
| Coroutines | kotlinx-coroutines | 1.10.2 |
| Lifecycle | AndroidX lifecycle-runtime-ktx | 2.8.7 |
| Image Decoding | Custom C/JNI (libjpeg-turbo, libpng, libwebp) | CMake |
| Security | EncryptedSharedPreferences | 1.1.0 |
| UI | Material Design + AndroidX | Material 1.13.0 |
| Static Analysis | Detekt | 1.23.7 (config: `config/detekt/detekt.yml`) |
| Paging | Jetpack Paging 3 | 3.3.6 |
| ViewModel | AndroidX lifecycle-viewmodel-ktx | 2.8.7 |
| ABI | arm64-v8a (release), arm64-v8a + x86_64 (debug) | 64-bit only |

---

## Repository Structure

```
LRReader/
├── app/
│   ├── schemas/                   # Room schema exports (per version)
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/lanraragi/reader/
│   │   │   │   └── client/api/        # LANraragi REST API client (PRIMARY)
│   │   │   │       └── data/          # LRR @Serializable data classes
│   │   │   ├── java/com/hippo/ehviewer/
│   │   │   │   ├── client/parser/     # HTML/JSON parsers (legacy EH)
│   │   │   │   ├── client/exception/  # Custom API exceptions
│   │   │   │   ├── dao/               # Room DB entities + DAOs
│   │   │   │   ├── module/            # DI-style service modules (ServiceRegistry)
│   │   │   │   ├── settings/          # Modular settings objects (replaces Settings.java)
│   │   │   │   ├── ui/                # Activities + Scenes + Fragments + Dialogs
│   │   │   │   ├── download/          # Download management
│   │   │   │   ├── gallery/           # Gallery + image provider
│   │   │   │   ├── spider/            # Spider/preload subsystem
│   │   │   │   ├── sync/              # Reading progress sync
│   │   │   │   ├── util/              # General utilities
│   │   │   │   ├── widget/            # Custom Android widgets
│   │   │   │   ├── preference/        # Preference screen helpers
│   │   │   │   ├── updater/           # Version update checking
│   │   │   │   ├── shortcuts/         # App shortcuts
│   │   │   │   ├── event/             # Event bus messages
│   │   │   │   └── callBack/          # Callback interfaces
│   │   │   ├── cpp/                   # C/JNI native image decoder
│   │   │   │   └── CMakeLists.txt
│   │   │   ├── res/                   # Resources (11 locale configs)
│   │   │   └── AndroidManifest.xml
│   │   └── test/                      # Unit tests (Robolectric + MockWebServer)
│   ├── build.gradle                   # App-level Gradle config
│   └── proguard-rules.pro
├── config/detekt/detekt.yml           # Detekt static analysis config
├── gradle/
│   └── libs.versions.toml             # Gradle Version Catalog (all deps here)
├── fastlane/                          # Fastlane metadata + screenshots
├── .github/workflows/
│   └── build.yml                      # CI: build + test + lint + detekt
├── build.gradle                       # Root Gradle config
├── settings.gradle                    # Project structure + repositories
├── gradle.properties                  # JVM args, AndroidX settings
└── local.properties                   # Local SDK path + signing (gitignored)
```

### Key Source Files

All paths below are relative to `app/src/main/java/com/hippo/ehviewer/` unless noted.

| File | Purpose |
|---|---|
| `EhApplication.kt` | App entry point; calls `ServiceRegistry.initialize()` |
| `ServiceRegistry.kt` | Central singleton registry (modules: App, Client, Coroutine, Data, Network) |
| `EhDB.kt` | Room database access layer (all suspend); domain methods are `@Deprecated` in favour of Repositories |
| `dao/AppDatabase.kt` | Room database schema (v21, exported to `app/schemas/`) |
| `dao/*Repository.kt` | Domain Repositories: History, Profile, QuickSearch, Favorites, DownloadDb |
| `settings/*.kt` | Modular settings objects (Appearance, Download, Network, Reading, Security, etc.) |
| `client/api/LRR*.kt` | LANraragi REST API: Archive, Search, Category, Database, TagCache, PagingSource |
| `client/api/LRRApiUtils.kt` | Shared utilities: `parseBaseUrl()`, `retryOnFailure()`, `friendlyError()` |
| `download/DownloadManager.kt` | Download facade — delegates to Repository/Scheduler/EventBus; owns `progressTracker` |
| `download/DownloadRepository.kt` | Download in-memory collections + DB persistence |
| `download/DownloadScheduler.kt` | Download worker scheduling + state machine |
| `download/DownloadProgressTracker.kt` | In-memory `StateFlow<Map<arcid, ProgressSnapshot>>` — live progress (ADR-001) |
| `download/ProgressSnapshot.kt` | Immutable per-archive progress: speed/finished/downloaded/total/remaining |
| `ui/MainActivity.kt` | Main UI entry point + scene routing |
| `ui/GalleryActivity.kt` | Reader/detail view |
| `ui/scene/GalleryListScene.kt` | Gallery browse scene (Paging 3) |
| `ui/scene/gallery/detail/GalleryDetailScene.kt` | Gallery detail view (decomposed: DetailHeaderBinder, DetailActionHandler) |
| `ui/scene/gallery/detail/GalleryDetailViewModel.kt` | Gallery detail state + metadata fetch + download state |
| `ui/scene/download/DownloadsScene.kt` | Download list UI; renders from `viewModel.downloadList` (Room × ProgressTracker Flow) |
| `ui/scene/download/DownloadsViewModel.kt` | Download list state (Flow-driven), labels, filter/sort, search, import, sealed DownloadUiEvent |
| `mapper/GalleryInfoMapper.kt` | GalleryInfo↔GalleryInfoUi conversion |
| `util/CoroutineBridge.kt` | Java→coroutine bridge (launchIO) |
| `util/FlowBridge.kt` | Java→Kotlin Flow bridge for lifecycle-aware collection |

---

## Build Commands

```bash
# Debug APK
./gradlew :app:assembleAppReleaseDebug
# Output: app/build/outputs/apk/appRelease/debug/

# Signed Release APK
./gradlew :app:assembleAppReleaseRelease
# Output: app/build/outputs/apk/appRelease/release/

# Unit tests
./gradlew app:testAppReleaseDebugUnitTest

# Lint
./gradlew app:lintAppReleaseDebug

# Detekt (static analysis — continue-on-error in CI)
./gradlew detekt

# Clean
./gradlew clean
```

### Signing Setup (required for release builds)

Create `local.properties` in the project root (gitignored):

```properties
sdk.dir=/path/to/Android/Sdk
RELEASE_STORE_FILE=keystore/release.jks
RELEASE_STORE_PASSWORD=<password>
RELEASE_KEY_ALIAS=lrreader
RELEASE_KEY_PASSWORD=<password>
```

Signing config also reads from environment variables (`RELEASE_STORE_FILE`, etc.) for CI use.

### Build Variants

- Single flavor: `appRelease`
- Two build types: `debug` (applicationIdSuffix `.debug`) and `release` (minified, signed)
- R8/ProGuard enabled for release; `shrinkResources true`

---

## Code Conventions

### Language

- **All new code must be Kotlin.** Java is legacy from EhViewer; do not write new Java.
- **All `ehviewer` business code is 100% Kotlin** — zero Java files remain in `com.hippo.ehviewer`.
- `com.hippo.*` framework (~228 files: GLView, Conaco, ContentLayout, widgets) stays Java — stable legacy, rarely touched.

### Style

- 4-space indentation, same-line opening braces
- CamelCase for classes, camelCase for variables/methods
- Comments may be in Chinese or English (both acceptable)
- Detekt enforces style rules; run `./gradlew detekt` before pushing

### Async / Threading

- All network and database calls use **Kotlin Coroutines**: `suspend fun` + `withContext(Dispatchers.IO)`
- Use `viewLifecycleOwner.lifecycleScope` for Fragment coroutines
- **From Java code**, use `CoroutineBridge.launchIO(lifecycleOwner, task)` or `IoThreadPoolExecutor` to move DB/network work off the main thread
- `EhDB` provides only `suspend fun xxxAsync()` methods — the legacy `blockingDb` bridge and all `@JvmStatic` wrappers have been removed
- `CoroutineModule` provides `applicationScope` and `ioScope` with `SupervisorJob` + `CoroutineExceptionHandler`
- `LRRCoroutineHelper.runSuspend()` has a **runtime main-thread guard** that throws if called on the UI thread
- **No `AsyncTask` anywhere** — all replaced with `IoThreadPoolExecutor` + `Handler`
- **No main-thread DB calls** — all `EhDB.*()` calls from UI code are wrapped in `IoThreadPoolExecutor` or coroutine scopes
- **No `runBlocking` in new code** — use `scope.launch {}` or `suspend fun` instead. The only surviving `runBlocking` is in `LRRCoroutineHelper.runSuspend()` (Java→Kotlin bridge with `@WorkerThread` + main-thread guard).
- Thread pool: `IoThreadPoolExecutor` for parallel image/network work

### Networking (OkHttp)

- All LANraragi API calls go through `client/api/` package
- `LRRAuthInterceptor` injects API key per request
- `LRRClientProvider` supplies the configured `OkHttpClient`
- DNS-over-HTTPS via `okhttp-dnsoverhttps`
- Cleartext HTTP allowed globally for LAN IP access; API key scoped to configured server via `LRRAuthInterceptor`

### Database (Room)

- All entities and DAOs in `dao/` package
- Use KSP (not KAPT) for annotation processing
- Schema version is v21; exported to `app/schemas/` — always provide a `Migration` when bumping
- **Never** use `fallbackToDestructiveMigration()` in production code
- `AppDatabase.kt` is the single Room database instance

### Serialization

- **All JSON (LRR API responses and new code):** `kotlinx-serialization` with `@Serializable` data classes in `client/api/data/`
- Gson has been removed from the project — do not re-add it

### Dependency Management

- All library versions declared in `gradle/libs.versions.toml` (Version Catalog)
- Reference libraries in `build.gradle` as `libs.<alias>`, never hardcode versions
- JitPack dependencies are pinned to commit hashes — update manually, document in catalog comments

### Service / Module Pattern

New singletons belong in the appropriate module under `module/`:

- `AppModule` — app-wide services (crash, analytics)
- `ClientModule` — API client instances
- `CoroutineModule` — scoped coroutines with exception handling
- `DataModule` — database access objects
- `NetworkModule` — OkHttp, DNS, proxy

Access via `ServiceRegistry.<module>.<service>`. Do not add new statics to `EhApplication`.

### Settings

Settings are now Kotlin objects in `settings/`:

- `Settings.kt` (utility only: `getContext()`, `getPreferences()`, generic accessors), `AppearanceSettings`, `DownloadSettings`, `FavoritesSettings`, `NetworkSettings`, `ReadingSettings`, `SecuritySettings`, `UpdateSettings`, `GuideSettings`, `PrivacySettings`
- New settings go into the appropriate typed object; do not add field-specific accessors to `Settings.kt`
- API keys use `EncryptedSharedPreferences` via `LRRAuthManager` — never plaintext

### Package Organization

- LRR API code → `client/api/`
- LRR data classes → `client/api/data/`
- UI scenes → `ui/scene/`; fragments → `ui/fragment/`
- Business logic stays out of Activities/Fragments

---

## Testing

Unit tests live in `app/src/test/java/` covering:

- **LRR API** — all API classes + data classes + PagingSource (MockWebServer)
- **Download module** — DownloadManager, Repository, Scheduler, EventBus, SpeedTracker, ProgressTracker
- **ViewModels** — all 8 Scene ViewModels (Downloads, GalleryDetail, GalleryList, History, ServerConfig, ServerList, Categories, QuickSearch)
- **Room** — schema integrity, migration paths, DAO CRUD
- **Utilities** — Parcelable round-trip, DiffUtil contracts, CoroutineBridge, tag parsing, pattern lockout

```bash
./gradlew app:testAppReleaseDebugUnitTest
```

Test reports: `app/build/reports/tests/`

---

## CI/CD

### GitHub Actions

**`build.yml`** — triggers on push/PR to `main`:
1. Validate Fastlane metadata
2. Build (`assembleAppReleaseDebug`)
3. Unit tests (`testAppReleaseDebugUnitTest`)
4. Lint (`lintAppReleaseDebug`)
5. Detekt (blocking — build fails on violations)
6. JaCoCo test coverage report (continue-on-error)
7. Upload artifacts: test reports, coverage reports, lint reports, detekt reports, APK
8. Dependency submission (push to `main` only — GitHub dependency graph)

Releases are managed locally via `gh release create` with pre-signed APKs. No CI-based release workflow.

Firebase Crashlytics is optional: applied only if `app/google-services.json` exists (gitignored).

---

## Localization

Resources compiled for 11 locale configurations:
`en`, `zh`, `zh-rCN`, `zh-rHK`, `zh-rTW`, `es`, `ja`, `ko`, `fr`, `de`, `th`

Lint rules disable `MissingTranslation` and `ExtraTranslation` — partial translations are acceptable.

---

## Native Code (C/JNI)

- Located in `app/src/main/cpp/`
- Built via CMake (`CMakeLists.txt`)
- Custom high-performance image decoder wrapping libjpeg-turbo, libpng, libwebp
- JNI module name: `native-lib`
- Only touch for image decoding bugs or new format support

## What NOT to Do

### Language & Build

- Do not write new Java — all new code must be Kotlin
- Do not use Gson — use `kotlinx-serialization` for all JSON
- Do not hardcode dependency versions in `build.gradle` — use `libs.versions.toml`
- Do not add `x86_64` ABI filter to release builds — release is arm64-v8a only (debug includes x86_64 for emulator)
- Do not commit `local.properties`, keystore credentials, or `google-services.json`

### Threading & Coroutines

- Do not use `AsyncTask` or raw `Thread` for network/DB work — use coroutines or `IoThreadPoolExecutor`
- Do not use `runBlocking` in new code — use `scope.launch {}` or `suspend fun` instead
- Do not add `blockingDb()` bridges or `@JvmStatic` wrappers to `EhDB` — use `suspend fun` variants from a coroutine scope
- Do not add fire-and-forget `scope.launch { EhDB.*() }` without try-catch — all DB persistence launches must handle exceptions

### Database & Persistence

- Do not use `fallbackToDestructiveMigration()` for Room schema changes
- Do not store API keys or secrets in source code or non-encrypted preferences
- Do not call `EhDB` domain methods directly from UI layer — use the corresponding Repository via `ServiceRegistry.dataModule` (History, Profile, QuickSearch, Favorites, DownloadDb); the `EhDB` methods are `@Deprecated`

### Architecture & Patterns

- Do not add new singletons to `EhApplication` — use `ServiceRegistry` modules
- Do not add field-specific accessors to `Settings.kt` — new settings go into the appropriate modular settings object
- Do not hardcode cache-clear calls in `ServiceRegistry.clearAllCaches()` — implement `Cacheable` and register via `ServiceRegistry.registerCacheable()`
- Do not use `GalleryInfo` / `GalleryInfoEntity` in UI-layer code that only displays gallery data — use `GalleryInfoUi`; use `GalleryInfoEntity` (via `GalleryInfo` typealias) only at persistence boundaries
- Do not add new Scene classes without a corresponding ViewModel — all functional Scenes have ViewModels
- Do not reintroduce Helper Callback interfaces — business logic goes in ViewModels, Scenes observe StateFlow/SharedFlow
- Do not move extracted helper logic back into Scene classes — keep Scenes as coordinators, helpers own the logic

### API & Naming

- Do not use `toHttpUrlOrNull()!!` to build LRR API URLs — use `parseBaseUrl()` from `LRRApiUtils.kt`
- Do not import from `com.hippo.ehviewer.client.lrr` — use `com.lanraragi.reader.client.api`
- Do not use `EhUrl` / `EhUrlOpener` — renamed to `LRRUrl` / `LRRUrlOpener`

### UI

- Do not use `notifyDataSetChanged()` on RecyclerView — use DiffUtil or specific `notifyItem*()` calls
- Do not introduce new visual themes or Material3 components — match existing `RoundSideRectDrawable` + theme attr style

### Download Module

- Do not import `DownloadRepository`, `DownloadScheduler`, or `DownloadEventBus` from outside the `download/` package — use `DownloadManager` facade only
- Do not add `DownloadInfoListener` implementation to Scene classes — listener logic belongs in ViewModels, Scenes observe sealed `DownloadUiEvent` SharedFlow
- Do not split `DownloadUiEvent` sealed interface back into individual SharedFlows — the single-flow dispatch pattern is intentional
- Do not read transient progress fields (`speed` / `finished` / `downloaded` / `total` / `remaining`) from `DownloadInfo` in new code — use `DownloadManager.progressFor(arcid)` or subscribe to `progressTracker.progressFlow` (ADR-001). The `@Ignore` fields on `DownloadInfo` are retained only for backward compatibility and will be removed in a future step.
- Do not read `DownloadManager.getLabelDownloadInfoList` / `defaultDownloadInfoList` from download-list UI code — use `viewModel.downloadList` (progress-enriched Flow). These accessors remain for non-UI callers only.
- Do not add a Scene-level `collectFlow(viewModel.downloadsFlow)` subscription for the download list — the Scene subscribes to `viewModel.downloadList` which already combines Room Flow with `DownloadProgressTracker.progressFlow`.
- Do not re-add per-tick `DownloadInfoListener.onUpdate`-driven `notifyItemChanged` calls for progress display — progress updates arrive via the combined Flow. The `ItemUpdated` event handler is retained only for immediate state flips (e.g., WAIT→DOWNLOAD) that precede the next Room Flow emission.
