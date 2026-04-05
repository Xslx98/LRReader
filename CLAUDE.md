# CLAUDE.md — LR Reader Codebase Guide

## Project Overview

**LR Reader** is an Android client for [LANraragi](https://github.com/Difegue/LANraragi), a self-hosted manga/archive management server. It is forked from [EhViewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ) and retains the EhViewer framework as its UI/reading foundation while replacing all E-Hentai API calls with LANraragi (LRR) REST API calls.

- **Application ID:** `com.lanraragi.reader`
- **Namespace:** `com.hippo.ehviewer` (legacy, retained from EhViewer)
- **Current Version:** 1.10.5 (versionCode 11005 — formula: `MAJOR*10000 + MINOR*100 + PATCH`)
- **License:** GPLv3

---

## Technology Stack

| Layer | Technology | Version |
|---|---|---|
| Languages | Java / Kotlin hybrid (42% Kotlin) | Kotlin 2.1.0 |
| Android SDK | compileSdk 35, minSdk 28 | Android 9+ |
| JDK | Java 21 | sourceCompatibility VERSION_21 |
| Build | Gradle + AGP 8.13.2 | `./gradlew` + Version Catalog (`libs.versions.toml`) |
| Network | OkHttp | 4.12.0 |
| API Serialization | kotlinx-serialization | 1.8.1 (all JSON, Gson removed) |
| Database | Room + KSP | 2.6.1, schema v11 (exported to `app/schemas/`) |
| Coroutines | kotlinx-coroutines | 1.10.2 |
| Lifecycle | AndroidX lifecycle-runtime-ktx | 2.8.7 |
| Image Decoding | Custom C/JNI (libjpeg-turbo, libpng, libwebp) | CMake |
| Security | EncryptedSharedPreferences | 1.1.0 |
| UI | Material Design + AndroidX | Material 1.13.0 |
| Static Analysis | Detekt | 1.23.7 (config: `config/detekt/detekt.yml`) |
| ABI | arm64-v8a, x86_64 | 64-bit only |

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
│   │   └── test/                      # Unit tests (32 files, LRR API + DAO + DiffUtil + Coroutine)
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
| `EhApplication.java` | App entry point; calls `ServiceRegistry.initialize()` |
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
| `client/lrr/LRRArchiveApi.kt` | Archive search/list/detail/upload/delete API |
| `client/lrr/LRRSearchApi.kt` | Search + random endpoint |
| `client/lrr/LRRCategoryApi.kt` | Category CRUD + archive association |
| `dao/AppDatabase.kt` | Room database schema (v11, schema exported) |
| `ui/MainActivity.java` | Main UI entry point + scene routing |
| `ui/GalleryActivity.java` | Reader/detail view |
| `ui/scene/GalleryListScene.java` | Gallery browse scene |

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
- Core layers (data, API, download, settings, modules, gallery providers) are **100% Kotlin**.
- UI layer (Scenes, Activities, Adapters) is partially migrated; mid-size Scenes are Kotlin, three large Scenes remain Java.
- `com.hippo.*` framework (230 files: GLView, Conaco, widgets) stays Java — stable legacy, rarely touched.

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
- DNS-over-HTTPS via `okhttp-dnsoverhttps`; HTTPS-first URL handling enforced

### Database (Room)

- All entities and DAOs in `dao/` package
- Use KSP (not KAPT) for annotation processing
- Schema version is v11; exported to `app/schemas/` — always provide a `Migration` when bumping
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

Settings are being migrated from `Settings.java` to typed objects in `settings/`:

- `AppearanceSettings`, `DownloadSettings`, `FavoritesSettings`, `NetworkSettings`, `ReadingSettings`, `SecuritySettings`
- New settings go into the appropriate typed object, not `Settings.java`
- API keys use `EncryptedSharedPreferences` via `LRRAuthManager` — never plaintext

### Package Organization

- LRR API code → `client/lrr/`
- LRR data classes → `client/lrr/data/`
- UI scenes → `ui/scene/`; fragments → `ui/fragment/`
- Business logic stays out of Activities/Fragments

---

## Testing

Unit tests live in `app/src/test/java/`. 32 test files covering:

- All LRR API classes (`LRRArchiveApiTest`, `LRRSearchApiTest`, `LRRCategoryApiTest`, etc.) using `MockWebServer`
- All LRR data classes (`LRRArchiveTest`, `LRRCategoryTest`, etc.)
- `RoomMigrationTest` — schema integrity verification
- `ServerProfileDaoTest` — DAO CRUD verification
- `GalleryInfoDiffTest` — DiffUtil identity/content equality contracts
- `ContentHelperDiffUtilTest` — DiffUtil dispatch operations (insert/remove/change)
- `CoroutineBridgeTest` — Java→coroutine bridge function contracts
- `EhDBMainThreadCheckTest` — main-thread detection + direct DAO operations
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
5. Detekt (continue-on-error)
6. Upload artifacts: test reports, lint reports, detekt reports, APK

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

5. **Room schema migrations:** Schema at v11, exported. Write an explicit `Migration` object for every schema change.

6. **DiffUtil in ContentLayout and DownloadsScene:** `ContentHelper.dispatchDiffUpdates()` for gallery list updates. `DownloadsScene` uses `DownloadInfoDiffCallback` with `gid`-based identity for `onUpdateAll()`/`onReload()`. Avoid `notifyDataSetChanged()` — use specific notifications or DiffUtil.

7. **EhDB dual API:** ~22 remaining `@JvmStatic` bridge methods use `blockingDb()` for Java callers (logs warning on main thread). Kotlin callers should use `suspend fun *Async()` versions directly. Dead methods and Kotlin-only bridges have been removed — do not add new `blockingDb` bridges.

8. **Download subsystem (Kotlin):** `DownloadManager.kt` (state management), `LRRDownloadWorker.kt` (background downloads with retry, format validation), `DownloadSpeedTracker.kt` (speed monitoring). Listener interfaces extracted to `DownloadInfoListener.java`/`DownloadListener.java`. Thread-safe via `CopyOnWriteArrayList` and `ConcurrentHashMap`. LRU cache (500 MB) for page images.

9. **Multi-server support:** `ServerProfile` entity + `LRRAuthManager` handle per-server credentials. Server selection affects all API calls. Credentials are encrypted.

10. **EhViewer stubs:** `EhGalleryProvider`, `FavoritesScene`, and `FavoriteListSortDialog` are intentional stubs (empty bodies) left for structural compatibility. Do not delete — but do not add logic to them either.

11. **Helper class extraction:** Large Scenes use extracted helpers to reduce line count: `GalleryUploadHelper` (upload/URL download from GalleryListScene), `GallerySearchHelper` (search suggestions/URL building), `DownloadImportHelper` (local archive import from DownloadsScene). Helpers communicate via `Callback` interfaces — follow this pattern for new extractions.

12. **Network tuning:** `NetworkModule.kt` configures `ConnectionPool(10, 5min)`, 200MB HTTP cache, thumbnail 24h cache, deferred `CookieManager.flush()`. Image client has 60s `callTimeout` for large files over slow WAN.

13. **GifHandler lifecycle:** `GifHandler` implements `Closeable` with native `destroy()` that calls `DGifCloseFile()` + `free()`. Always `close()` when done — native memory is NOT garbage collected.

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
