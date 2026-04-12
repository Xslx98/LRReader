# CLAUDE.md — LR Reader Codebase Guide

## Project Overview

**LR Reader** is an Android client for [LANraragi](https://github.com/Difegue/LANraragi), a self-hosted manga/archive management server. It is forked from [EhViewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ) and retains the EhViewer framework as its UI/reading foundation while replacing all E-Hentai API calls with LANraragi (LRR) REST API calls.

- **Application ID:** `com.lanraragi.reader`
- **Namespace:** `com.hippo.ehviewer` (legacy, retained from EhViewer)
- **Current Version:** 1.11.4 (versionCode 11104 — formula: `MAJOR*10000 + MINOR*100 + PATCH`)
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
| `dao/AppDatabase.kt` | Room database schema (v18, schema exported) |
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
| `ui/scene/gallery/list/GalleryListViewModel.kt` | Paging 3 ViewModel for gallery list (`Flow<PagingData<GalleryInfoUi>>`) |
| `ui/scene/gallery/list/GalleryListSearchHelper.kt` | SearchBar interaction + query construction extracted from GalleryListScene (W9-3) |
| `ui/scene/download/DownloadsViewModel.kt` | Download list state, labels, filter/sort, search, import, bulk ops, sealed DownloadUiEvent (W9-1) |
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
- Schema version is v18; exported to `app/schemas/` — always provide a `Migration` when bumping
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

Unit tests live in `app/src/test/java/`, covering:

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
- `RoomMigrationTest` — schema integrity verification (validates current v18 schema)
- `RoomMigrationPathTest` — migration path tests v9→v10→v11→v12→v13→v14→v15→v16→v17→v18
- `ServerProfileDaoTest` — DAO CRUD verification
- `GalleryInfoDiffTest` — DiffUtil identity/content equality contracts
- `ContentHelperDiffUtilTest` — DiffUtil dispatch operations
- `CoroutineBridgeTest` — Java→coroutine bridge function contracts
- `EhDBMainThreadCheckTest` — blockingDb bridge removal verification + async dirname DAO round-trip
- `ClientModuleTest` — memory cache tier boundary verification (8 tests)
- `CacheableTest` — Cacheable self-registration pattern in ServiceRegistry (6 tests, W3-1)
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

---

## Key Architectural Notes

1. **EhViewer heritage:** The `com.hippo.*` namespace and scene-based navigation are retained from EhViewer. Do not rename packages without understanding all transitive references.

2. **Scene-based navigation:** Uses a custom `Scene` framework (not Jetpack Navigation). UI transitions via `startScene()` / `popScene()`.

3. **ServiceRegistry:** Replaces the old service-locator pattern in `EhApplication`. Initialize modules here; don't add new statics to `EhApplication`. Includes `CoroutineModule` for scoped coroutines. Cache clearing uses the `Cacheable` interface (W3-1): modules implement `Cacheable` and self-register via `ServiceRegistry.registerCacheable()`, so `clearAllCaches()` iterates registered instances instead of hardcoding calls. ServiceRegistry has zero imports from the LRR API package.

4. **LRR API surface:** Full LANraragi REST API wrapped in `client/api/`. Add new endpoints here as `suspend` functions returning `@Serializable` data classes. Use `parseBaseUrl(baseUrl)` from `LRRApiUtils.kt` to build URLs — never `toHttpUrlOrNull()!!`.

5. **Room schema migrations:** Schema at v18, exported. Write an explicit `Migration` object for every schema change.

6. **DiffUtil in ContentLayout and DownloadsScene:** `ContentHelper.dispatchDiffUpdates()` for gallery list updates. `DownloadsScene` uses `DownloadInfoDiffCallback` with `gid`-based identity for diff updates received via `DownloadsViewModel.downloadEvent` sealed flow (W8-1 replaced all `notifyDataSetChanged` in DownloadsScene with DiffUtil; W5-1 did the same for GalleryListScene). Avoid `notifyDataSetChanged()` — use specific notifications or DiffUtil.

7. **EhDB — all suspend, no bridges (W3-5, 2026-04-11):** The legacy `blockingDb()` wrapper and all `@JvmStatic` bridge methods have been deleted. `EhDB` now exposes only `suspend fun *Async()` methods. `SpiderDen.getGalleryDownloadDir()` was migrated to `suspend fun` in W5-3, eliminating the last production `runBlocking`. The only remaining `runBlocking` is in `LRRCoroutineHelper.runSuspend()` — a Java→Kotlin bridge utility with `@WorkerThread` + runtime main-thread guard. Do NOT add `blockingDb` bridges back to `EhDB`.

8. **Download subsystem (100% Kotlin, coroutine-based, W6-2 decomposed):** The download subsystem is decomposed into four classes with clear single responsibilities:
    - **`DownloadManager.kt`** (~340 lines) — thin facade that preserves the public API and orchestrates the three components below. All external callers (`DownloadsViewModel`, `DownloadService`, `GalleryListScene`, etc.) interact only with this facade.
    - **`DownloadRepository.kt`** (~750 lines) — owns all in-memory collections (`allInfoList`, `allInfoMap`, `labelInfoMap`, `labelList`, `labelSet`, `defaultInfoList`, `labelCountMap`), DB load/reload lifecycle (`startLoading`, `initDeferred`), label CRUD (`addLabel`, `renameLabel`, `deleteLabel`, `changeLabel`, `moveLabel`), info mutations (`addInfo`, `removeInfo`, `replaceInfo`, `addInfoSorted`, `removeInfoBatch`), and DB persistence helpers (`persistInfo`, `persistHistory`). Companion has `DATE_DESC_COMPARATOR` and `insertSorted()`.
    - **`DownloadScheduler.kt`** (~440 lines) — owns worker lifecycle (`waitList`, `activeTasks`, `activeWorkers`), state machine (`ensureDownload`, `stopDownload`, `stopAllDownload`, `stopRangeDownload`), `DownloadEvent` sealed interface, `dispatchEvent`/`postEvent`, and `PerTaskListener` (bridges `SpiderQueen.OnSpiderListener` to `DownloadEvent`).
    - **`DownloadEventBus.kt`** (~100 lines) — owns `DownloadInfoListener` registration/dispatch (`WeakReference` list, `forEachListener` with GC cleanup), `DownloadListener` single-listener, and `postToMain` handler dispatch.
    
    `LRRDownloadWorker.kt` (background downloads with retry, format validation) and `DownloadSpeedTracker.kt` (speed monitoring) are unchanged. All DB writes use `scope.launch { try { EhDB.*Async() } catch { Log.e } }` — every fire-and-forget launch block has try-catch (W7-4). Worker callbacks use immutable `sealed interface DownloadEvent` (not mutable object pool). `awaitInit()` has 10s timeout + main-thread guard. `DownloadRepository` accepts `CoroutineScope` + `CoroutineDispatcher` for testability (W8-3 removed Handler dependency). `containLabel()` uses `HashSet` for O(1) lookup. LRU cache (500 MB) for page images. Orphan labels are batch-inserted in a single `withTransaction` (W2-1). All `Collections.sort` replaced with `insertSorted()` binary insertion for O(log N) maintenance of `DATE_DESC` order (W2-2). 59 unit tests across 4 test files cover facade API, repository collections, scheduler state machine, event bus dispatch, labels, queue, notifications, orphan label batching, and sort order invariants.

9. **Multi-server support:** `ServerProfile` entity + `LRRAuthManager` handle per-server credentials. Server selection affects all API calls. Credentials are encrypted.

10. **EhViewer stubs removed (C6, 2026-04-08):** `EhGalleryProvider`, `FavoritesScene`, and `FavoriteListSortDialog` were deleted after investigation confirmed zero live callers. See item 24 for full cleanup history.

11. **Helper→ViewModel migration (W3-2, 2026-04-11):** Business logic formerly in Helper classes has been moved into ViewModels. All Callback interfaces eliminated. Deleted: `GalleryDetailRequestHelper` (→ `GalleryDetailViewModel.requestGalleryDetail()`), `GalleryDownloadHelper` (→ `GalleryDetailViewModel` download state StateFlow + DownloadInfoListener), `DownloadFilterHelper` (→ `DownloadsViewModel` filter/sort/search). Converted to stateless utilities: `GalleryTagHelper` (object with explicit parameters), `DownloadLabelHelper` (dialog-only utility, bulk ops in ViewModel), `DownloadImportHelper` (launcher-only, processing in ViewModel). Remaining helpers that were already stateless: `GalleryUploadHelper`, `GallerySearchHelper`. Scenes observe ViewModel StateFlow/SharedFlow — do NOT reintroduce Callback interfaces.

12. **Network tuning:** `NetworkModule.kt` configures `ConnectionPool(10, 5min)`, 200MB HTTP cache. Thumbnail responses get injected `Cache-Control: public, max-age=3600, stale-while-revalidate=82800` (1h fresh + 23h stale, 24h total) because LANraragi sends no caching headers (W2-8). `CookieJar.NO_COOKIES` — no cookies stored or sent (LANraragi uses Bearer token auth). Image client has 60s `callTimeout` for large files over slow WAN.

13. **GifHandler lifecycle:** `GifHandler` implements `Closeable` with native `destroy()` that calls `DGifCloseFile()` + `free()`. Always `close()` when done — native memory is NOT garbage collected.

14. **Tag autocomplete:** `LRRTagCache` fetches tag statistics from `/api/database/stats` with 10-min TTL. `SearchBar` queries it for inline suggestions alongside local `EhTagDatabase` translations. Cache cleared on server switch via `ServiceRegistry.clearAllCaches()`.

15. **Room Flow observation:** `DownloadRoomDao` provides `Flow<List<DownloadInfo>>` queries. `DownloadsScene` subscribes via `FlowBridge.collectFlow()` for reactive list updates. Flow handles structure changes (add/remove/state); real-time download progress (`@Ignore` fields not persisted to Room) is delivered via `DownloadsViewModel`'s `DownloadInfoListener` → sealed `DownloadUiEvent` SharedFlow pipeline (W9-1).

16. **Paging 3 integrated:** `LRRArchivePagingSource` returns `PagingData<GalleryInfoUi>` (W3-3). `GalleryListViewModel` provides `Flow<PagingData<GalleryInfoUi>>` with `SearchParams` invalidation. ContentHelper pagination framework preserved as the adapter layer. Config: pageSize=50, prefetchDistance=10 (W2-5, halved from 100/20 to reduce OOM risk).

17. **Tag editor:** `TagEditDialog.kt` shows a grouped chip-style editor using the same `RoundSideRectDrawable` visual style as the detail page. Click to edit, long-press to delete, [+] to add per namespace. Supports `AutoCompleteTextView` with `LRRTagCache` suggestions. Entry point: pencil icon in tag display area.

18. **DownloadsViewModel + sealed DownloadUiEvent (W9-1, 2026-04-11):** `DownloadsViewModel.kt` manages download list state (current label, download list, back-list, search key, searching flag, pagination state, spider info cache) as `StateFlow` properties. Also owns filter/sort execution, bulk download operations, archive import processing, and `DownloadInfoListener` implementation. After W9-1: the 9 individual SharedFlows were merged into a single `sealed interface DownloadUiEvent` with 9 event types (`ItemAdded`, `ItemRemoved`, `ItemUpdated`, `DiffUpdate`, `Replaced`, `LabelRenamed`, `LabelDeleted`, `LabelsChanged`, `Reloaded`), emitted through one `SharedFlow<DownloadUiEvent>`. `DownloadsScene` observes via a single `collectFlow(viewModel.downloadEvent) { when(event) { ... } }` dispatch. Do NOT split back into individual SharedFlows.

19. **GalleryDetailViewModel:** `GalleryDetailViewModel.kt` manages gallery detail state (galleryInfo, galleryDetail, downloadInfo, gid, token, action, loading state) as `StateFlow` properties. After W3-2: also owns async LRR metadata fetch (`requestGalleryDetail()` from GalleryDetailRequestHelper), download state tracking (`downloadState` StateFlow + `DownloadInfoListener` from GalleryDownloadHelper), and error events (`detailError` SharedFlow). Derived accessors centralize the fallback logic (detail > info > args). Scoped to parent Activity.

20. **Reader settings immediate-apply:** `GalleryMenuHelper` applies settings immediately when any control changes (Spinner, SeekBar, Switch) — no confirm button. Uses an `initialized` flag to suppress listener callbacks during initial value loading. Brightness has a live preview listener delegated from `GalleryActivity`.

21. **Network security:** `network_security_config.xml` allows cleartext globally (`cleartextTrafficPermitted="true"`) because LANraragi servers are typically accessed via bare LAN IP over HTTP. Android's `<domain-config>` has no CIDR/wildcard-IP support. `LRRCleartextRejectionInterceptor` enforces application-layer cleartext policy (per-profile opt-in, host:port match, scheme downgrade rejection). `LRRAuthInterceptor` ensures the API key is only sent to the configured server host. `getAllowCleartext()` defaults to `true` when sPrefs is available (backward-compatible for existing HTTP profiles) but returns `false` when sPrefs is null / KeyStore unavailable (fail-closed, W7-1). User-installed CAs are trusted for self-signed HTTPS setups.

22. **EncryptedSharedPreferences recovery:** When Android KeyStore is unavailable (device migration, corruption), `LRRAuthManager.isNeedsReauthentication()` returns true. `MainActivity.onCreate2()` checks this flag and shows an AlertDialog directing the user to `ServerListScene` to re-enter credentials. Without this, the app silently falls back to the initial setup page because `isConfigured()` returns false.

23. **App startup order:** `EhApplication.onCreate()` initializes core services synchronously (Settings, LRRAuthManager, EhDB, ServiceRegistry) then defers heavy work to background: JNI initialization (Image, Native, A7Zip, BitmapUtils) runs on `IoThreadPoolExecutor`. Profile migration and old DB merge also run on background threads via `AppModule.bootScope` (W1-1).

24. **Legacy EhViewer subsystems removed:** Over the course of cleanups C2 through C6, several dead EhViewer-era subsystems were deleted from the codebase. Investigation uncovered each of these as either (a) structurally severed during the EhViewer→LRR conversion with no consumer ever reconnected, or (b) a stub left "for structural compatibility" whose structural compatibility argument had decayed to zero live references:
    - **EhFilter** (C2, 2026-04-07): user-defined title/uploader/tag blacklist. Filters were written to `FILTER` table but the consumption path (`filterTitle`/`filterTag`/`filterUploader`) had zero callers. Deleted: `EhFilter`, `Filter` entity, `EhFilterTest`, UI entry points, string resources. Room v14→v15 dropped `FILTER`.
    - **BlackList** (C3, 2026-04-07): user-defined bad-uploader blacklist. `BlackListActivity` was fully unreachable — no Intent/menu/preference ever launched it. Deleted: Activity, entity, layouts, manifest declaration, DAO, EhDB API, strings, arrays, ids. Room v15→v16 dropped `Black_List`. Side benefit: finally deleted the 9-year-old EhViewer-era homophobic-slur column-mapped field name.
    - **BookmarkInfo** (C4, 2026-04-08): per-gallery "reader bookmark" entity. Zero callers for any of its DAO methods anywhere outside the DAO; not even wrapped in `EhDB.kt`. Deleted: entity + DAO section. Room v16→v17 dropped `BOOKMARKS`.
    - **GalleryTags** (C5, 2026-04-08): per-gallery tag cache. Dead cache — `insertGalleryTags`/`updateGalleryTags` had zero callers, so the table was never populated; the single reader (`queryGalleryTags` via `DownloadListInfosExecutor.searchTagList`) always got null. Real tag data is populated directly into `DownloadInfo.tgList` by `LRRArchive.toGalleryInfoUi()` from the LRR API response. Deleted: entity + DAO section + `EhDB.queryGalleryTags` blockingDb bridge + the dead `searchTagList`/`parserList` helpers in `DownloadListInfosExecutor`. Room v17→v18 dropped `Gallery_Tags`. Also brought the `@JvmStatic blockingDb` bridge count from 3 to 2.
    - **Stubs** (C6, 2026-04-08): `FavoritesScene` (empty scene, only referenced via dead `registerLaunchMode`), `FavoriteListSortDialog` (literally an empty class), and `EhGalleryProvider` (stub provider whose `ACTION_EH` trigger path at `GalleryDetailScene` was confirmed dead — `R.id.index` is declared but never set by any code, preview grid is never populated from LRR metadata). Deleted the classes, the `ACTION_EH` constant + branch in `GalleryActivity`, and the dead click handler in `GalleryDetailScene`.

    LR Reader is a private library client where users curate stored content directly — there is no use case for in-app personal blocklists, per-gallery bookmarks, or EhViewer-era favourites. If you find any surviving references to `EhFilter`, `BlackList`, `BookmarkInfo`, `GalleryTags`, `FavoritesScene`, `FavoriteListSortDialog`, `EhGalleryProvider`, `ACTION_EH`, `Filter::class`, `BlackList::class`, `BookmarkInfo::class`, `GalleryTags::class`, `R.string.filter_*`, `R.string.blacklist*`, or the dead orphan test fixture `EhNews.html`, they are bugs from an incomplete revert; remove them.

    **Remaining cosmetic cleanup (not blocking):** `gallery_detail_previews.xml` layout file + its `<include>` in `gallery_detail_content.xml` and `R.id.index` in `values/ids.xml` are leftover resources that are no longer referenced by any code after EhGalleryProvider removal. Safe to delete in a future UI-resources sweep.

25. **ProGuard log stripping:** Release builds strip `Log.v()`, `Log.d()`, `Log.i()`, and `Log.w()` via `-assumenosideeffects`. Only `Log.e()` is preserved for crash diagnostics.

26. **GalleryInfo data model split (W3-3, 2026-04-11):** `GalleryInfo` class renamed to `GalleryInfoEntity` (Room entity base with `@ColumnInfo` fields), with `typealias GalleryInfo = GalleryInfoEntity` for backward compatibility. New `GalleryInfoUi` class (Parcelable, no Room annotations) for UI display. Mapper functions in `mapper/GalleryInfoMapper.kt`: `GalleryInfo.toUi()`, `GalleryInfoUi.toEntity()`. The gallery list pipeline (PagingSource → ContentHelper → Adapter → Scene) uses `GalleryInfoUi`. Detail/download code at persistence boundaries uses `GalleryInfoEntity` / `DownloadInfo`. `LRRArchive.toGalleryInfoUi()` returns the UI type; `toGalleryInfo()` wraps it for DB callers.

27. **Pattern lock KeyStore binding (W3-6 + W8-2, 2026-04-11):** `LRRAuthManager` pattern lock wraps the PBKDF2 hash with KeyStore-bound AES-GCM via `BiometricPrompt` on devices with strong biometrics. PBKDF2 iterations raised from 100K to 200K (W8-2), with transparent migration: `verifyPattern()`/`verifyPatternWithCipher()` try 200K first, fall back to 100K, and on legacy match re-hash + save with 200K. KeyStore-bound patterns downgrade to PBKDF2-only during migration (GCM cipher is single-use). Failure lockout persisted in plain SharedPreferences: 5 failures → 30s lock, 10 → 5min. Devices without biometrics fall back to PBKDF2-only. Controllable `clockMillis` lambda for test determinism. 29 unit tests in `PatternLockoutTest.kt` (7 migration tests added in W8-2).

28. **Package rename stage 1 (W3-7, 2026-04-11):** `com.hippo.ehviewer.client.lrr.*` → `com.lanraragi.reader.client.api.*`. All 25 source files moved, all imports updated across 80+ files, ProGuard rules updated, Gradle `verifyNoBaseUrlConcat` task path updated. SharedPreferences keys unchanged (user data preserved). Test file directory structure (`app/src/test/java/com/hippo/ehviewer/client/lrr/`) NOT moved (test infrastructure, not published — may move in Stage 2).

29. **Cacheable interface (W3-1, 2026-04-11):** `interface Cacheable { fun clearCache() }` in `module/` package. `NetworkModule`, `DataModule` implement it and self-register via `ServiceRegistry.registerCacheable()`. `LRRTagCache` implements `Cacheable` and is registered via `ClientModule.init`. `ServiceRegistry.clearAllCaches()` iterates registered cacheables — no hardcoded cache calls, no imports from `client.lrr`. 6 unit tests in `CacheableTest.kt`.

30. **DownloadManager decomposition (W6-2, 2026-04-11):** `DownloadManager.kt` was a 1448-line God Class with 6 types of responsibility. Decomposed into 4 classes: `DownloadEventBus` (listener dispatch), `DownloadRepository` (collection management + DB persistence), `DownloadScheduler` (worker lifecycle + state machine), and `DownloadManager` (thin facade, ~340 lines). Public API unchanged — all external callers see only `DownloadManager`. Components are `internal` to the `download/` package. `DownloadSpeedTracker.Callback` routes through the three components. 30 new unit tests across `DownloadEventBusTest` (8), `DownloadRepositoryTest` (12), `DownloadSchedulerTest` (10). Do NOT bypass the facade — external code should import `DownloadManager`, never `DownloadRepository`/`DownloadScheduler`/`DownloadEventBus` directly.

31. **DownloadsScene listener migration (W6-4 → W9-1, 2026-04-11):** `DownloadsScene` no longer implements `DownloadInfoListener` or holds a `_downloadManager` field. `DownloadsViewModel` implements `DownloadInfoListener`, registers in `init {}`, unregisters in `onCleared()`. After W9-1: the 9 individual SharedFlows were merged into a single `sealed interface DownloadUiEvent` (see item 18). Scene observes via one `collectFlow(viewModel.downloadEvent) { when ... }` dispatch. Do NOT re-add `DownloadInfoListener` to Scenes — listener logic belongs in ViewModels (see also W3-2 item 11). Do NOT split the sealed event back into individual SharedFlows.

32. **Scene decomposition (W9-2 + W9-3, 2026-04-11):** Three God Scenes were decomposed by extracting helper classes:
    - **`DownloadsScene`** (1393→1259 lines): extracted `DownloadGuideHelper` (showcase overlays), `DownloadPaginationHelper` (page navigation), `DownloadDragDropHelper` (item reordering). Scene retains core view setup + ViewModel observation.
    - **`GalleryDetailScene`** (1054→694 lines): extracted `DetailHeaderBinder` (header view binding, thumbnail, circular reveal) and `DetailActionHandler` (action button click handling).
    - **`GalleryListScene`** (974→892 lines): extracted `GalleryListSearchHelper` (SearchBar interaction, query construction). `GalleryDrawerHelper` already existed for drawer logic.
    
    Extracted helpers are `internal` classes in the same package. Scene creates them in `onCreateView` and delegates. Do NOT move business logic back into Scene classes — keep Scene as coordinator.

33. **Download subsystem resilience (W7-4, 2026-04-11):** All 15 `scope.launch { EhDB.*() }` blocks across `DownloadRepository` (12) and `DownloadManager` (3) now have `try-catch(e: Exception) { Log.e(...) }`. DB failures no longer silently propagate to the global CoroutineExceptionHandler. `getLabelCount()` no longer defensively catches NPE (all `labelCountMap` writes confirmed on main thread). `DownloadManager.addDownloadInfoListener`/`removeDownloadInfoListener` accept non-nullable `DownloadInfoListener` parameter (W7-2, `!!` eliminated).

---

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
