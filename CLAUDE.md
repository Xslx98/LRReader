# CLAUDE.md — LR Reader Codebase Guide

## Project Overview

**LR Reader** is an Android client for [LANraragi](https://github.com/Difegue/LANraragi), a self-hosted manga/archive management server. It is forked from [EhViewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ) and retains the EhViewer framework as its UI/reading foundation while replacing all E-Hentai API calls with LANraragi (LRR) REST API calls.

- **Application ID:** `com.lanraragi.reader`
- **Namespace:** `com.hippo.ehviewer` (legacy, retained from EhViewer)
- **Current Version:** 1.11.3 (versionCode 11103 — formula: `MAJOR*10000 + MINOR*100 + PATCH`)
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
| `EhDB.kt` | Room database access layer (`blockingDb` hard-throws on main thread in debug builds) |
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
| `ui/scene/download/DownloadsScene.kt` | Download management (Room Flow + DiffUtil) |
| `ui/scene/gallery/detail/GalleryDetailScene.kt` | Gallery detail view (delegates to helpers + ViewModel) |
| `ui/scene/gallery/detail/GalleryDetailViewModel.kt` | Gallery detail state: info, detail, loading state |
| `ui/scene/gallery/detail/GalleryTagHelper.kt` | Tag display, tag long-press actions |
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
- `EhDB` provides dual API: `suspend fun xxxAsync()` for Kotlin callers (preferred), `@JvmStatic fun xxx()` via `blockingDb` bridge for the few remaining legacy callers (currently all Kotlin — see item 7 below for the inventory)
- `CoroutineModule` provides `applicationScope` and `ioScope` with `SupervisorJob` + `CoroutineExceptionHandler`
- `LRRCoroutineHelper.runSuspend()` has a **runtime main-thread guard** that throws if called on the UI thread
- **No `AsyncTask` anywhere** — all replaced with `IoThreadPoolExecutor` + `Handler`
- **No main-thread DB calls** — all `EhDB.*()` calls from UI code are wrapped in `IoThreadPoolExecutor` or coroutine scopes
- **No `runBlocking` in new code** — use `scope.launch {}` or `suspend fun` instead. The only surviving production `runBlocking` is inside `EhDB.blockingDb()` (legacy bridge, see item 7) and `EhDB.mergeOldDB()` (W1-7 will convert to suspend).
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

- `Settings.kt` (legacy global), `AppearanceSettings`, `DownloadSettings`, `FavoritesSettings`, `NetworkSettings`, `ReadingSettings`, `SecuritySettings`
- New settings go into the appropriate typed object
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
- `DownloadManagerTest` — download state machine, labels, queue, notifications (18 tests, uses injected `CoroutineScope`)
- `DownloadSpeedTrackerTest` — speed calculation + remaining time
- `GalleryInfoParcelTest` — Parcelable round-trip for GalleryInfo + DownloadInfo (11 tests)
- `TagEditDialogTest` — tag parsing + formatting round-trip (18 tests)
- `RoomMigrationTest` — schema integrity verification (validates current v18 schema)
- `RoomMigrationPathTest` — migration path tests v9→v10→v11→v12→v13→v14→v15→v16→v17→v18
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

4. **LRR API surface:** Full LANraragi REST API wrapped in `client/api/`. Add new endpoints here as `suspend` functions returning `@Serializable` data classes. Use `parseBaseUrl(baseUrl)` from `LRRApiUtils.kt` to build URLs — never `toHttpUrlOrNull()!!`.

5. **Room schema migrations:** Schema at v18, exported. Write an explicit `Migration` object for every schema change.

6. **DiffUtil in ContentLayout and DownloadsScene:** `ContentHelper.dispatchDiffUpdates()` for gallery list updates. `DownloadsScene` uses `DownloadInfoDiffCallback` with `gid`-based identity for `onUpdateAll()`/`onReload()`. Avoid `notifyDataSetChanged()` — use specific notifications or DiffUtil.

7. **EhDB dual API:** 2 remaining `@JvmStatic` `blockingDb()` bridge methods exist as a legacy compatibility layer. Inventory (updated after C5, 2026-04-08): `getDownloadDirname` (1 caller) and `putDownloadDirname` (3 callers) — both called from `SpiderDen.kt` during download directory resolution, all Kotlin and all already off the main thread. The historical bridges `putDownloadInfo` (dead bridge, removed) and `queryGalleryTags` (dead cache, removed in C5 along with the Gallery_Tags entity) are gone. **Behaviour after W1-2**: `blockingDb()` hard-throws `IllegalStateException` in **debug** builds when called from the main thread, forcing offenders to be migrated; **release** builds only `Log.w` (which W1-6's R8 `-assumenosideeffects` rule then strips entirely, making release a silent passthrough). Kotlin callers must use the `suspend fun *Async()` variants from a coroutine scope. Do NOT add new `blockingDb` bridges.

8. **Download subsystem (100% Kotlin, coroutine-based):** `DownloadManager.kt` (state management via `Mutex` + `CompletableDeferred`), `LRRDownloadWorker.kt` (background downloads with retry, format validation), `DownloadSpeedTracker.kt` (speed monitoring), `DownloadInfoListener.kt`/`DownloadListener.kt` (interfaces). Thread-safe via `Mutex` for shared state, `CopyOnWriteArrayList` for active tasks, `ConcurrentHashMap` for workers. All DB writes use `scope.launch { EhDB.*Async() }` — never `runBlocking`. Worker callbacks use immutable `sealed interface DownloadEvent` (not mutable object pool). `awaitInit()` has 10s timeout + main-thread guard. Constructor accepts `CoroutineScope` for testability. `containLabel()` uses `HashSet` for O(1) lookup. LRU cache (500 MB) for page images. 18 unit tests cover state machine, labels, queue, and notifications.

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

22. **EncryptedSharedPreferences recovery:** When Android KeyStore is unavailable (device migration, corruption), `LRRAuthManager.isNeedsReauthentication()` returns true. `MainActivity.onCreate2()` checks this flag and shows an AlertDialog directing the user to `ServerListScene` to re-enter credentials. Without this, the app silently falls back to the initial setup page because `isConfigured()` returns false.

23. **App startup order:** `EhApplication.onCreate()` initializes core services synchronously (Settings, LRRAuthManager, EhDB, ServiceRegistry) then defers heavy work to background: JNI initialization (Image, Native, A7Zip, BitmapUtils) runs on `IoThreadPoolExecutor`. Profile migration and old DB merge also run on background threads via `AppModule.bootScope` (W1-1).

24. **Legacy EhViewer subsystems removed:** Over the course of cleanups C2 through C6, several dead EhViewer-era subsystems were deleted from the codebase. Investigation uncovered each of these as either (a) structurally severed during the EhViewer→LRR conversion with no consumer ever reconnected, or (b) a stub left "for structural compatibility" whose structural compatibility argument had decayed to zero live references:
    - **EhFilter** (C2, 2026-04-07): user-defined title/uploader/tag blacklist. Filters were written to `FILTER` table but the consumption path (`filterTitle`/`filterTag`/`filterUploader`) had zero callers. Deleted: `EhFilter`, `Filter` entity, `EhFilterTest`, UI entry points, string resources. Room v14→v15 dropped `FILTER`.
    - **BlackList** (C3, 2026-04-07): user-defined bad-uploader blacklist. `BlackListActivity` was fully unreachable — no Intent/menu/preference ever launched it. Deleted: Activity, entity, layouts, manifest declaration, DAO, EhDB API, strings, arrays, ids. Room v15→v16 dropped `Black_List`. Side benefit: finally deleted the 9-year-old EhViewer-era homophobic-slur column-mapped field name.
    - **BookmarkInfo** (C4, 2026-04-08): per-gallery "reader bookmark" entity. Zero callers for any of its DAO methods anywhere outside the DAO; not even wrapped in `EhDB.kt`. Deleted: entity + DAO section. Room v16→v17 dropped `BOOKMARKS`.
    - **GalleryTags** (C5, 2026-04-08): per-gallery tag cache. Dead cache — `insertGalleryTags`/`updateGalleryTags` had zero callers, so the table was never populated; the single reader (`queryGalleryTags` via `DownloadListInfosExecutor.searchTagList`) always got null. Real tag data is populated directly into `DownloadInfo.tgList` by `LRRArchive.toGalleryInfo()` from the LRR API response. Deleted: entity + DAO section + `EhDB.queryGalleryTags` blockingDb bridge + the dead `searchTagList`/`parserList` helpers in `DownloadListInfosExecutor`. Room v17→v18 dropped `Gallery_Tags`. Also brought the `@JvmStatic blockingDb` bridge count from 3 to 2.
    - **Stubs** (C6, 2026-04-08): `FavoritesScene` (empty scene, only referenced via dead `registerLaunchMode`), `FavoriteListSortDialog` (literally an empty class), and `EhGalleryProvider` (stub provider whose `ACTION_EH` trigger path at `GalleryDetailScene` was confirmed dead — `R.id.index` is declared but never set by any code, preview grid is never populated from LRR metadata). Deleted the classes, the `ACTION_EH` constant + branch in `GalleryActivity`, and the dead click handler in `GalleryDetailScene`.

    LR Reader is a private library client where users curate stored content directly — there is no use case for in-app personal blocklists, per-gallery bookmarks, or EhViewer-era favourites. If you find any surviving references to `EhFilter`, `BlackList`, `BookmarkInfo`, `GalleryTags`, `FavoritesScene`, `FavoriteListSortDialog`, `EhGalleryProvider`, `ACTION_EH`, `Filter::class`, `BlackList::class`, `BookmarkInfo::class`, `GalleryTags::class`, `R.string.filter_*`, `R.string.blacklist*`, or the dead orphan test fixture `EhNews.html`, they are bugs from an incomplete revert; remove them.

    **Remaining cosmetic cleanup (not blocking):** `gallery_detail_previews.xml` layout file + its `<include>` in `gallery_detail_content.xml` and `R.id.index` in `values/ids.xml` are leftover resources that are no longer referenced by any code after EhGalleryProvider removal. Safe to delete in a future UI-resources sweep.

25. **ProGuard log stripping:** Release builds strip `Log.v()`, `Log.d()`, `Log.i()`, and `Log.w()` via `-assumenosideeffects`. Only `Log.e()` is preserved for crash diagnostics.

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
- Do not use `runBlocking` in new code — use `scope.launch {}` or `suspend fun` instead
- Do not use `toHttpUrlOrNull()!!` to build LRR API URLs — use `parseBaseUrl()` from `LRRApiUtils.kt`
- Do not use `notifyDataSetChanged()` on RecyclerView — use DiffUtil or specific `notifyItem*()` calls
- Do not introduce new visual themes or Material3 components — match existing `RoundSideRectDrawable` + theme attr style
- Do not add `x86_64` ABI filter to release builds — release is arm64-v8a only (debug includes x86_64 for emulator)
