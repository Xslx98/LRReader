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
| Database | Room + KSP | 2.6.1, schema v18 (exported to `app/schemas/`) |
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
│   │   └── test/                      # Unit tests (42 files, LRR API + DAO + DiffUtil + Paging + Filter + Download)
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

| File | Purpose |
|---|---|
| `EhApplication.kt` | App entry point; calls `ServiceRegistry.initialize()`, defers JNI init to background |
| `ServiceRegistry.kt` | Central singleton registry replacing old EhApplication service locator |
| `module/AppModule.kt` | App-level services (crash reporting, analytics) |
| `module/ClientModule.kt` | LRR API clients + auth |
| `module/CoroutineModule.kt` | SupervisorJob + CoroutineExceptionHandler scoped coroutines |
| `module/DataModule.kt` | Room database access |
| `module/NetworkModule.kt` | OkHttp client configuration + DNS |
| `util/CoroutineBridge.kt` | Java→coroutine bridge (launchIO/launchIOGlobal) |
| `EhDB.kt` | Room database access layer (all suspend, no `blockingDb` bridges) |
| `settings/AppearanceSettings.kt` | UI/theme preferences |
| `settings/DownloadSettings.kt` | Download preferences |
| `settings/NetworkSettings.kt` | Network/proxy preferences |
| `settings/ReadingSettings.kt` | Reader preferences |
| `settings/SecuritySettings.kt` | Auth/security preferences |
| `client/api/LRRApiUtils.kt` | Shared utilities: `parseBaseUrl()`, `retryOnFailure()`, `friendlyError()`, JSON/exception defs |
| `client/api/LRRArchiveApi.kt` | Archive search/list/detail/upload/delete/metadata API |
| `client/api/LRRSearchApi.kt` | Search + random endpoint |
| `client/api/LRRCategoryApi.kt` | Category CRUD + archive association |
| `client/api/LRRDatabaseApi.kt` | Tag statistics + database operations |
| `client/api/LRRTagCache.kt` | In-memory tag autocomplete cache (10-min TTL) |
| `client/api/LRRArchivePagingSource.kt` | Paging 3 source for gallery list |
| `dao/AppDatabase.kt` | Room database schema (v19, schema exported) |
| `dao/HistoryRepository.kt` | History domain Repository backed by BrowsingRoomDao (W17-3) |
| `util/FlowBridge.kt` | Java→Kotlin Flow bridge for lifecycle-aware collection |
| `ui/MainActivity.kt` | Main UI entry point + scene routing |
| `ui/GalleryActivity.kt` | Reader/detail view |
| `ui/scene/GalleryListScene.kt` | Gallery browse scene (uses PagingSource for LRR search) |
| `ui/scene/download/DownloadsScene.kt` | Download management (~1259 lines, observes ViewModel sealed events, no direct DownloadManager access) |
| `ui/scene/download/DownloadGuideHelper.kt` | Showcase/tutorial overlay logic extracted from DownloadsScene (W9-2) |
| `ui/scene/download/DownloadPaginationHelper.kt` | Page indicator and navigation extracted from DownloadsScene (W9-2) |
| `ui/scene/download/DownloadDragDropHelper.kt` | Item reordering via RecyclerViewDragDropManager extracted from DownloadsScene (W9-2) |
| `ui/scene/gallery/detail/GalleryDetailScene.kt` | Gallery detail view (~694 lines, observes GalleryDetailViewModel, W9-3 decomposed) |
| `ui/scene/gallery/detail/DetailHeaderBinder.kt` | Header view binding + thumbnail + circular reveal extracted from GalleryDetailScene (W9-3) |
| `ui/scene/gallery/detail/DetailActionHandler.kt` | Action button click handling extracted from GalleryDetailScene (W9-3) |
| `ui/scene/gallery/detail/GalleryDetailViewModel.kt` | Gallery detail state + metadata fetch + download state + DownloadInfoListener (W3-2) |
| `ui/scene/gallery/detail/GalleryTagHelper.kt` | Stateless tag display utility object (W3-2: Callback eliminated) |
| `ui/scene/gallery/list/GalleryListViewModel.kt` | Paging 3 ViewModel for gallery list + download state observation (W15-2) |
| `ui/scene/gallery/list/QuickSearchViewModel.kt` | Quick search CRUD ViewModel (W14-3) |
| `ui/scene/gallery/list/GalleryFabHelper.kt` | FabLayout callback logic extracted from GalleryListScene (W15-2) |
| `ui/scene/gallery/list/GallerySearchBarHelper.kt` | SearchBar/FastScroller/SearchLayout callbacks (W15-2) |
| `ui/scene/gallery/list/GalleryListHelperFactory.kt` | Factory wiring all 12 helpers for GalleryListScene (W15-2) |
| `ui/scene/gallery/list/GalleryListSearchHelper.kt` | SearchBar interaction + query construction extracted from GalleryListScene (W9-3) |
| `ui/scene/download/DownloadsViewModel.kt` | Download list state, labels, filter/sort, search, import, bulk ops, sealed DownloadUiEvent (W9-1) |
| `ui/scene/download/DownloadGalleryOpenHelper.kt` | Gallery item click + read-process update extracted from DownloadsScene (W16-1) |
| `ui/scene/download/DownloadSelectionHelper.kt` | Selection mode + FAB handling extracted from DownloadsScene (W16-1) |
| `ui/scene/LRRCategoriesViewModel.kt` | Category CRUD ViewModel (W14-2) |
| `ui/scene/ServerListViewModel.kt` | Server profile CRUD + connection verification ViewModel (W15-1) |
| `ui/scene/ServerListDialogHelper.kt` | Profile add/edit/delete dialog builders (W15-1) |
| `download/DownloadManager.kt` | Download facade — thin delegation to Repository/Scheduler/EventBus (W6-2) |
| `download/DownloadRepository.kt` | Download collection management + DB persistence, injected CoroutineDispatcher (W6-2, W8-3) |
| `download/DownloadScheduler.kt` | Download worker scheduling + state machine (W6-2) |
| `download/DownloadEventBus.kt` | Download listener registration + event dispatch (W6-2) |
| `mapper/GalleryInfoMapper.kt` | GalleryInfo↔GalleryInfoUi conversion (W3-3) |
| `ui/scene/gallery/detail/TagEditDialog.kt` | Grouped tag editor (chip-style, per-namespace) |
| `ui/gallery/GalleryMenuHelper.kt` | Reader settings dialog (immediate-apply, no confirm button) |

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
- **All `ehviewer` business code is Kotlin** — data, API, download, settings, modules, gallery providers, all Scenes, all Activities, all Adapters, all widgets.
- Remaining 16 Java files in `ehviewer` are small callback interfaces, exception classes, legacy parsers, and annotations (`GalleryActivityEvent` converted to Kotlin in W3-3).
- `com.hippo.*` framework (230 files: GLView, Conaco, ContentLayout, widgets) stays Java — stable legacy, rarely touched.

### Style

- 4-space indentation, same-line opening braces
- CamelCase for classes, camelCase for variables/methods
- Comments may be in Chinese or English (both acceptable)
- Detekt enforces style rules; run `./gradlew detekt` before pushing

### Async / Threading

- All network and database calls use **Kotlin Coroutines**: `suspend fun` + `withContext(Dispatchers.IO)`
- Use `viewLifecycleOwner.lifecycleScope` for Fragment coroutines
- **From Java code**, use `CoroutineBridge.launchIO(lifecycleOwner, task)` or `IoThreadPoolExecutor` to move DB/network work off the main thread
- `EhDB` provides only `suspend fun xxxAsync()` methods — the legacy `blockingDb` bridge and all `@JvmStatic` wrappers have been removed (W3-5)
- `CoroutineModule` provides `applicationScope` and `ioScope` with `SupervisorJob` + `CoroutineExceptionHandler`
- `LRRCoroutineHelper.runSuspend()` has a **runtime main-thread guard** that throws if called on the UI thread
- **No `AsyncTask` anywhere** — all replaced with `IoThreadPoolExecutor` + `Handler`
- **No main-thread DB calls** — all `EhDB.*()` calls from UI code are wrapped in `IoThreadPoolExecutor` or coroutine scopes
- **No `runBlocking` in new code** — use `scope.launch {}` or `suspend fun` instead. The only surviving `runBlocking` is in `LRRCoroutineHelper.runSuspend()` (Java→Kotlin bridge with `@WorkerThread` + main-thread guard). SpiderDen was migrated to `suspend fun` in W5-3.
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
- Schema version is v19; exported to `app/schemas/` — always provide a `Migration` when bumping
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

- `Settings.kt` (utility: `getContext()`, `getPreferences()`, generic accessors only — field-specific accessors removed in W3-4), `AppearanceSettings`, `DownloadSettings`, `FavoritesSettings`, `NetworkSettings`, `ReadingSettings`, `SecuritySettings`, `UpdateSettings`, `GuideSettings`, `PrivacySettings`
- New settings go into the appropriate typed object; do not add field-specific accessors to `Settings.kt`
- API keys use `EncryptedSharedPreferences` via `LRRAuthManager` — never plaintext

### Package Organization

- LRR API code → `client/api/`
- LRR data classes → `client/api/data/`
- UI scenes → `ui/scene/`; fragments → `ui/fragment/`
- Business logic stays out of Activities/Fragments

---

## Testing

Unit tests live in `app/src/test/java/` (52 files, 573 test methods), covering:

- All LRR API classes (`LRRArchiveApiTest`, `LRRSearchApiTest`, `LRRCategoryApiTest`, etc.) using `MockWebServer`
- All LRR data classes (`LRRArchiveTest`, `LRRCategoryTest`, etc.)
- `LRRTagStatTest` + `LRRTagCacheTest` — tag autocomplete data + cache (18 tests)
- `LRRArchivePagingSourceTest` — Paging 3 source (16 tests)
- `DownloadManagerTest` — download facade state machine, labels, queue, notifications, sort order invariants (29 tests, uses injected `CoroutineScope`)
- `DownloadManagerOrphanLabelBatchTest` — orphan label batch insert verification (5 tests)
- `DownloadEventBusTest` — listener registration/dispatch, WeakReference cleanup (8 tests, W6-2)
- `DownloadRepositoryTest` — collection management, label CRUD, info mutations, sorted insertion (12 tests, W6-2)
- `DownloadSchedulerTest` — worker scheduling, stop operations, concurrency limit, event dispatch (10 tests, W6-2)
- `DownloadSpeedTrackerTest` — speed calculation + remaining time
- `GalleryInfoParcelTest` — Parcelable round-trip for GalleryInfo + DownloadInfo (11 tests)
- `TagEditDialogTest` — tag parsing + formatting round-trip (18 tests)
- `RoomMigrationTest` — schema integrity verification (validates current v19 schema)
- `RoomMigrationPathTest` — migration path tests v9→v10→v11→v12→v13→v14→v15→v16→v17→v18→v19
- `ServerProfileDaoTest` — DAO CRUD verification
- `GalleryInfoDiffTest` — DiffUtil identity/content equality contracts
- `ContentHelperDiffUtilTest` — DiffUtil dispatch operations
- `CoroutineBridgeTest` — Java→coroutine bridge function contracts
- `EhDBMainThreadCheckTest` — blockingDb bridge removal verification + async dirname DAO round-trip
- `ClientModuleTest` — memory cache tier boundary verification (8 tests)
- `CacheableTest` — Cacheable self-registration pattern in ServiceRegistry (6 tests, W3-1)
- `DownloadsViewModelTest` — label switching, search, pagination, sealed event forwarding (31 tests, W17-1)
- `HistoryViewModelTest` — history load/delete/clear, DiffUtil integration (8 tests, W17-1)
- `ServerConfigViewModelTest` — WAN detection, connection verify, profile persistence (12 tests, W17-1)
- `LRRCategoriesViewModelTest` — category CRUD via MockWebServer, pinned sort, loading state (11 tests, W17-2)
- `QuickSearchViewModelTest` — quick search load/delete/reorder via in-memory Room (6 tests, W17-2)
- `ServerListViewModelTest` — profile CRUD, activation, connection verify (10 tests, W17-2)
- `PatternLockoutTest` — pattern lock failure lockout logic with controllable clock + PBKDF2 migration (29 tests, W3-6 + W8-2)
- `TestServiceRegistryHelper` — test infrastructure for ServiceRegistry mocking

Run tests with:
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

- Do not write new Java; use Kotlin for all new code
- Do not use `AsyncTask` or raw `Thread` for network/DB work
- Do not store API keys or secrets in source code or non-encrypted preferences
- Do not use `fallbackToDestructiveMigration()` for Room schema changes
- Do not add `google-services.json` to the repository
- Do not commit `local.properties` or keystore credentials
- Do not use Gson — use `kotlinx-serialization` for all JSON
- Do not hardcode dependency versions in `build.gradle` — use `libs.versions.toml`
- Do not add new singletons to `EhApplication` — use `ServiceRegistry` modules
- Do not add `blockingDb()` bridges or `@JvmStatic` wrappers to `EhDB` — they have been removed (W3-5); use `suspend fun *Async()` variants from a coroutine scope
- Do not use `runBlocking` in new code — use `scope.launch {}` or `suspend fun` instead
- Do not use `toHttpUrlOrNull()!!` to build LRR API URLs — use `parseBaseUrl()` from `LRRApiUtils.kt`
- Do not use `notifyDataSetChanged()` on RecyclerView — use DiffUtil or specific `notifyItem*()` calls
- Do not introduce new visual themes or Material3 components — match existing `RoundSideRectDrawable` + theme attr style
- Do not add `x86_64` ABI filter to release builds — release is arm64-v8a only (debug includes x86_64 for emulator)
- Do not add field-specific accessors to `Settings.kt` — it now contains only utility methods (`getContext`, `getPreferences`, generic typed accessors); new settings go into the appropriate modular settings object (`AppearanceSettings`, `DownloadSettings`, `UpdateSettings`, `GuideSettings`, `PrivacySettings`, etc.) (W3-4)
- Do not hardcode cache-clear calls in `ServiceRegistry.clearAllCaches()` — implement `Cacheable` and register via `ServiceRegistry.registerCacheable()` (W3-1)
- Do not use `GalleryInfo` / `GalleryInfoEntity` in UI-layer code that only displays gallery data — use `GalleryInfoUi` (W3-3); use `GalleryInfoEntity` (via `GalleryInfo` typealias) only at persistence boundaries
- Do not reintroduce Helper Callback interfaces — business logic goes in ViewModels, Scenes observe StateFlow/SharedFlow (W3-2)
- Do not import from `com.hippo.ehviewer.client.lrr` — use `com.lanraragi.reader.client.api` (W3-7)
- Do not import `DownloadRepository`, `DownloadScheduler`, or `DownloadEventBus` from outside the `download/` package — use `DownloadManager` facade only (W6-2)
- Do not add `DownloadInfoListener` implementation to Scene classes — listener logic belongs in ViewModels, Scenes observe sealed `DownloadUiEvent` SharedFlow (W9-1)
- Do not split `DownloadUiEvent` sealed interface back into individual SharedFlows — the single-flow dispatch pattern is intentional (W9-1)
- Do not move extracted helper logic back into Scene classes — keep Scenes as coordinators, helpers own the logic (W9-2, W9-3)
- Do not add fire-and-forget `scope.launch { EhDB.*() }` without try-catch — all DB persistence launches must handle exceptions (W7-4)
- Do not use `EhUrl` / `EhUrlOpener` — renamed to `LRRUrl` / `LRRUrlOpener` (W14-1)
- Do not call `EhDB` history methods directly from UI layer — use `HistoryRepository` via `ServiceRegistry.dataModule.historyRepository` (W17-3); EhDB history methods are `@Deprecated`
- Do not add new Scene classes without a corresponding ViewModel — all 8 functional Scenes now have ViewModels (W14-W15)
