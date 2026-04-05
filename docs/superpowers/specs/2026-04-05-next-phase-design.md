# Next Phase Design: Room Flow + LRR API + Paging + Testing

## #4: Room + Flow 观察模式

### 现状
下载列表通过 `DownloadInfoListener` 回调链（9 个方法）通知 UI。`DownloadManager` 手动维护 `CopyOnWriteArrayList` + `NotifyTask` → `SimpleHandler.post()` → 各 Scene 的 listener 方法。这套机制有 ~300 行代码，且 UI 更新容易遗漏。

### 目标
用 Room DAO 返回 `Flow<List<DownloadInfo>>` 替代手动回调。UI 层通过 `lifecycleScope.launch { flow.collect {} }` 自动观察数据变化。

### 设计

**Phase 1: DAO 层添加 Flow 返回类型**
```kotlin
// DownloadRoomDao.kt — 新增
@Query("SELECT * FROM DOWNLOADS ORDER BY TIME DESC")
fun observeAllDownloads(): Flow<List<DownloadInfo>>

@Query("SELECT * FROM DOWNLOADS WHERE SERVER_PROFILE_ID = :profileId ORDER BY TIME DESC")
fun observeDownloadsByServer(profileId: Long): Flow<List<DownloadInfo>>
```

**Phase 2: EhDB 暴露 Flow API**
```kotlin
// EhDB.kt — 新增
fun observeDownloads(): Flow<List<DownloadInfo>> {
    val profileId = LRRAuthManager.getActiveProfileId()
    return if (profileId > 0)
        sDatabase.downloadDao().observeDownloadsByServer(profileId)
    else
        sDatabase.downloadDao().observeAllDownloads()
}
```

**Phase 3: DownloadsScene 订阅 Flow**
```kotlin
// 在 onViewCreated 中
viewLifecycleOwner.lifecycleScope.launch {
    EhDB.observeDownloads().collect { downloads ->
        mList = downloads
        mAdapter?.notifyDataSetChanged() // 或 DiffUtil
    }
}
```

**Phase 4: 逐步移除 DownloadInfoListener 回调**
- `onReload()` → Flow 自动触发
- `onUpdateAll()` → Flow 自动触发
- `onAdd()`/`onRemove()` → 仍需保留用于动画（DiffUtil 可计算）
- `onUpdate()` → 下载进度更新频率太高（每秒多次），不适合 Flow 触发数据库写入。保留现有的内存回调用于实时进度，Flow 只用于列表结构变化。

### 注意事项
- 下载进度（speed, downloaded, total）是 `@Ignore` 字段，不持久化到 Room → Flow 不会感知这些变化
- 因此 Flow **不能完全替代** 回调——它只处理列表结构变化（增/删/状态变更）
- 进度更新仍需现有 `NotifyTask` 机制或改为 `SharedFlow`

### 推荐实施顺序
1. 添加 DAO Flow 方法（无破坏性）
2. DownloadsScene 中并行订阅 Flow（保留旧回调作为 fallback）
3. 验证 Flow 工作后，逐步删除冗余回调路径
4. 最终保留：Flow（列表结构）+ SharedFlow/callback（实时进度）

### 预估：8-12 小时

---

## #5: LANraragi API 增强

### 现状
当前覆盖的 LANraragi API：
- ✅ 归档搜索/列表/详情/上传/删除
- ✅ 文件提取/页面获取
- ✅ 分类 CRUD
- ✅ 服务器信息
- ✅ 阅读进度同步
- ✅ Minion 任务管理

### 可增强方向

**A. 标签自动补全**
```
GET /api/database/stats  → 返回所有标签及出现频率
```
在 SearchBar 输入时用本地缓存的标签列表做前缀匹配。LANraragi 的 `/api/database/stats` 返回 `[{"namespace":"artist","text":"name","weight":5}, ...]`。

实现：
1. 新增 `LRRTagApi.kt` — `suspend fun getTagStats(): List<LRRTagStat>`
2. 在 `SearchBar` 的 `SuggestionProvider` 中，除了搜索历史还提供标签匹配
3. 缓存 tag 列表到内存（TTL 10 分钟），避免每次输入都请求
4. 预估：4-6 小时

**B. 批量标签编辑**
```
PUT /api/archives/:id/metadata  → 更新归档标签
```
允许用户在详情页编辑标签并同步到服务器。

实现：
1. 新增 `LRRArchiveApi.updateMetadata()` 
2. GalleryDetailScene 添加"编辑标签"按钮
3. 标签编辑对话框
4. 预估：6-8 小时

**C. 服务器端搜索缓存**
LANraragi 搜索结果是分页的，但客户端每次翻页都重新搜索。可利用 LANraragi 的搜索缓存（同一 filter 的后续请求更快）：
- 保持搜索参数不变时，只传 `start` 偏移
- 已实现基本功能，可优化为预加载下一页

**推荐优先级：** A（标签补全）> C（搜索优化）> B（标签编辑）

### 预估总计：10-15 小时

---

## #6: Paging 3 画廊列表

### 现状
`GalleryListScene` 手动分页：
- `ContentHelper` 管理页码、加载状态
- 用户滚到底部触发 `getPageData(taskId, page)` 
- 结果手动 append 到列表

### 目标
用 Jetpack Paging 3 替代手动分页，获得：
- 自动预加载（用户还没滚到底就开始加载）
- 内存管理（自动丢弃远离视口的页面）
- 加载状态 UI（header/footer loading spinner）
- 重试机制

### 设计

**PagingSource:**
```kotlin
class LRRArchivePagingSource(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val query: String?,
    private val category: String?,
    private val sortBy: String?,
    private val sortOrder: Int?
) : PagingSource<Int, GalleryInfo>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, GalleryInfo> {
        val page = params.key ?: 0
        val pageSize = params.loadSize
        return try {
            val result = LRRSearchApi.searchArchives(
                client, baseUrl, query, category, sortBy, sortOrder,
                start = page * pageSize
            )
            val items = result.data?.map { it.toGalleryInfo() } ?: emptyList()
            LoadResult.Page(
                data = items,
                prevKey = if (page > 0) page - 1 else null,
                nextKey = if (items.size >= pageSize) page + 1 else null
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, GalleryInfo>): Int? {
        return state.anchorPosition?.let { it / state.config.pageSize }
    }
}
```

**ViewModel (新增):**
```kotlin
class GalleryListViewModel : ViewModel() {
    private var currentQuery: String? = null

    val galleryFlow: Flow<PagingData<GalleryInfo>> = ...

    fun search(query: String?, category: String?, ...) {
        currentQuery = query
        // Invalidate pager
    }
}
```

### 注意事项
- `ContentHelper` 是 `ContentLayout.java`（hippo 框架）的一部分，被多个 Scene 使用
- 不能一次性替换所有 Scene — 先在 GalleryListScene 做试点
- Paging 3 需要 `RecyclerView.Adapter` 是 `PagingDataAdapter` — 需要替换 `GalleryAdapterNew`
- 搜索参数变化时需要 invalidate PagingSource

### 推荐实施顺序
1. 添加 Paging 3 依赖到 `libs.versions.toml`
2. 创建 `LRRArchivePagingSource`
3. 创建 `GalleryListViewModel`
4. GalleryListScene 试点 Paging（保留 ContentHelper 作为 fallback）
5. 验证后其他 Scene 跟进

### 预估：12-16 小时

---

## #7: DownloadManager 单元测试

### 现状
`DownloadManager.kt` (1268 行) 是核心状态管理，管理：
- 下载队列（等待/活跃/完成/失败）
- 标签分组
- 并发下载限制
- 进度通知
- 数据库同步

**零测试覆盖。**

### 测试策略

**需要 mock 的依赖：**
- `EhDB` — Room 数据库操作
- `LRRDownloadWorker` — 实际下载执行
- `SimpleHandler` — 主线程 Handler

**使用 TestServiceRegistryHelper** — 项目已有测试基础设施用于 mock ServiceRegistry。

### 测试矩阵

**A. 下载状态机 (8 tests)**
```
STATE_NONE → startDownload() → STATE_WAIT/STATE_DOWNLOAD
STATE_DOWNLOAD → onFinish() → STATE_FINISH
STATE_DOWNLOAD → onError() → STATE_FAILED
STATE_WAIT → stopDownload() → STATE_NONE
STATE_DOWNLOAD → stopDownload() → STATE_NONE
STATE_FINISH → deleteDownload() → removed
startAllDownload() → all NONE → WAIT
stopAllDownload() → all active → NONE
```

**B. 标签管理 (5 tests)**
```
addLabel() → label added to list + DB
renameLabel() → label renamed + downloads updated
removeLabel() → downloads moved to default
moveLabel() → order changed in DB
getInfoListForLabel(null) → default list
```

**C. 下载队列 (4 tests)**
```
maxConcurrent(3) → only 3 active at a time
finish one → next in wait starts
cancel active → next in wait starts
concurrent access → no ConcurrentModificationException
```

**D. 通知机制 (3 tests)**
```
addDownload() → listeners notified with onAdd
removeDownload() → listeners notified with onRemove
reload() → listeners notified with onReload
```

### 推荐实施
1. 创建 `DownloadManagerTest.kt` in `app/src/test/`
2. 使用 `kotlinx-coroutines-test` 的 `runTest` 
3. Mock EhDB 的 suspend 函数（`mockk` 或手写 fake）
4. 先测状态机（最高价值），再测标签，最后测队列

### 预估：6-8 小时

---

## 优先级和依赖关系

```
#7 (测试) ────── 无依赖，随时可做，为后续重构提供安全网
#5A (标签补全) ── 无依赖，独立功能
#4 (Flow) ─────── 依赖 #7（有测试才敢改 DownloadManager 的通知机制）
#6 (Paging) ───── 依赖 #5A（搜索 API 需要稳定），最大改动
#5B (标签编辑) ── 依赖 #5A（需要标签数据）
```

**推荐执行顺序：** #7 → #5A → #4 → #6 → #5B
