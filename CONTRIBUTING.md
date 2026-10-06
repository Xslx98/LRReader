# Contributing to LR Reader

Thank you for your interest in LR Reader. Bug reports, feature requests and pull
requests are welcome. Security problems go through private reporting instead —
see [SECURITY.md](SECURITY.md).

## Development environment

| Tool | Version |
|---|---|
| JDK | 21 (CI uses Temurin) |
| Android SDK | compileSdk / targetSdk 36, minSdk 28 |
| Android Gradle Plugin, Kotlin | see `gradle/libs.versions.toml` |

All library and plugin versions live in `gradle/libs.versions.toml`. Reference
them from `build.gradle` as `libs.<alias>`; never hard-code a version.

## Build and check

```bash
git clone https://github.com/Xslx98/LRReader.git
cd LRReader
./gradlew :app:assembleAppReleaseDebug          # debug APK (arm64 + x86_64)
./gradlew app:testAppReleaseDebugUnitTest       # unit tests (Robolectric)
./gradlew app:lintAppReleaseDebug               # Android lint
./gradlew detekt detektMain                     # static analysis
bash scripts/ci-check.sh                        # every CI gate, locally
```

The debug APK is written to `app/build/outputs/apk/appRelease/debug/`.

`scripts/ci-check.sh` runs the same gates as CI: assemble, unit tests, lint,
detekt with type resolution, the release DEX log check, Room schema drift,
baseline growth, the `runCatching` cap, the coverage floor and the JitPack AAR
checksums. Run it before opening a pull request (`ANDROID_HOME` must be set).

Release builds are signed with a key that is not part of the repository;
`:app:assembleAppReleaseRelease` needs your own keystore in `local.properties`
(`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`). You do not need it to contribute. Never commit
`local.properties`, keystores or any credential.

## Project layout

One Gradle module, `app/`:

- `com.lanraragi.reader.*` — application code, 100% Kotlin.
  - `client/api/` — every LANraragi REST call and its `@Serializable` DTOs.
  - `dao/` — Room entities, DAOs and migrations (`AppDatabase` is the only database).
  - `download/` — the download service; other packages use the `DownloadManager` facade only.
  - `ui/scene/` — screens (scenes) and their ViewModels.
- `com.lanraragi.framework.*` — the reader/rendering framework inherited from
  EhViewer, kept in Java. Leave it in Java; new code anywhere is Kotlin.
- `app/schemas/` — exported Room schemas; CI fails when they drift.

## Code conventions

### Language and style

- New code is Kotlin. 4-space indent, braces on the same line, `CamelCase`
  types and `camelCase` members.
- JSON uses `kotlinx-serialization` `@Serializable` classes. Gson is not allowed.
- Build LANraragi URLs with `parseBaseUrl()`, not `toHttpUrlOrNull()!!`.

### Coroutines and threads

- Network and database work is `suspend fun` + `withContext(Dispatchers.IO)`.
- No `runBlocking`, `AsyncTask` or bare `Thread` for I/O.
- No database calls on the main thread.
- A fire-and-forget `scope.launch { … }` that writes data must catch and log
  its errors.
- Wrap suspending calls with `suspendRunCatching`, not `runCatching` — the
  latter swallows cancellation. CI caps the number of plain `runCatching`.

### Architecture

- Every functional screen has a ViewModel; scenes observe `StateFlow` /
  `SharedFlow` and hold no business logic.
- UI code reaches persistence through the repositories on
  `ServiceRegistry.dataModule`, not through `LegacyDb` directly.
- New singletons go into a `ServiceRegistry` module, not `LRReaderApplication`.
- Caches implement `Cacheable` and register themselves.
- Lists use `DiffUtil` or `notifyItem*()`, never `notifyDataSetChanged()`.
- Keep the existing look (theme attributes, `RoundSideRectDrawable`); do not
  introduce Material 3 widgets or new themes.

### Database

- Every schema change gets a Room migration and an exported schema. Never use
  `fallbackToDestructiveMigration()`.
- API keys and other secrets never go into source code or unencrypted
  `SharedPreferences`.

### Logging

- Release builds strip `Log.v/d/i/w` with R8. A call whose arguments have side
  effects (a `Throwable` argument, a string template reading properties) is not
  stripped and fails the DEX log check. Use `Log.e` for real errors or guard the
  call with `if (BuildConfig.DEBUG)`.

### Strings

- Every new user-visible string needs all ten translations (`de es fr ja ko th
  zh-rCN zh-rHK zh-rTW` plus the default English); lint's `MissingTranslation`
  is an error. Spanish and French plurals need the `many` quantity.

### Static analysis

- detekt runs with `maxIssues: 0` and a baseline. Fix new findings; do not add
  entries to `config/detekt/baseline*.xml` or to the lint baseline.

## Tests

- A bug fix comes with a test that fails without the fix. A new feature comes
  with tests for its logic (ViewModels, repositories, parsers, workers).
- Prove a new guard is tested: break it on purpose (remove the check, flip the
  condition), run its test, see it fail, then restore the code.
- Unit tests run on the JVM with Robolectric. Avoid `Thread.sleep`: wait for the
  event, or use the coroutine test dispatcher.
- Line coverage of the `dao`, `download` and `gallery` packages must not drop
  below the floor in `scripts/ci/coverage-floor.txt`. Raise the floor when your
  change raises coverage.

## Commits and pull requests

- Branch from `main`. One logical change per commit: each commit builds, passes
  the tests and detekt, and can be reverted on its own. If the subject needs
  "and" or "also", split the commit.
- Commit messages: `<type>(<scope>): <summary>` in English, for example
  `fix(downloads): keep the failure reason after a restart`. Types: `feat`,
  `fix`, `perf`, `refactor`, `test`, `docs`, `build`, `ci`, `chore`.
- Do not rewrite commits that are already pushed to a shared branch.
- Do not skip hooks or signing (`--no-verify`, `--no-gpg-sign`).
- Fill in the pull request template; CI must be green before review.

## Reporting bugs and requesting features

Use the issue templates on
[GitHub Issues](https://github.com/Xslx98/LRReader/issues). For crashes,
**Settings → Advanced → Share diagnostics** creates a zip with redacted logs and
local crash reports — attach it instead of screenshots of logs.

## License

Contributions are released under the project's [GPLv3 license](LICENSE).
