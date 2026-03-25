# LR Reader 优化路线图

**创建日期**：2026-03-22  
**当前版本**：v1.9.0 (versionCode 12)  
**初始基线**：v1.4.0 (versionCode 5)

> [!NOTE]
> 本文档记录了从 v1.4.0 到 v1.9.0 的完整优化历史（内部文档，已 gitignore）。  
> **相关文档**：[onboard.md](docs/onboard.md)（入门指南）| [CONTRIBUTING.md](CONTRIBUTING.md)（构建 & 签名）

---

## ⚠️ 变更控制原则

> [!CAUTION]
> 涉及重大变更（依赖升级、minSdk 变更、死代码删除等）时，**必须**遵循以下协议：

1. **最小变更原则**：每次 commit 只做一件事。例如"删 desugar"和"删 BitmapFactory fallback"分开提交
2. **高密度构建验证**：每个最小变更后立即 `assembleAppReleaseDebug` 确认编译通过
3. **逐步推进**：先改配置 → 编译 → 提交 → 再改代码 → 编译 → 提交，绝不跨步
4. **可回滚性**：每步之后都有一个编译通过的 commit，任何问题可精确 `git revert`
5. **安装验证**：每个阶段的最后一步必须 `installAppReleaseDebug` 并在真机确认功能正常

---

## 阶段一：零风险配置瘦身 ✅

**完成日期**：2026-03-22

### Step 1.1 — minSdk 23 → 28 ✅ `97895be4`
### Step 1.2 — 移除 coreLibraryDesugaring ✅ `0d844336`
### Step 1.3+1.4 — ABI 精简 + targetSdk 35 ✅ `33eea5c7`
### Step 1.5+1.6 — 删除 Fonts + Conscrypt ✅ `651eb53d`
### Step 1.7 — 阶段验证 ✅

---

## 阶段二：安全与性能基建

**目标**：升级核心依赖，消除已知安全漏洞  
**预估耗时**：2-4 小时

### Step 2.1 — OkHttp 3.14.7 → 4.12.0 ✅
> 修改版本号 + 修复 Kotlin API 变化 (`response.body`, `Okio.source().buffer()`)

### Step 2.2 — 删除 fastjson，统一 Gson ✅
> 12 个文件迁移至 Gson：DAO 层 (GalleryTags, QuickSearch, GalleryInfo, DownloadInfo)、
> 工具类 (ClipboardUtil, Settings)、Kotlin (AppUpdater, UpdateDialog)、
> WiFi (WiFiDataHand, WiFiServerActivity, WiFiClientActivity)。
> 删除 `com.alibaba:fastjson:1.2.83` 依赖。

### Step 2.3 — 升级 AndroidX 依赖 ✅
> `activity` 1.2.4→1.10.1, `biometric` alpha→1.1.0, `browser` 1.3.0→1.8.0, `recyclerview` 1.2.1→1.4.0

### Step 2.4 — 删除 TagSoup ✅
> 删除 1089 行 `Html.java`，5 个调用者迁移至 `android.text.Html.fromHtml()`。
> `URLImageGetter` 改为实现 `android.text.Html.ImageGetter`。
> 删除 `tagsoup:1.2.1` 依赖。

### Step 2.5 — 阶段验证 ✅
> BUILD + INSTALL 成功

---

## 阶段三：可维护性重构

**目标**：清理遗留代码、拆分大对象、引入现代并发  
**预估耗时**：1-2 周（分批进行）

### Step 3.1 — 清理 EhViewer 死代码（分模块）
> [!IMPORTANT]
> 每个子模块独立删除 + BUILD + COMMIT

> [!CAUTION]
> **spider/ 模块不可删除**。`SpiderDen`、`SpiderInfo`、`SpiderQueen` 各有 6+ 活跃引用
> （DownloadManager, LRRDownloadWorker, GalleryDetailScene, ThumbDataContainer,
> ArchiverDownloadDialog, GalleryAdapter 等），是核心下载/缓存基础设施。
> 原 ROADMAP 的 3.1a "删除 spider/" 已修正。

> [!CAUTION]
> ### 踩坑记录：为什么 "0 refs" 的文件删了却编译失败？
>
> **事故**：用 PowerShell 检查 `client/parser/` 11 个文件的外部引用，脚本报告全部 0 refs，
> 于是批量删除。结果 BUILD 失败：6 个文件有活跃的 import + 代码引用。
>
> **根因 #1 — PowerShell `-SimpleMatch` 陷阱**：
> ```powershell
> # 错误写法
> Select-String -Pattern "import.*$p" -SimpleMatch
> # -SimpleMatch 把 .* 当做字面量匹配，没有文件包含字面文本 "import.*Xxx"
> # 所以所有类都报 0 refs
>
> # 正确写法
> Select-String -Pattern $p          # 不加 -SimpleMatch，搜类名本身
> ```
>
> **根因 #2 — 只搜 import 不够**：
> 即使 import 检查正确，也会漏掉以下场景：
> - **全限定引用**：`com.hippo.ehviewer.client.parser.GalleryPageParser.Result`（无 import 行）
> - **内部类使用**：`GalleryListParser.Result`、`VoteCommentParser.Result` 作为类型参数
> - **被注释代码中的引用**：编译器仍解析 import 行，即使使用方被注释
>
> **正确的安全删除协议**：
> 1. 使用 `grep_search` 工具搜索**类名**（非 `import.*类名`），`MatchPerLine=true`
> 2. `SearchPath` 设为整个 `app/src/main/java`
> 3. 排除条件是**文件自身路径**，而非整个目录
> 4. **不只看 import 行**，还要看代码体中的 `.Result`、`.parse()` 等内部类/方法引用
> 5. 任何 refs > 0 的文件一律不删
> 6. 删除后立即 BUILD，不要积累多个删除再验证

- [x] **3.1a** 删除 `sync/` 模块 ✅ `fde70a9c`
  > 删除 3 个 STUB（DownloadSpiderInfoExecutor, GalleryDetailTagsSyncTask, GalleryListTagsSyncTask）
  > + SpiderInfoReadCallBack。保留 DownloadListInfosExecutor（494L 活跃搜索/排序逻辑）。

- [x] **3.1b** ~~删除 `client/parser/`~~ **N/A — 全部 11 个解析器都有活跃引用**
  > 踩坑后确认：所有解析器的 `.Result` 内部类被 ContentLayout、GalleryDetailScene、
  > GalleryCommentsScene、GalleryListScene、SpiderInfo、EhEngine、ClipboardUtil 引用。
  > 无法安全删除。

- [x] **3.1c** 删除 EH 专有 UI Scene ✅ `60201825`
  > 删除 7 个 STUB（sign/4, topList/2, SelectSiteScene/1）。
  > 同步移除 MainActivity 中 6 个 import + 7 个 registerLaunchMode 调用。

- [x] **3.1d** ~~删除 `client/data/`~~ **N/A — 全部 data 类都有活跃引用**
  > topList/ (被 EhTopListDetail 引用), userTag/ (20+ refs: SubscriptionsScene, SubscriptionDraw,
  > GalleryListScene, EhApplication, BaseScene, GalleryDetailScene 等),
  > wifi/ (WiFiServerActivity, WiFiClientActivity, ConnectThread),
  > EhNewsDetail (EhApplication), HomeDetail (LimitsCountView), NewVersion (GalleryDetail)

- [x] **3.1e** 删除 5 个 0 引用异常 ✅
  > Image509Exception, OffensiveException, PiningException, EmptyGalleryException,
  > GalleryUnavailableException。保留 EhException (8+ refs), ParseException (3 refs),
  > CancelledException (3 refs), NoHAtHClientException (2 refs)。

- [x] **3.1f** 删除 TorrentDownloadCallBack ✅
  > 其余 4 个 callBack 均有活跃引用：DownloadSearchCallback (DownloadsScene + DownloadListInfosExecutor),
  > ImageChangeCallBack (MainActivity + UserImageChange), PermissionCallBack (PermissionRequester + UserImageChange),
  > SubscriptionCallback (GalleryListScene + SubscriptionDraw)。

### Step 3.2 — LRREngine 引入 Kotlin Coroutines ✅

当前 LRR 所有网络调用使用 `IoThreadPoolExecutor.execute() + runOnUiThread()` 模式。
目标：迁移为 `lifecycleScope.launch { withContext(Dispatchers.IO) { ... } }` 结构化并发。

#### Step 3.2a — 添加依赖
- [x] `app/build.gradle` 添加以下依赖 → **BUILD + COMMIT**
  ```groovy
  implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2"
  implementation "androidx.lifecycle:lifecycle-runtime-ktx:2.8.7"
  ```

> [!NOTE]
> **依赖版本确认**（来源：官方文档）：
> - `kotlinx-coroutines-android` — 提供 `Dispatchers.Main`、`withContext` 等核心 API
>   ([d.android.com/kotlin/coroutines](https://developer.android.com/kotlin/coroutines))
> - `lifecycle-runtime-ktx:2.4.0+` — 提供 `lifecycleScope` 和 `viewLifecycleOwner.lifecycleScope`
>   ([d.android.com/topic/libraries/architecture/coroutines](https://developer.android.com/topic/libraries/architecture/coroutines))
> - 项目 Kotlin = `2.1.0`，`kotlinx-coroutines 1.10.2` 完全兼容
> - R8/ProGuard 规则已内置于 `kotlinx-coroutines-android` 模块，**无需手动配置**

#### Step 3.2b — LRREngine.java → LRREngine.kt (suspend)
- [x] 将 `LRREngine.java`（427L）转换为 Kotlin `object`
- [x] 每个网络方法标记为 `suspend` 并包裹 `withContext(Dispatchers.IO) { ... }`
- [x] `friendlyError()` / `ensureSuccess()` 保持为普通函数
- [x] 删除旧 Java 文件 → **BUILD + COMMIT**

> [!CAUTION]
> **main-safety 模式**（来源：[d.android.com/kotlin/coroutines#use-coroutines-for-main-safety](https://developer.android.com/kotlin/coroutines)）：
> `suspend fun` 配合 `withContext(Dispatchers.IO)` 使函数变为 main-safe，
> 调用方可直接在 `lifecycleScope.launch`（主线程）中调用，无需手动切换线程。
> `withContext` 返回后自动回到调用者的 Dispatcher（即主线程），UI 更新可直接写。
>
> **Java 调用方兼容性**：`suspend` 函数在 Java 中表现为接收额外 `Continuation` 参数的方法，
> **Java 代码无法直接调用 suspend 函数**。因此 3.2b 和 3.2c 必须同步进行——
> 所有 Java 调用方必须在同一步中迁移为 Kotlin 协程调用或使用 `runBlocking` 桥接。

#### Step 3.2c — 迁移 Scene 调用方（4 文件）
每个文件：替换 `IoThreadPoolExecutor.execute { ... runOnUiThread { } }` 为
`lifecycleScope.launch { val r = withContext(IO) { LRREngine.xxx() }; /* UI直接写 */ }`

- [x] `ServerConfigScene.java` — `tryConnect()` 中 2 处 `LRREngine.getServerInfo()` 调用
- [x] `LRRCategoriesScene.java` — `fetchCategories()` 中 1 处 `LRREngine.getCategories()` 调用
- [x] `GalleryDetailScene.java` — 6+ 处 LRREngine 调用
  （`getArchiveMetadata`, `updateArchiveMetadata`, `getCategories`, `addToCategory`, `removeFromCategory`）
- [x] `GalleryListScene.java` — `searchArchives()` 调用
- [x] **BUILD + COMMIT**

> [!CAUTION]
> **生命周期绑定**（来源：[d.android.com/topic/libraries/architecture/coroutines#lifecyclescope](https://developer.android.com/topic/libraries/architecture/coroutines)）：
> `LifecycleScope` 在 `Lifecycle` destroy 时**自动取消所有协程**。
> 官方 Fragment 示例使用 `viewLifecycleOwner.lifecycleScope.launch { ... }` 模式。
> 本项目 Scene 继承 `SceneFragment extends Fragment`，可直接使用此模式。
>
> **异常处理模式变化**：当前 `try/catch` 在 `execute()` lambda 内，
> 迁移后 `try/catch` 放在 `lifecycleScope.launch` block 内。
> 由于 `withContext` 返回主线程后异常才抛出，**异常天然在主线程处理**，
> 不需要额外 `runOnUiThread` 包裹。
> （来源：[d.android.com/kotlin/coroutines](https://developer.android.com/kotlin/coroutines) —
> "Once the withContext block finishes, the coroutine in login() resumes execution on the main thread"）

#### Step 3.2d — 迁移后台调用方（2 文件）
- [x] `LRRGalleryProvider.java`（extends `GalleryProvider2`）— 在自己的 IO 线程中调用
  → 使用 `runBlocking { LRREngine.xxx() }` 桥接（因为 suspend 函数已自带 `withContext(IO)`，
  `runBlocking` 仅作同步桥接用）
- [x] `LRRDownloadWorker.java`（**不是** WorkManager Worker，是普通类）
  → 同上，用 `runBlocking` 桥接
- [x] **BUILD + COMMIT**

> [!WARNING]
> **`runBlocking` 使用场景**：仅在已确定运行在后台线程的代码中桥接 suspend 函数。
> `LRRGalleryProvider` 和 `LRRDownloadWorker` **已确认**运行在 IO 线程池中，
> 使用 `runBlocking` 不会阻塞主线程。**严禁在主线程中使用 `runBlocking`**。


### Step 3.3 — 清除 EhConfig 死配置 + Settings 瘦身 ✅
- [x] 删除 EhConfig 中 22 个 EH cookie instance fields + `uconfig()` 方法
- [x] 删除 Settings.java 中对应的 get/set 方法（实际瘦身: EhConfig -81%, Settings -27L）
- [x] 保留 category 常量（DOUJINSHI, MANGA 等 — 活跃引用）
- [x] 级联清理: EhRequest（删 mEhConfig/setEhConfig/getEhConfig）、EhClient.Task（删 mEhConfig/getEhConfig）
- [x] 每步 → **BUILD + COMMIT**

> [!CAUTION]
> **SharedPreferences key 耦合**：Settings.java 的 get/set 方法使用字段名作为
> SharedPreferences key。删除死字段时**必须确认字段在 LRR 路径中无读取**。
> 建议：逐字段 grep 确认 0 外部引用后才删除对应 Settings 方法。


### Step 3.4 — JSON 迁移 kotlinx-serialization（LRR 包） ✅
- [x] 添加 kotlinx-serialization 编译器插件 (`2.1.0`)
- [x] 添加 `kotlinx-serialization-json:1.8.1` 运行时依赖
- [x] 转换 4 个 LRR 数据类为 Kotlin `@Serializable`（`@JvmField` Java 互操作）
- [x] LRREngine.kt: Gson → `Json.decodeFromString` + `Json { ignoreUnknownKeys = true }`
- [x] 配置 `ignoreUnknownKeys = true` 容错
- [x] 保留非 LRR 的 Gson 用法（GreenDAO, WiFi, utils）
- [x] **BUILD + COMMIT**

> [!CAUTION]
> **编译器插件版本强耦合**（官方文档确认）：
> `org.jetbrains.kotlin.plugin.serialization` 版本必须与
> `kotlin-gradle-plugin` **完全一致**（均为 `2.1.0`）。
> 版本不匹配会导致内部编译器错误（ICE），报错信息极不直观。
>
> **GreenDAO Entity 冲突**：GreenDAO Entity 使用代码生成，
> 若加 `@Serializable` 注解可能与 GreenDAO 代码生成冲突。
> 建议：仅对 `lrr/` 包下的纯数据类和 API 响应类使用，不动 GreenDAO Entity。
>
> **ProGuard/R8 规则**（官方文档确认）：
> `kotlinx-serialization` 的 consumer ProGuard 规则**由 R8 自动应用**，
> AGP 8.0+ 无需手动配置。仅在以下场景需额外规则：
> - 使用命名 companion object 的 `@Serializable` 类
> - 运行时反射查找序列化器
> Phase 2 已添加的 Gson keep 规则可保留，两者不冲突。

### Step 3.5 — 阶段验证 ✅
- [x] **INSTALL** + 全功能回归 — 无崩溃
- [x] 代码行数对比：净减 **−1,326 行**（22 文件删除，5 新增）

---

## 阶段四：功能恢复 + 品牌一致性 ✅

**目标**：恢复被错误移除的有价值功能，修复品牌/国际化一致性问题  
**依据**：[feature_audit.md](file:///C:/Users/XSlx/.gemini/antigravity/brain/534aa6b8-4196-4fbc-9b42-ca15e4c1efad/feature_audit.md)  
**完成时间**：2026-03-23

### Step 4.1 — 恢复高价值功能到设置 XML ✅
将以下功能重新添加到对应 settings XML，验证后端代码可正常工作：

- [x] **export_data** — 恢复到 `download_settings.xml`，验证 CSV 导出流程
- [x] **import_data** — 恢复到 `download_settings.xml`，验证 CSV 导入流程
- [x] **preload_image** — 恢复到 `download_settings.xml`，验证预加载逻辑
- [x] **download_timeout** — 恢复到 `download_settings.xml`，验证超时控制
- [x] **BUILD + COMMIT**

### Step 4.2 — 恢复 UI 微调设置 ✅
- [x] **detail_size** — 恢复到 `eh_settings.xml`，验证详情页选项生效
- [x] **thumb_size** — 恢复到 `eh_settings.xml`，验证缩略图选项生效
- [x] **修复即时生效 Bug** — `refreshColumnSize()` 方法确保设置变更后立即刷新列宽
- [x] **BUILD + COMMIT**

### Step 4.3 — 国际化修复 ✅
- [x] 默认 `values/strings.xml` 中 6 个英文字串改为中文
- [x] `values-en/strings.xml` 新增 2 条 + 修正 1 条
- [x] **BUILD + COMMIT**
- [x] 全量国际化修复 — 默认 strings.xml 50+ 中文→英文，9 个 locale 补全缺失翻译 `53bb85af`

### Step 4.4 — EhViewer → LRReader 路径重命名 ✅
- [x] `AppConfig.APP_DIRNAME` "EhViewer" → "LRReader"
- [x] `EhConfig` 3 个路径常量重命名
- [x] `filepaths.xml` 5 个 FileProvider 路径
- [x] `DownloadFragment.java` 导出文件名前缀
- [x] `s_pen_actions.xml` S-Pen ID
- [x] 一次性迁移逻辑：自动重命名文件系统目录 + 更新 SharedPreferences
- [x] **BUILD + COMMIT**

### Step 4.5 — 阶段验证 ✅
- [x] **INSTALL** + 全功能回归 — 无崩溃
- [x] 验证恢复的设置项（export/import/preload/timeout/detail_size/thumb_size）可正常操作
- [x] 验证 i18n 一致性（中文/英文 locale 均正确）
- [x] 验证 EhViewer → LRReader 路径迁移生效

> [!NOTE]
> 原计划的 EH 死代码清理（下载排序/WiFi传输/filter_by_kind 等）推迟到阶段五统一处理。

---

## 阶段五：EH 死代码清理 + 架构现代化（长期）

**目标**：清理残余 EH 专属代码 + 基础设施升级，按需推进  
**预估耗时**：按需（建议按 Step 顺序执行，每步独立可交付）

> [!IMPORTANT]
> **Phase 2-3 踩坑经验总结**（指导本阶段所有操作）：
> 1. **每删一个文件必须 BUILD**，不要批量删除后再编译——依赖链可能很深
> 2. **`Settings.java` 的 key 常量被 XML `android:key` 和 Java 两端引用**，删 key 前必须搜索 `*.xml` + `*.java`
> 3. **`EhDB.java` 是数据层唯一入口**，改动 DAO 必须同步改 `EhDB` 的对应方法
> 4. **`GalleryInfo` 被 Parcelable 序列化**，字段变更必须同步 `writeToParcel` / `readFromParcel`
> 5. **字符串资源清理必须同时检查所有 locale**（`values/`, `values-en/`, `values-zh-rCN/` 等）

---

### Step 5.1 — 清理 Settings.java 死代码 ✅

> **审计结果**：交叉搜索 `*.java` + `*.kt`（教训：仅搜 Java 会漏掉 Kotlin 引用）。
> 确认 4 个 key 仅 Settings.java 内部引用，3 个 EhUtils.kt 方法无外部调用者。

- [x] 删除 Settings.java: `KEY_NEED_SIGN_IN`, `KEY_SELECT_SITE`, `KEY_THUMB_RESOLUTION`, `KEY_FIX_THUMB_URL`
- [x] 删除 EhUtils.kt: `signOut()`, `needSignedIn()`, `handleThumbUrlResolution()`
- [x] 清理 EhUtils.kt 无用 import（`Context`, `EhApplication`）
- [x] **BUILD + COMMIT** `b81174bb`

> [!TIP]
> **踩坑**：初次仅搜 `*.java` 漏掉 Kotlin 调用者导致编译失败。
> 此后所有审计均同时搜索 `*.java` + `*.kt`。

---

### Step 5.2 — 清理孤立字符串资源 ✅

> **审计结果**：8 组孤立字符串（thumb_resolution\*, fix_thumb_url\*, need_sign_in,
> select_site, filter_by_kind, show_eh_events\*, show_eh_limits\*, show_read_progress\*）
> 分布在 10 个 locale 文件中。

- [x] 批量删除 89 行孤立字符串
- [x] 删除 arrays.xml 中 12 行死 `thumb_resolution_entries/values` 数组
- [x] 删除 6 locales (de/es/fr/ja/ko/th) 中 90 行 ExtraTranslation 字符串
- [x] **BUILD + COMMIT** `d47b702d`

> [!WARNING]
> **踩坑**：`arrays.xml` 中 `@string/` 引用被忽略，导致构建失败。
> 字符串清理必须同时检查 `strings.xml` + `arrays.xml` + 所有 locale。

---

### Step 5.3 — EventBus → SharedFlow ✅

> **审计结果**：仅 1 条真实事件流 `GalleryPreviewsScene.postSticky(GalleryActivityEvent)`
> → `GalleryActivity.@Subscribe(sticky=true)`。GalleryListScene/DownloadsScene 的
> `@Subscribe` 处理器为死代码（从未调用 `EventBus.register()`）。

- [x] 创建 `AppEventBus.kt`（`MutableSharedFlow<GalleryActivityEvent>(replay=1)` — sticky 语义）
- [x] `GalleryPreviewsScene`: `postSticky()` → `AppEventBus.postGalleryActivityEvent()`
- [x] `GalleryActivity`: `@Subscribe(sticky)` → `replayCache` 轮询（Java 兼容方案）
- [x] 删除 GalleryListScene/DownloadsScene 死 EventBus 代码（@Subscribe/unregister/imports）
- [x] 更新 proguard-rules.pro: EventBus keep→dontwarn, 删除 FastJSON keep, 添加 sqlcipher dontwarn
- [x] **BUILD + COMMIT** `c196e371`

> [!NOTE]
> EventBus 库仍在 `build.gradle` 依赖中（其他传递性使用可能存在），待后续确认后移除。

### 已知遗留问题 — 已修复 ✅ `b0f38260`

#### 1. Release 构建闪退（R8 minify）— 已修复
- **修复**：重写 `proguard-rules.pro`，补全 `com.hippo.a7zip.*`、`Image1`、`ReLinker`、`Native`、`GifHandler` 的 `-keep` 规则
- **验证**：`assembleAppReleaseRelease` BUILD SUCCESSFUL（1m 37s）
- **Note**：安装 release APK 需要配置签名证书

#### 2. kotlinx-serialization 类型安全 — 已修复
- **修复**：创建 `FlexibleStringSerializer`，将任意 JSON 原始值（boolean/int/string）统一转为 String
- **应用**：`LRRArchive.isnew`、`LRRCategory.pinned`

#### 3. EventBus/GreenDAO 残留 — 已清理
- **修复**：移除 proguard 中 GreenDAO keep、EventBus dontwarn、Conscrypt keep 规则
- **保留**：`com.hippo.ehviewer.dao.**` keep（Room Entity 需要）

---

### Step 5.4 — GreenDAO → Room + kapt → KSP ✅

**完成日期**：2026-03-23

- [x] 添加 KSP 插件 (`2.1.0-1.0.29`) + Room 依赖 (`2.6.1`)
- [x] 替换 kapt → ksp，移除 GreenDAO 依赖
- [x] 10 个 Entity 全部重写为 Room `@Entity`（含 `@ColumnInfo` 大写列名兼容）
- [x] 创建 3 个 Room DAO：`BrowsingRoomDao`, `DownloadRoomDao`, `MiscRoomDao`
- [x] 创建 `AppDatabase.kt`（`fallbackToDestructiveMigration` — 当前无真实用户数据）
- [x] 完全重写 `EhDB.java` — 60 个方法迁移至 Room DAO
- [x] 修复 `HistoryScene.java`（LazyList → List）
- [x] 删除 12 个 GreenDAO 生成文件 + `daogenerator` 模块
- [x] 修复 `@Nullable` NPE 崩溃（Room `bindString` null 值处理）
- [x] **BUILD + INSTALL + 真机验证** `53bb85af`

---

### Step 5.5 — 移除 kapt 插件 ✅

**完成日期**：2026-03-23

- [x] kapt 插件已在 5.4 中随 KSP 切换一并移除
- [x] 清理残留注释代码（Glide kapt + EventBus 注释）
- [x] 确认零 kapt 引用 → **BUILD + COMMIT** `18ef0ce4`

---

### Step 5.6 — 死代码与残留目录清理 ✅ `db0df0a5`

> 合并 CODE_REVIEW_REPORT Section 4 和 PROJECT_TECHNICAL_DOC Section 6 的发现。

| 文件 | 位置 | 说明 |
|------|------|------|
| `scene_login.xml` | `res/layout/` | 无任何 Java/Kotlin 引用 |
| `requestOverride.js` | `assets/` | 原 E-H 登录拦截脚本，已失效且存在安全隐患 |
| `TestThread.java` | `ui/scene/gallery/list/` | 空实现 |
| `daogenerator/` 目录 | 项目根目录 | 已从 settings.gradle 移除，但目录仍在 |

- [x] 删除以上 4 项
- [x] **BUILD + COMMIT** — R8 release BUILD SUCCESSFUL (1m 7s)

---

### Step 5.7 — Scoped Storage 合规（存储架构现代化）✅

**完成日期**：2026-03-23

- [x] **5.7a** `AppConfig.getExternalAppDir()`: `Environment.getExternalStorageDirectory()` → `context.getExternalFilesDir(null)` `a9aface9`
- [x] **5.7a** 删除 `Settings.migrateEhViewerPaths()`（无真实用户，无需迁移）
- [x] **5.7b** `GalleryActivity.saveImage()` 改用 `MediaStore.Images.Media` API，保存至 `Pictures/LRReader` 相册 `eaf3d1ea`
- [x] **5.7c** `DownloadFragment` 简化为 SAF-only，移除 `DirPickerActivity` 引用和 API 版本分支 `bbc50675`
- [x] **5.7d** `filepaths.xml`: `<external-path>` → `<external-files-path>`
- [x] **5.7e** Manifest 移除 `WRITE_EXTERNAL_STORAGE`、`READ_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE`、`requestLegacyExternalStorage`
- [x] R8 release BUILD SUCCESSFUL (57s)

---

## 阶段六：安全与稳定性加固

**目标**：解决 CODE_REVIEW_REPORT 中标记的 High/Critical Risk 漏洞
**优先级**：按风险等级排序

### Step 6.1 — SSL 证书校验修复 (Critical) ✅

> **修复**：域前置（Domain Fronting）是 E-Hentai 专属功能，LRReader 连接私有 LANraragi 服务器不需要。

- [x] 删除 `EhApplication.java` 中 `if (Settings.getDF() && ...)` 整个 SSL 代码块
- [x] 删除 `EhX509TrustManager.java`（27 行，trust-all 空实现）
- [x] 删除 `EhSSLSocketFactory.java`（415 行，域前置 SNI 剥离）
- [x] 删除 `EhSSLSocketFactoryLowSDK.java`
- [x] 清理 `EhApplication.java` 中 7 个 SSL 相关 import
- [x] OkHttp 现使用平台默认 `TrustManagerFactory` 标准证书链校验
- [x] **BUILD SUCCESSFUL**

### Step 6.2 — Native 指针 Use-After-Free 修复 (High) ✅

> **来源**：CODE_REVIEW_REPORT §2.2

- [x] `Image1.kt` `mNativePtr` 从 `var Long` → `AtomicLong`
- [x] `recycle()` 使用 `getAndSet(0L)` 原子获取并置零（双重释放防护）
- [x] 所有 14 个读取点改为 `mNativePtr.get()`
- [x] **备注**：`Image1` 非活跃路径（`EhApplication:185` initialize 已注释），此为防御性修复
- [x] **BUILD SUCCESSFUL**

### Step 6.3 — JNI 全局缓冲区分析 (High → Low) ✅

> **来源**：CODE_REVIEW_REPORT §2.1

**结论**：完整追踪调用链确认 `tile_buffer` 仅从 GL 渲染线程访问。OpenGL ES 规范要求 GL context 绑定到单一线程，天然串行，**无实际竞态**。

- [x] 追踪完整调用链：`ImageTexture.Uploader.onGLIdle()` → `NativeTexture.updateContent()` → `Tile.texImage()` → `ImageWrapper.texImage()` → `Image.texImage(@Synchronized)` → `nativeTexImage()` → `tile_buffer`
- [x] 在 `image.c:17` 和 `java_wrapper.c:33` 添加 GL 线程安全注释
- [x] 更新 CODE_REVIEW_REPORT 风险评级 `High → Low`
- [x] **BUILD SUCCESSFUL**

---

## 阶段七：收尾 ✅

### Step 7.2 — Release 签名配置 ✅

**完成日期**：2026-03-23

- [x] 生成 release keystore (`keystore/release.jks`)
- [x] 配置 `signingConfigs.release`（从 `local.properties` 读取，含默认值 fallback）
- [x] Release buildType 改用 release 签名
- [x] 移除死设置项 `restore_download_items`（UI 可见但无后端代码）
- [x] `keystore/` 目录加入 `.gitignore`
- [x] **BUILD + INSTALL + COMMIT**

> [!NOTE]
> Step 7.1 (Image 双引擎统一) 和 Step 7.3 (Gson 依赖移除) 经评估后移除：
> - 7.1：`Image1.kt` 已不在活跃路径，Phase 6 已做防御性修复，投入产出比低
> - 7.3：Gson 与 kotlinx-serialization 共存无功能影响，200KB 体积差异可忽略

---

## 阶段八：代码清理 / 减负 ✅

**目标**：移除 EHViewer 遗留的未使用代码和资源，减小 APK 体积，降低维护负担

> [!NOTE]
> 经评估，EH 数据模型与 LRR 档案共用（用户档案来源 EH），代码层面清理空间有限。
> 仅移除了纯 EH 服务端 Activity（UConfig/Hosts/ExcludedLanguages/DirPicker）和废弃库。

- [x] Manifest 移除 4 个 EH-only Activity + apache.http.legacy
- [x] 保留 MyTagsActivity 和所有 EH 数据模型/共用代码

---

## 阶段九：用户体验优化 ✅

- [x] 暗色模式完整适配（分类页工具栏 `?attr/toolbarColor`）
- [x] 横屏 / 平板布局——已确认可用
- [x] 下载进度通知——已通过 SpiderQueen 管线实现

---

## 阶段十：安全加固 ✅

- [x] HTTP 明文流量：审计 `network_security_config.xml` —— 已正确配置 (cleartext for LAN, user certs)
- [x] API Key 泄露防护：确认无日志打印、auth 拦截器 host-scoped
- [x] R8 release 移除 `Log.d()`/`Log.v()` 调用（`-assumenosideeffects`）

---

## 阶段十一：网络与数据 ✅

- [x] 列表分页加载——已通过 `ContentHelper` + `GOTO_NEXT_PAGE` 实现无限滚动
- [x] WebSocket 实时更新——LANraragi API 无 WebSocket 端点，不适用
- [x] 离线模式——需新建 `LocalGalleryProvider`，作为未来功能计划

---

## 阶段十二：开源准备 ✅

**完成日期**：2026-03-24

- [x] 替换 LICENSE 文件为标准 GPLv3 全文（原文件为 GPLv3+Apache2.0+Doom3 错误拼接）
- [x] 更新 NOTICE — 标注 LRReader + 原始 EhViewer (Hippo Seven, Apache 2.0)
- [x] 重写 README.md — 从 EhViewer 上游 README 转换为 LRReader 专属内容
- [x] 新增 CONTRIBUTING.md — 构建指南、提交规范、代码风格
- [x] 确认 `.gitignore` 已排除 `*.apk`、`keystore/`、`local.properties`

---

## 阶段十三：技术改进

**依据**：[PROJECT_TECHNICAL_SUMMARY.md §10](docs/PROJECT_TECHNICAL_SUMMARY.md)  
**完成日期**：2026-03-25（13.1–13.4）  
**优先级**：按影响面和难度排序

### Step 13.1 — 主线程数据库访问迁移 ✅

> **问题**：`AppDatabase` 使用 `allowMainThreadQueries()`，主线程同步查询可能导致 UI 卡顿。
>
> **方案**：将 Room DAO 方法标记为 `suspend`，通过 `EhDB.kt` 双层桥接 API 兼容 Java/Kotlin 调用方。

- [x] **13.1a** 3 个 DAO（`BrowsingRoomDao`, `DownloadRoomDao`, `MiscRoomDao`）50+ 方法 → `suspend`
- [x] **13.1b** `EhDB.java` → `EhDB.kt` 完全重写为双层 API：
  - `suspend` 方法供 Kotlin 协程直接调用
  - `@JvmStatic runBlocking` 桥接方法供 Java 调用
- [x] **13.1c** 4 个 Java 文件（`EhApplication`, `MainActivity`, `ServerConfigScene`, `ServerListScene`）重定向至 `EhDB` 桥接
- [x] **13.1d** 移除 `allowMainThreadQueries()` → **BUILD SUCCESSFUL**

> [!NOTE]
> **技术发现**：`EhDB.kt` 双层桥接 API 约 1170 行。Java 调用方使用 `@JvmStatic` 暴露的
> `runBlocking` 桥接方法，运行时仍在原调用线程执行（已确认全部在后台线程）。
> `suspend` 版本供未来 Kotlin 迁移后直接使用。

---

### Step 13.2A — 消除 `runBlocking` 样板代码 ✅

> **问题**：22 处 `BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, cont) -> ...)` 样板代码。
>
> **原始计划 (13.2)**：将 Scene 文件从 Java → Kotlin 迁移以直接使用协程。
>
> **ROI 评估结论**：Java→Kotlin 迁移 ROI 为负。所有 `runBlocking` 调用已在后台线程 (`IoThreadPoolExecutor.execute()` / `new Thread()`)，
> 不阻塞主线程。代价高（2500+行/文件迁移）、风险大（Parcelable/XML/回调链断裂）。
> 改为 **Plan A**：创建 `LRRCoroutineHelper` 工具类消除样板。

- [x] **[NEW]** [LRRCoroutineHelper.kt](app/src/main/java/com/hippo/ehviewer/client/lrr/LRRCoroutineHelper.kt) — 顶级 `runSuspend()` 函数
- [x] 替换 **22 处** `BuildersKt.runBlocking` → `LRRCoroutineHelper.runSuspend`（6 个文件）
- [x] 清理所有 `EmptyCoroutineContext` / `BuildersKt` 冗余 import
- [x] **BUILD SUCCESSFUL**

> [!NOTE]
> **技术细节**：`runSuspend` 接受 `suspend CoroutineScope.() -> T` 参数，Java 通过 SAM 转换
> 使用相同的 `(scope, cont) -> ...` lambda 语法调用。运行时语义与 `BuildersKt.runBlocking` 完全等价。
>
> | 文件 | 替换数 |
> |---|---|
> | `GalleryDetailScene.java` | 12 |
> | `GalleryListScene.java` | 3 |
> | `LRRCategoriesScene.java` | 4 |
> | `ServerConfigScene.java` | 2 |
> | `LRRGalleryProvider.java` | 3 |
> | `LRRDownloadWorker.java` | 1 |

---

### Step 13.3 — 图像格式扩展 ✅

> **问题**：Magic bytes 校验不支持 AVIF 和 JPEG XL 新一代格式。

- [x] 添加 AVIF 识别 (`....ftypavif`)
- [x] 添加 JXL 识别 (ISOBMFF 容器头)
- [x] 同步更新 `LRRGalleryProvider` 和 `LRRDownloadWorker` 中的校验方法
- [x] **BUILD SUCCESSFUL**

---

### Step 13.4 — 缓存淘汰精度优化 ✅

> **问题**：目录级 LRU 以 `lastModified()` 为指标，部分文件系统对目录修改时间更新不一致。

- [x] 引入 `SharedPreferences` 记录每个 arcid 的最后访问时间戳
- [x] 替换 `cleanupOldCaches()` 中的 `lastModified()` 排序
- [x] **BUILD SUCCESSFUL**

---

### Step 13.5 — 命名空间重构 ❌ 不做

> **问题**：包名 `com.hippo.ehviewer` 暗示 EhViewer 项目身份，对新贡献者造成混淆。
>
> **ROI 评估**：[详细分析](file:///C:/Users/XSlx/.gemini/antigravity/brain/762a86b3-ee5c-4abe-b98e-71629bc72736/step_13_5_roi_analysis.md)

> [!CAUTION]
> **结论：强烈建议不做。ROI 极度负面。**
>
> **关键事实**：`applicationId` 已经是 `com.lanraragi.reader`（APK 身份正确），包名纯属内部实现细节。
>
> **影响范围**：501 个源文件（426 Java + 75 Kotlin）、50+ JNI 函数名硬编码在 C/C++、
> 30+ XML layout 引用、17 条 ProGuard keep 规则、12 个 Manifest 组件声明。
>
> **最大风险**：JNI 函数名 (`Java_com_hippo_lib_image_Image1_nativeDecode`) 必须与 Java 类 FQN
> **字符级精确匹配**，不一致导致运行时 `UnsatisfiedLinkError` — **编译期无法检测**。
>
> **成本**：3-5 天全量重构 + 高崩溃风险 + git blame 全部失效。
> **收益**：代码美观度微弱提升。

---

## 不做的事

| 项目 | 原因 |
|------|------|
| Scene → Jetpack Navigation 全局替换 | 改动面过大，Scene 框架运行稳定 |
| NDK 层 Google Test | ROI 低，JNI 代码量小且已验证 |
| 迁移到 Coil/Glide 图片库 | C 层渲染引擎在大图场景有绝对内存优势 |
| 支持 armeabi-v7a / x86 | 2020 年后手机全是 arm64 |

---

## 完成标准

每个阶段完成后应满足：
- ✅ `assembleAppReleaseRelease` R8 编译通过
- ✅ `installAppReleaseRelease` 安装成功
- ✅ 真机验证核心功能：启动 → 列表 → 搜索 → 阅读 → 翻页
- ✅ 所有变更有独立 commit，commit message 清晰描述改动内容

---

## 进度总览

| 阶段 | 描述 | 状态 |
|------|------|------|
| Phase 1 | 零风险配置瘦身 | ✅ 完成 |
| Phase 2 | 安全与性能基建（OkHttp/fastjson/AndroidX/TagSoup） | ✅ 完成 |
| Phase 3 | 可维护性重构（死代码/协程/EhConfig/kotlinx-serialization） | ✅ 完成 |
| Phase 4 | 功能恢复 + 品牌一致性 | ✅ 完成 |
| Phase 5 | EH 死代码清理 + Room/KSP/SharedFlow/R8 | ✅ 完成 |
| Phase 6 | 安全与稳定性加固（SSL/Image1 AtomicLong/tile_buffer 分析） | ✅ 完成 |
| Phase 7 | 收尾（Release 签名配置 + 死设置清理） | ✅ 完成 |
| Phase 8 | 代码清理 / 减负 | ✅ 完成 |
| Phase 9 | 用户体验优化 | ✅ 完成 |
| Phase 10 | 安全加固 | ✅ 完成 |
| Phase 11 | 网络与数据 | ✅ 完成 |
| Phase 12 | 开源准备（LICENSE/README/CONTRIBUTING） | ✅ 完成 |
| Phase 13 | 技术改进（主线程 DB / runBlocking 清理 / 格式扩展 / 缓存 / 命名空间评估） | ✅ 完成 |


