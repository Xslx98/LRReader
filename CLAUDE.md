# CLAUDE.md — LR Reader Codebase Guide

## Project Overview

**LR Reader** is an Android client for [LANraragi](https://github.com/Difegue/LANraragi), a self-hosted manga/archive management server. It is forked from [EhViewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ) and retains the EhViewer framework as its UI/reading foundation while replacing all E-Hentai API calls with LANraragi (LRR) REST API calls.

- **Application ID:** `com.lanraragi.reader`
- **Namespace:** `com.hippo.ehviewer` (legacy, retained from EhViewer)
- **Current Version:** 1.11.0 (versionCode 11100 — formula: `MAJOR*10000 + MINOR*100 + PATCH`)
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
| Database | Room + KSP | 2.6.1, schema v12 (exported to `app/schemas/`) |
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
│   │   │   ├── java/com/hippo/ehviewer/
│   │   │   │   ├── client/lrr/        # LANraragi REST API client (PRIMARY)
│   │   │   │   │   └── data/          # LRR @Serializable data classes
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
│   │   └── test/                      # Unit tests (33 files, LRR API + DAO + DiffUtil + Paging + Filter)
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
| `EhApplication.kt` | App entry point; calls `ServiceRegistry.initialize()` |
| `ServiceRegistry.kt` | Central singleton registry replacing old EhApplication service locator |
| `module/AppModule.kt` | App-level services (crash reporting, analytics) |
| `module/ClientModule.kt` | LRR API clients + auth |
| `module/CoroutineModule.kt` | SupervisorJob + CoroutineExceptionHandler scoped coroutines |
| `module/DataModule.kt` | Room database access |
| `module/NetworkModule.kt` | OkHttp client configuration + DNS |
| `util/CoroutineBridge.kt` | Java→coroutine bridge (launchIO/launchIOGlobal) |
| `EhDB.kt` | Room database access layer (blockingDb main-thread detection) |
| `settings/AppearanceSettings.kt` | UI/theme preferences |
| `settings/DownloadSettings.kt` | Download preferences |
| `settings/NetworkSettings.kt` | Network/proxy preferences |
| `settings/ReadingSettings.kt` | Reader preferences |
| `settings/SecuritySettings.kt` | Auth/security preferences |
| `client/lrr/LRRArchiveApi.kt` | Archive search/list/detail/upload/delete/metadata API |
| `client/lrr/LRRSearchApi.kt` | Search + random endpoint |
| `client/lrr/LRRCategoryApi.kt` | Category CRUD + archive association |
| `client/lrr/LRRDatabaseApi.kt` | Tag statistics + database operations |
| `client/lrr/LRRTagCache.kt` | In-memory tag autocomplete cache (10-min TTL) |
| `client/lrr/LRRArchivePagingSource.kt` | Paging 3 source for gallery list |
| `dao/AppDatabase.kt` | Room database schema (v12, schema exported) |
| `util/FlowBridge.kt` | Java→Kotlin Flow bridge for lifecycle-aware collection |
| `ui/MainActivity.kt` | Main UI entry point + scene routing |
| `ui/GalleryActivity.kt` | Reader/detail view |
| `ui/scene/GalleryListScene.kt` | Gallery browse scene (uses PagingSource for LRR search) |
| `ui/scene/download/DownloadsScene.kt` | Download management (Room Flow + DiffUtil) |
| `ui/scene/gallery/detail/GalleryDetailScene.kt` | Gallery detail view (delegates to helpers + ViewModel) |
| `ui/scene/gallery/detail/GalleryDetailViewModel.kt` | Gallery detail state: info, detail, loading state |
| `ui/scene/gallery/detail/GalleryTagHelper.kt` | Tag display, filter dialogs, tag long-press actions |
| `ui/scene/gallery/detail/GalleryDownloadHelper.kt` | Download state display + DownloadInfoListener |
| `ui/scene/gallery/detail/GalleryDetailRequestHelper.kt` | LRR metadata fetch + category favorite detection |
| `ui/scene/gallery/list/GalleryListViewModel.kt` | Paging 3 ViewModel for gallery list |
| `ui/scene/download/DownloadsViewModel.kt` | Download list state, label, search, pagination ViewModel |
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
- Remaining 17 Java files in `ehviewer` are small callback interfaces, exception classes, legacy parsers, event classes, and annotations.
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
- `EhDB` provides dual API: `suspend fun xxxAsync()` for Kotlin callers (preferred), `@JvmStatic fun xxx()` via `blockingDb` bridge for remaining Java callers only
- `CoroutineModule` provides `applicationScope` and `ioScope` with `SupervisorJob` + `CoroutineExceptionHandler`
- `LRRCoroutineHelper.runSuspend()` has a **runtime main-thread guard** that throws if called on the UI thread
- **No `AsyncTask` anywhere** — all replaced with `IoThreadPoolExecutor` + `Handler`
- **No main-thread DB calls** — all `EhDB.*()` calls from UI code are wrapped in `IoThreadPoolExecutor`
- Thread pool: `IoThreadPoolExecutor` for parallel image/network work

### Networking (OkHttp)

- All LANraragi API calls go through `client/lrr/` package
- `LRRAuthInterceptor` injects API key per request
- `LRRClientProvider` supplies the configured `OkHttpClient`
- DNS-over-HTTPS via `okhttp-dnsoverhttps`
- Cleartext HTTP allowed globally for LAN IP access; API key scoped to configured server via `LRRAuthInterceptor`

### Database (Room)

- All entities and DAOs in `dao/` package
- Use KSP (not KAPT) for annotation processing
- Schema version is v12; exported to `app/schemas/` — always provide a `Migration` when bumping
- **Never** use `fallbackToDestructiveMigration()` in production code
- `AppDatabase.kt` is the single Room database instance

### Serialization

- **All JSON (LRR API responses and new code):** `kotlinx-serialization` with `@Serializable` data classes in `client/lrr/data/`
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

- `Settings.kt` (legacy global), `AppearanceSettings`, `DownloadSettings`, `FavoritesSettings`, `NetworkSettings`, `ReadingSettings`, `SecuritySettings`
- New settings go into the appropriate typed object
- API keys use `EncryptedSharedPreferences` via `LRRAuthManager` — never plaintext

### Package Organization

- LRR API code → `client/lrr/`
- LRR data classes → `client/lrr/data/`
- UI scenes → `ui/scene/`; fragments → `ui/fragment/`
- Business logic stays out of Activities/Fragments

---

## Testing

Unit tests live in `app/src/test/java/`. 33 test files covering:

- All LRR API classes (`LRRArchiveApiTest`, `LRRSearchApiTest`, `LRRCategoryApiTest`, etc.) using `MockWebServer`
- All LRR data classes (`LRRArchiveTest`, `LRRCategoryTest`, etc.)
- `LRRTagStatTest` + `LRRTagCacheTest` — tag autocomplete data + cache (18 tests)
- `LRRArchivePagingSourceTest` — Paging 3 source (16 tests)
- `DownloadManagerTest` — download state machine, labels, queue, notifications (18 tests)
- `DownloadSpeedTrackerTest` — speed calculation + remaining time
- `GalleryInfoParcelTest` — Parcelable round-trip for GalleryInfo + DownloadInfo (11 tests)
- `TagEditDialogTest` — tag parsing + formatting round-trip (18 tests)
- `EhFilterTest` — title/tag/uploader/namespace filtering (35 tests)
- `RoomMigrationTest` — schema integrity verification
- `RoomMigrationPathTest` — migration path tests v9→v10→v11→v12 (11 tests)
- `ServerProfileDaoTest` — DAO CRUD verification
- `GalleryInfoDiffTest` — DiffUtil identity/content equality contracts
- `ContentHelperDiffUtilTest` — DiffUtil dispatch operations
- `CoroutineBridgeTest` — Java→coroutine bridge function contracts
- `EhDBMainThreadCheckTest` — main-thread detection
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

3. **ServiceRegistry:** Replaces the old service-locator pattern in `EhApplication`. Initialize modules here; don't add new statics to `EhApplication`. Includes `CoroutineModule` for scoped coroutines.

4. **LRR API surface:** Full LANraragi REST API wrapped in `client/lrr/`. Add new endpoints here as `suspend` functions returning `@Serializable` data classes.

5. **Room schema migrations:** Schema at v12, exported. Write an explicit `Migration` object for every schema change.

6. **DiffUtil in ContentLayout and DownloadsScene:** `ContentHelper.dispatchDiffUpdates()` for gallery list updates. `DownloadsScene` uses `DownloadInfoDiffCallback` with `gid`-based identity for `onUpdateAll()`/`onReload()`. Avoid `notifyDataSetChanged()` — use specific notifications or DiffUtil.

7. **EhDB dual API:** ~22 remaining `@JvmStatic` bridge methods use `blockingDb()` for Java callers (logs warning on main thread). Kotlin callers should use `suspend fun *Async()` versions directly. Dead methods and Kotlin-only bridges have been removed — do not add new `blockingDb` bridges.

8. **Download subsystem (100% Kotlin):** `DownloadManager.kt` (state management), `LRRDownloadWorker.kt` (background downloads with retry, format validation), `DownloadSpeedTracker.kt` (speed monitoring), `DownloadInfoListener.kt`/`DownloadListener.kt` (interfaces). Thread-safe via `CopyOnWriteArrayList` and `ConcurrentHashMap`. LRU cache (500 MB) for page images. 18 unit tests cover state machine, labels, queue, and notifications.

9. **Multi-server support:** `ServerProfile` entity + `LRRAuthManager` handle per-server credentials. Server selection affects all API calls. Credentials are encrypted.

10. **EhViewer stubs:** `EhGalleryProvider`, `FavoritesScene`, and `FavoriteListSortDialog` are intentional stubs (empty bodies) left for structural compatibility. Do not delete — but do not add logic to them either.

11. **Helper class extraction:** Large Scenes use extracted helpers to reduce line count: `GalleryUploadHelper` (upload/URL download from GalleryListScene), `GallerySearchHelper` (search suggestions/URL building), `DownloadImportHelper` (local archive import from DownloadsScene), `DownloadLabelHelper` (bulk actions: start/stop/delete/move from DownloadsScene), `DownloadFilterHelper` (category filter, sort/filter execution, search callbacks from DownloadsScene), `GalleryTagHelper` (tag display/filter/long-press from GalleryDetailScene), `GalleryDownloadHelper` (download state + DownloadInfoListener from GalleryDetailScene), `GalleryDetailRequestHelper` (LRR metadata fetch + category detection from GalleryDetailScene). Helpers communicate via `Callback` interfaces — follow this pattern for new extractions.

12. **Network tuning:** `NetworkModule.kt` configures `ConnectionPool(10, 5min)`, 200MB HTTP cache, thumbnail 24h cache, deferred `CookieManager.flush()`. Image client has 60s `callTimeout` for large files over slow WAN.

13. **GifHandler lifecycle:** `GifHandler` implements `Closeable` with native `destroy()` that calls `DGifCloseFile()` + `free()`. Always `close()` when done — native memory is NOT garbage collected.

14. **Tag autocomplete:** `LRRTagCache` fetches tag statistics from `/api/database/stats` with 10-min TTL. `SearchBar` queries it for inline suggestions alongside local `EhTagDatabase` translations. Cache cleared on server switch via `ServiceRegistry.clearAllCaches()`.

15. **Room Flow observation:** `DownloadRoomDao` provides `Flow<List<DownloadInfo>>` queries. `DownloadsScene` subscribes via `FlowBridge.collectFlow()` for reactive list updates. Flow handles structure changes (add/remove/state); existing callbacks handle real-time download progress (`@Ignore` fields not persisted to Room).

16. **Paging 3 integrated:** `LRRArchivePagingSource` used directly by `GalleryListScene.GalleryListHelper.getPageData()` for LRR search. `GalleryListViewModel` provides `Flow<PagingData<GalleryInfo>>` with `SearchParams` invalidation. ContentHelper pagination framework preserved as the adapter layer. Config: pageSize=100, prefetchDistance=20.

17. **Tag editor:** `TagEditDialog.kt` shows a grouped chip-style editor using the same `RoundSideRectDrawable` visual style as the detail page. Click to edit, long-press to delete, [+] to add per namespace. Supports `AutoCompleteTextView` with `LRRTagCache` suggestions. Entry point: pencil icon in tag display area.

18. **DownloadsViewModel:** `DownloadsViewModel.kt` manages download list state (current label, download list, back-list, search key, searching flag, pagination state, spider info cache) as `StateFlow` properties. `DownloadsScene` accesses state via property delegates that read/write ViewModel flows. The ViewModel is scoped to the parent Activity via `ViewModelProvider(requireActivity())` because Scene fragments may be recreated. View references, adapters, DiffUtil dispatch, and dialog/navigation remain in the Scene.

19. **GalleryDetailViewModel:** `GalleryDetailViewModel.kt` manages gallery detail state (galleryInfo, galleryDetail, downloadInfo, gid, token, action, loading state) as `StateFlow` properties. `GalleryDetailScene` accesses state via shortcut property delegates that read/write ViewModel flows. Derived accessors (`getEffectiveGid()`, `getEffectiveToken()`, `getEffectiveUploader()`, `getEffectiveCategory()`, `getEffectiveGalleryInfo()`) centralize the fallback logic (detail > info > args). Cache lookup via `tryLoadFromCache()`. Scoped to Activity like `DownloadsViewModel`.

20. **Reader settings immediate-apply:** `GalleryMenuHelper` applies settings immediately when any control changes (Spinner, SeekBar, Switch) — no confirm button. Uses an `initialized` flag to suppress listener callbacks during initial value loading. Brightness has a live preview listener delegated from `GalleryActivity`.

21. **Network security:** `network_security_config.xml` allows cleartext globally (`cleartextTrafficPermitted="true"`) because LANraragi servers are typically accessed via bare LAN IP over HTTP. Android's `<domain-config>` has no CIDR/wildcard-IP support. `LRRAuthInterceptor` ensures the API key is only sent to the configured server host. User-installed CAs are trusted for self-signed HTTPS setups.

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
- Do not add new `blockingDb()` bridges in `EhDB` — Kotlin callers use `suspend` Async versions directly
- Do not use `notifyDataSetChanged()` on RecyclerView — use DiffUtil or specific `notifyItem*()` calls
- Do not introduce new visual themes or Material3 components — match existing `RoundSideRectDrawable` + theme attr style
- Do not add `x86_64` ABI filter to release builds — release is arm64-v8a only (debug includes x86_64 for emulator)
