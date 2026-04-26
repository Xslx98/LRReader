# CLAUDE.md — LR Reader 协作守则

> **LR Reader** 是 [LANraragi](https://github.com/Difegue/LANraragi) 的 Android 客户端。Fork 自 EhViewer_CN_SXJ，保留 EhViewer 的 UI/阅读框架，业务后端全部替换为 LANraragi REST API。
>
> - **Application ID**：`com.lanraragi.reader`
> - **Namespace**：`com.hippo.ehviewer`（保留）
> - **当前版本**：v1.12.0 (versionCode 11200)
> - **License**：GPLv3
> - **versionCode 公式**：`MAJOR*10000 + MINOR*100 + PATCH`

---

## 文档索引

CLAUDE.md 只承载高频日常约定。详细信息分散在 `docs/`：

| 文档 | 何时阅读 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | 需要技术栈版本表、目录结构、关键文件列表、ServiceRegistry / Settings 模块拆分 |
| [docs/testing-and-ci.md](docs/testing-and-ci.md) | 跑测试、看 CI 流程、改签名配置、Native CMake、本地化、Room schema 演进 |
| [docs/adr-001-download-ssot.md](docs/adr-001-download-ssot.md) | 改 Download 模块（W35-3a/3b 已完成，W35-1a/3c 仍在路上） |
| [docs/audit-2026-04-15-v2.md](docs/audit-2026-04-15-v2.md) | 数据架构反模式 —— 顶部含 2026-04-26 状态更新；当前唯一活跃整改是 D1（`GalleryInfoEntity` 扁平化），合入 W35-1a/3c 执行 |
| [docs/EDGE_TO_EDGE.md](docs/EDGE_TO_EDGE.md) | 改 Activity 布局或状态栏 —— API 35+ 状态栏着色坑很深，必读 |
| `docs/archive/` | 归档目录：已完成的 plan / 旧 audit 快照 / 过时 onboard / 历史 ROADMAP。**不必要不查看**，主要为审计/历史回溯保留 |

> ⚠️ **`docs/` 永不入 git**。整个目录及所有子目录在 `.gitignore` 中（`/docs/`），仅作本地协作笔记，不提交、不推送、不发布到 GitHub。新增/修改 docs 文件不要 `git add docs/...`，也不要 `git add -f` 强制追加。

---

## 常用命令

```bash
./gradlew :app:assembleAppReleaseDebug      # Debug APK
./gradlew :app:assembleAppReleaseRelease    # 签名 Release APK
./gradlew app:testAppReleaseDebugUnitTest   # 单测
./gradlew app:lintAppReleaseDebug           # Lint
./gradlew detekt                            # 静态分析（CI 阻塞）
./gradlew clean
```

---

## 代码约定

### 语言

- **新代码必须 Kotlin**。`com.hippo.ehviewer` 业务代码已 100% Kotlin（零 Java 文件）；`com.hippo.*` 框架（GLView / Conaco / ContentLayout / 控件，~228 文件）保持 Java，稳定遗留，不动。
- 4 空格缩进，同行开括号；CamelCase 类名 / camelCase 变量与方法。
- 注释中文/英文皆可；Detekt 强制风格规则，提交前 `./gradlew detekt`。

### 异步与线程

- 网络与 DB 调用一律 **Kotlin 协程**：`suspend fun` + `withContext(Dispatchers.IO)`。
- Fragment 协程用 `viewLifecycleOwner.lifecycleScope`。
- Java 调 IO 工作：`CoroutineBridge.launchIO(lifecycleOwner, task)` 或 `IoThreadPoolExecutor`。
- `EhDB` 只暴露 `suspend fun xxxAsync()`；旧 `blockingDb` 桥与 `@JvmStatic` 包装已删除。
- `CoroutineModule` 提供 `applicationScope` / `ioScope`，含 `SupervisorJob` + `CoroutineExceptionHandler`。
- `LRRCoroutineHelper.runSuspend()` 带运行时主线程守卫（在 UI 线程调用会抛异常）。
- **新代码禁用 `runBlocking`**（唯一存活点：`LRRCoroutineHelper.runSuspend()`，`@WorkerThread` + 主线程守卫）。
- **禁止主线程 DB 调用** —— UI 层调 `EhDB.*()` 必须包在 `IoThreadPoolExecutor` 或协程作用域里。
- 无 `AsyncTask`；并行 IO 用 `IoThreadPoolExecutor`。

### 网络（OkHttp）

- 所有 LANraragi API 调用走 `client/api/` 包。
- `LRRAuthInterceptor` 注入 API key（按目标 host 校验，防 token 泄漏）。
- DNS-over-HTTPS 来自 `okhttp-dnsoverhttps`。
- 全局允许明文 HTTP（LAN IP 访问）。

### 数据库（Room）

- Entity / DAO 都在 `dao/` 包；用 KSP（**不**用 KAPT）。
- 当前 schema v21；升级流程见 [docs/testing-and-ci.md §6](docs/testing-and-ci.md#6-schema-演进)。
- **永不**使用 `fallbackToDestructiveMigration()`（生产代码）。
- `AppDatabase.kt` 是唯一 Room 数据库实例。
- UI 不直接调 `EhDB` domain 方法 —— 走对应 Repository（`ServiceRegistry.dataModule.{history|profile|quickSearch|favorites|downloadDb}Repository`），`EhDB` 上的 domain 方法已 `@Deprecated`。

### 序列化

- 所有 JSON（包括 LRR API 响应和新代码）用 `kotlinx-serialization` 的 `@Serializable` 数据类（在 `client/api/data/`）。
- **Gson 已从项目中移除，禁止重新引入。**

### 依赖管理

- 库版本统一在 `gradle/libs.versions.toml`；`build.gradle` 用 `libs.<alias>` 引用，**禁止硬编码版本号**。
- JitPack 依赖钉到 commit hash，更新需手动同步并在 catalog 注释中说明。

### 命名

- 用 `parseBaseUrl()`（来自 `LRRApiUtils.kt`）构建 LRR API URL，**不要** `toHttpUrlOrNull()!!`。
- 旧路径 `com.hippo.ehviewer.client.lrr` 已废弃 —— 用 `com.lanraragi.reader.client.api`。
- 旧名 `EhUrl` / `EhUrlOpener` 已重命名为 `LRRUrl` / `LRRUrlOpener`。

---

## What NOT to Do

### 语言 / 构建

- 不写新 Java —— 新代码一律 Kotlin。
- 不用 Gson —— 全部用 `kotlinx-serialization`。
- 不在 `build.gradle` 硬编码版本 —— 用 `libs.versions.toml`。
- Release 不加 `x86_64` ABI —— release 仅 arm64-v8a，debug 才包含 x86_64。
- 不提交 `local.properties` / 签名文件 / `google-services.json`。
- 不跟踪 `docs/` 目录 —— 整个目录 gitignored，仅本地协作笔记，禁止 `git add docs/...` 或 `-f` 强制追加。

### 协程 / 线程

- 不用 `AsyncTask` 或裸 `Thread` 做网络/DB 工作 —— 用协程或 `IoThreadPoolExecutor`。
- 新代码不用 `runBlocking` —— 用 `scope.launch {}` 或 `suspend fun`。
- 不在 `EhDB` 添加 `blockingDb()` 桥或 `@JvmStatic` 包装 —— 协程作用域中调 `suspend` 变体。
- DB 持久化的 `scope.launch { EhDB.*() }` 必须带 try-catch，禁止"fire-and-forget 不处理异常"。

### 数据库 / 持久化

- Room schema 升级不用 `fallbackToDestructiveMigration()`。
- API key / 任何 secret 不入源码、不入未加密 SharedPreferences。
- UI 层不直接调 `EhDB` domain 方法 —— 走对应 Repository（同上节）。

### 架构

- 不在 `EhApplication` 加单例 —— 用 `ServiceRegistry` 各 Module。
- 不在 `Settings.kt` 加字段访问器 —— 新设置进对应模块化 settings 对象。
- 不在 `ServiceRegistry.clearAllCaches()` 硬编码清缓存调用 —— 实现 `Cacheable` 并 `registerCacheable()`。
- UI 层只显示画廊数据时用 `GalleryInfoUi`，不要传 `GalleryInfo` / `GalleryInfoEntity`；只在持久化边界用 `GalleryInfoEntity`（其 typealias 为 `GalleryInfo`）。
- 不新建无 ViewModel 的 Scene —— 所有功能性 Scene 都有 ViewModel。
- 不重新引入 Helper Callback 接口 —— 业务逻辑放 ViewModel，Scene 观察 StateFlow / SharedFlow。
- 抽离的 helper 不要塞回 Scene 类 —— Scene 当 coordinator，helper 持有逻辑。

### API / 命名

- 构建 LRR API URL 不用 `toHttpUrlOrNull()!!` —— 用 `parseBaseUrl()`。
- 不从 `com.hippo.ehviewer.client.lrr` 导入 —— 用 `com.lanraragi.reader.client.api`。
- 不用 `EhUrl` / `EhUrlOpener` —— 已重命名为 `LRRUrl` / `LRRUrlOpener`。

### UI

- 不在 RecyclerView 用 `notifyDataSetChanged()` —— 用 DiffUtil 或具体的 `notifyItem*()`。
- 不引入新视觉主题或 Material3 控件 —— 沿用现有 `RoundSideRectDrawable` + theme attr 风格。

### Download 模块

- 不从 `download/` 包外导入 `DownloadRepository` / `DownloadScheduler` / `DownloadEventBus` —— 只用 `DownloadManager` Facade。
- Scene 不实现 `DownloadInfoListener` —— 监听器逻辑进 ViewModel，Scene 订阅 sealed `DownloadUiEvent` SharedFlow。
- 不把 `DownloadUiEvent` 拆回多个独立 SharedFlow —— 单 Flow 分派模式有意为之。
- 下载进度（`speed` / `finished` / `downloaded` / `total` / `remaining`）只在 `DownloadProgressTracker` 上 —— 用 `DownloadManager.progressFor(arcid)` 或订阅 `progressTracker.progressFlow`（详见 [docs/adr-001-download-ssot.md](docs/adr-001-download-ssot.md)）。`DownloadInfo` 已不再持有这些 @Ignore 字段（W35-3c 已移除）。
- 下载列表 UI 代码不读 `DownloadManager.getLabelDownloadInfoList` / `defaultDownloadInfoList` —— 用 `viewModel.downloadList`（带进度信息的 Flow）。这两个 accessor 仅留给非 UI 调用方。
- 不在 Scene 层加 `collectFlow(viewModel.downloadsFlow)` —— Scene 已订阅 `viewModel.downloadList`，它已经把 Room Flow 与 `DownloadProgressTracker.progressFlow` combine 过。
- 不重新加每 tick 由 `DownloadInfoListener.onUpdate` 驱动的 `notifyItemChanged` —— 进度更新走 combined Flow。`ItemUpdated` 事件处理器仅保留给"先于下次 Room Flow emission 的即时状态翻转"（如 WAIT→DOWNLOAD）。

---

## 反馈渠道

`/help` 查看 Claude Code 帮助；反馈 issue 提交到 https://github.com/anthropics/claude-code/issues。
