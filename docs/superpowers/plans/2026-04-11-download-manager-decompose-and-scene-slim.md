# DownloadManager 拆分 + DownloadsScene 瘦身 实施方案

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 1448 行的 `DownloadManager` God Class 拆分为 4 个协作者（Repository / Scheduler / EventBus / Facade），然后将 `DownloadsScene` 的 `DownloadInfoListener` 彻底移入 `DownloadsViewModel`，使 Scene 不再直接持有 `DownloadManager` 引用。

**Architecture:** DownloadManager 拆分为三个内部组件 + 一个 Facade。Repository 管理集合 + DB 读写，Scheduler 管理 Worker 调度 + 状态机，EventBus 管理 Listener 分发。现有 Facade (`DownloadManager`) 保留所有公共 API 签名不变，内部委派给三个组件。拆分完成后，DownloadsScene 的 `DownloadInfoListener` 移入 DownloadsViewModel，Scene 通过 StateFlow 观察变化。

**Tech Stack:** Kotlin, Coroutines, Room, WeakReference, Handler(Looper.getMainLooper()), Robolectric unit tests

---

## 总体依赖关系

```
Task 1: DownloadEventBus 抽取（独立，无前置）
Task 2: DownloadRepository 抽取（独立，无前置）
Task 3: DownloadScheduler 抽取（依赖 Task 1 + Task 2）
Task 4: DownloadManager Facade 瘦身（依赖 Task 3）
Task 5: DownloadsScene DownloadInfoListener 移入 ViewModel（依赖 Task 4）
```

Task 1 和 Task 2 可并行。Task 3 依赖二者。Task 4 是最终组装。Task 5 是 W6-4 的核心。

---

## 文件结构

### 新建文件

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/hippo/ehviewer/download/DownloadEventBus.kt` | Listener 注册/解注册/分发（WeakReference list + forEachListener） |
| `app/src/main/java/com/hippo/ehviewer/download/DownloadRepository.kt` | 集合管理（mAllInfoList/Map, mMap, mLabelList/Set/CountMap, mDefaultInfoList）+ DB 读写 |
| `app/src/main/java/com/hippo/ehviewer/download/DownloadScheduler.kt` | Worker 调度（mWaitList, mActiveTasks, mActiveWorkers）+ 状态机 |
| `app/src/test/java/com/hippo/ehviewer/download/DownloadEventBusTest.kt` | EventBus 单元测试 |
| `app/src/test/java/com/hippo/ehviewer/download/DownloadRepositoryTest.kt` | Repository 单元测试 |
| `app/src/test/java/com/hippo/ehviewer/download/DownloadSchedulerTest.kt` | Scheduler 单元测试 |

### 修改文件

| 文件 | 变更内容 |
|---|---|
| `app/src/main/java/com/hippo/ehviewer/download/DownloadManager.kt` | 从 1448 行瘦身到 ~350 行 Facade，内部委派给三个组件 |
| `app/src/main/java/com/hippo/ehviewer/download/DownloadSpeedTracker.kt` | Callback 接口适配：从 DownloadManager 内部字段改为 EventBus/Repository 提供 |
| `app/src/main/java/com/hippo/ehviewer/ui/scene/download/DownloadsScene.kt` | 移除 `DownloadInfoListener` 实现，不再直接持有 `_downloadManager` |
| `app/src/main/java/com/hippo/ehviewer/ui/scene/download/DownloadsViewModel.kt` | 接管 `DownloadInfoListener`，暴露 StateFlow 供 Scene 观察 |
| `app/src/test/java/com/hippo/ehviewer/download/DownloadManagerTest.kt` | 验证 Facade API 不变，全部 29 个现有测试通过 |

---

## Task 1: DownloadEventBus 抽取

**Files:**
- Create: `app/src/main/java/com/hippo/ehviewer/download/DownloadEventBus.kt`
- Create: `app/src/test/java/com/hippo/ehviewer/download/DownloadEventBusTest.kt`
- Modify: `app/src/main/java/com/hippo/ehviewer/download/DownloadManager.kt`

从 `DownloadManager` 中抽取 Listener 管理和事件分发职责。当前 DownloadManager 中涉及的成员：
- `mDownloadListener: DownloadListener?`（第 73 行）
- `mDownloadInfoListeners: MutableList<WeakReference<DownloadInfoListener>>`（第 74 行）
- `forEachListener()`（第 99-112 行）
- `DownloadEvent` sealed interface（第 1270-1300 行）
- `dispatchEvent()`（第 1306-1381 行）
- `postEvent()`（第 1386-1388 行）

### Step 1: 创建 DownloadEventBus 类

- [ ] **Step 1.1: 编写 DownloadEventBus 类**

```kotlin
// app/src/main/java/com/hippo/ehviewer/download/DownloadEventBus.kt
package com.hippo.ehviewer.download

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * Manages registration and dispatch of [DownloadInfoListener] and [DownloadListener].
 * Extracted from DownloadManager to give it a single responsibility.
 *
 * All public methods must be called on the main thread (enforced by [assertMainThread]).
 */
class DownloadEventBus {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var downloadListener: DownloadListener? = null
    private val infoListeners: MutableList<WeakReference<DownloadInfoListener>> = ArrayList()

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "DownloadEventBus method must be called on the main thread, current: ${Thread.currentThread().name}"
        }
    }

    fun addDownloadInfoListener(listener: DownloadInfoListener) {
        assertMainThread()
        infoListeners.add(WeakReference(listener))
    }

    fun removeDownloadInfoListener(listener: DownloadInfoListener) {
        assertMainThread()
        infoListeners.removeAll { it.get() == null || it.get() === listener }
    }

    fun setDownloadListener(listener: DownloadListener?) {
        assertMainThread()
        downloadListener = listener
    }

    fun getDownloadListener(): DownloadListener? = downloadListener

    /**
     * Returns the raw list of weak references for [DownloadSpeedTracker] callback.
     */
    fun getInfoListenerRefs(): List<WeakReference<DownloadInfoListener>> = infoListeners

    /**
     * Iterates over registered [DownloadInfoListener]s, unwrapping each
     * [WeakReference] and skipping GC'd entries. Periodically cleans up null refs.
     */
    inline fun forEachListener(action: (DownloadInfoListener) -> Unit) {
        var hasNull = false
        for (ref in infoListeners) {
            val listener = ref.get()
            if (listener != null) {
                action(listener)
            } else {
                hasNull = true
            }
        }
        if (hasNull) {
            infoListeners.removeAll { it.get() == null }
        }
    }

    /**
     * Post a block to the main thread for execution.
     */
    fun postToMain(block: () -> Unit) {
        mainHandler.post(block)
    }
}
```

- [ ] **Step 1.2: 编写 DownloadEventBusTest**

```kotlin
// app/src/test/java/com/hippo/ehviewer/download/DownloadEventBusTest.kt
package com.hippo.ehviewer.download

import com.hippo.ehviewer.dao.DownloadInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DownloadEventBusTest {

    private lateinit var bus: DownloadEventBus

    @Before
    fun setUp() {
        bus = DownloadEventBus()
    }

    @Test
    fun addAndRemoveInfoListener() {
        val events = mutableListOf<String>()
        val listener = object : FakeDownloadInfoListener() {
            override fun onReload() { events.add("reload") }
        }
        bus.addDownloadInfoListener(listener)
        bus.forEachListener { it.onReload() }
        assertEquals(listOf("reload"), events)

        bus.removeDownloadInfoListener(listener)
        events.clear()
        bus.forEachListener { it.onReload() }
        assertTrue(events.isEmpty())
    }

    @Test
    fun forEachListener_skipsGarbageCollectedRefs() {
        val events = mutableListOf<String>()
        // Add a listener that we'll "lose" the strong reference to
        var weakListener: FakeDownloadInfoListener? = object : FakeDownloadInfoListener() {
            override fun onReload() { events.add("weak") }
        }
        bus.addDownloadInfoListener(weakListener!!)

        val strongListener = object : FakeDownloadInfoListener() {
            override fun onReload() { events.add("strong") }
        }
        bus.addDownloadInfoListener(strongListener)

        // Simulate GC of the weak listener
        @Suppress("UNUSED_VALUE")
        weakListener = null
        System.gc()
        Thread.sleep(100)

        events.clear()
        bus.forEachListener { it.onReload() }
        // At minimum, the strong one fires
        assertTrue("strong" in events)
    }

    @Test
    fun setAndGetDownloadListener() {
        assertNull(bus.getDownloadListener())

        val listener = object : DownloadListener {
            override fun onGet509() {}
            override fun onStart(info: DownloadInfo) {}
            override fun onDownload(info: DownloadInfo) {}
            override fun onGetPage(info: DownloadInfo) {}
            override fun onFinish(info: DownloadInfo) {}
            override fun onCancel(info: DownloadInfo) {}
        }
        bus.setDownloadListener(listener)
        assertSame(listener, bus.getDownloadListener())

        bus.setDownloadListener(null)
        assertNull(bus.getDownloadListener())
    }

    @Test
    fun getInfoListenerRefs_returnsRegisteredRefs() {
        val listener = object : FakeDownloadInfoListener() {}
        bus.addDownloadInfoListener(listener)
        assertEquals(1, bus.getInfoListenerRefs().size)
        assertSame(listener, bus.getInfoListenerRefs()[0].get())
    }

    @Test
    fun forEachListener_emptyListDoesNotThrow() {
        // Should not throw
        bus.forEachListener { it.onReload() }
    }

    @Test
    fun multipleListeners_allNotified() {
        val events = mutableListOf<Int>()
        for (i in 1..5) {
            val id = i
            bus.addDownloadInfoListener(object : FakeDownloadInfoListener() {
                override fun onReload() { events.add(id) }
            })
        }
        bus.forEachListener { it.onReload() }
        assertEquals(listOf(1, 2, 3, 4, 5), events)
    }

    @Test
    fun removeListener_onlyRemovesSpecificOne() {
        val events = mutableListOf<String>()
        val listenerA = object : FakeDownloadInfoListener() {
            override fun onReload() { events.add("A") }
        }
        val listenerB = object : FakeDownloadInfoListener() {
            override fun onReload() { events.add("B") }
        }
        bus.addDownloadInfoListener(listenerA)
        bus.addDownloadInfoListener(listenerB)

        bus.removeDownloadInfoListener(listenerA)
        bus.forEachListener { it.onReload() }
        assertEquals(listOf("B"), events)
    }

    @Test
    fun postToMain_executesBlockOnMainThread() {
        var executed = false
        bus.postToMain { executed = true }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(executed)
    }
}
```

- [ ] **Step 1.3: 运行测试验证 DownloadEventBus**

Run: `./gradlew app:testAppReleaseDebugUnitTest --tests "com.hippo.ehviewer.download.DownloadEventBusTest"`
Expected: 8 tests PASS

- [ ] **Step 1.4: 在 DownloadManager 中使用 DownloadEventBus**

在 `DownloadManager` 中：

1. 添加 `DownloadEventBus` 作为构造函数参数（默认创建新实例）：

```kotlin
class DownloadManager(
    private val mContext: Context,
    private val scope: CoroutineScope = ServiceRegistry.coroutineModule.ioScope,
    internal val eventBus: DownloadEventBus = DownloadEventBus()
) {
```

2. 删除以下字段（委派给 eventBus）：
   - `private val mainHandler`（第 51 行）
   - `private var mDownloadListener`（第 73 行）
   - `private val mDownloadInfoListeners`（第 74 行）
   - `private inline fun forEachListener`（第 99-112 行）

3. 将所有 `forEachListener { ... }` 调用改为 `eventBus.forEachListener { ... }`。涉及行号（基于当前文件）：
   - `publishLoadedData`（第 285 行）
   - `replaceInfo`（第 334 行）
   - `startDownload` 内 3 处（第 547, 579 行区域）
   - `startRangeDownload`（第 643, 684 行区域）
   - `startAllDownload`（第 684 行区域）
   - `addDownload(List)`（第 743 行区域）
   - `stopDownload`（第 855 行区域）
   - `stopCurrentDownload`（第 869 行区域）
   - `stopRangeDownload`（第 881 行区域）
   - `stopAllDownload`（第 901 行区域）
   - `deleteDownload`（第 921 行区域）
   - `deleteRangeDownload`（第 969 行区域）
   - `ensureDownload`（第 511 行区域）
   - `dispatchEvent` 内的所有 listener 分发

4. 将公共方法委派：
   - `addDownloadInfoListener(listener)` → `eventBus.addDownloadInfoListener(listener!!)`
   - `removeDownloadInfoListener(listener)` → `eventBus.removeDownloadInfoListener(listener!!)`
   - `setDownloadListener(listener)` → `eventBus.setDownloadListener(listener)`

5. 将 `mainHandler.post { ... }` 调用改为 `eventBus.postToMain { ... }`。

6. 将 `mDownloadListener?.onXxx()` 调用改为 `eventBus.getDownloadListener()?.onXxx()`。

7. 更新 `DownloadSpeedTracker` 的 Callback 实现中的 `getDownloadListener()` 和 `getDownloadInfoListeners()` 委派给 `eventBus`。

- [ ] **Step 1.5: 运行全部测试验证**

Run: `./gradlew app:testAppReleaseDebugUnitTest`
Expected: 全部测试 PASS（现有 DownloadManagerTest 29 个 + 新增 DownloadEventBusTest 8 个）

- [ ] **Step 1.6: Commit**

```bash
git add app/src/main/java/com/hippo/ehviewer/download/DownloadEventBus.kt \
       app/src/test/java/com/hippo/ehviewer/download/DownloadEventBusTest.kt \
       app/src/main/java/com/hippo/ehviewer/download/DownloadManager.kt
git commit -m "refactor: extract DownloadEventBus from DownloadManager

Move listener registration/unregistration and event dispatch into
DownloadEventBus. DownloadManager delegates to eventBus for all
listener operations. Public API unchanged.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: DownloadRepository 抽取

**Files:**
- Create: `app/src/main/java/com/hippo/ehviewer/download/DownloadRepository.kt`
- Create: `app/src/test/java/com/hippo/ehviewer/download/DownloadRepositoryTest.kt`
- Modify: `app/src/main/java/com/hippo/ehviewer/download/DownloadManager.kt`

从 `DownloadManager` 中抽取集合管理和 DB 读写职责。当前涉及成员：
- 8 个共享集合：`mAllInfoList`, `mAllInfoMap`, `mMap`, `mLabelCountMap`, `mLabelList`, `mLabelSet`, `mDefaultInfoList`（不含 `mWaitList`，那属于 Scheduler）
- `loadDataFromDb()`（第 190-256 行）
- `publishLoadedData()`（第 262-286 行）
- `getInfoListForLabel()`（第 337-343 行）
- `containLabel()`（第 345-351 行）
- `containDownloadInfo()`（第 353-356 行）
- 所有 label CRUD 方法：`addLabel`, `moveLabel`, `renameLabel`, `deleteLabel`
- 所有 getter properties：`labelList`, `allDownloadInfoList`, `defaultDownloadInfoList`, `downloadInfoList`, etc.
- `LoadedDownloadData` data class（第 174-180 行）
- `insertSorted()`（第 1442-1446 行）
- `DATE_DESC_COMPARATOR`（第 1428-1435 行）

### Step 2: 创建 DownloadRepository 类

- [ ] **Step 2.1: 编写 DownloadRepository 类**

```kotlin
// app/src/main/java/com/hippo/ehviewer/download/DownloadRepository.kt
package com.hippo.ehviewer.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.dao.DownloadLabel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Manages the in-memory download data collections and their DB persistence.
 * Extracted from DownloadManager to give it a single responsibility.
 *
 * All public read/write methods on shared collections must be called from the main thread.
 */
class DownloadRepository(
    private val context: Context,
    private val scope: CoroutineScope
) {

    // All download info list
    val allInfoList: MutableList<DownloadInfo> = ArrayList()
    // All download info map — O(1) lookup by gid
    val allInfoMap: HashMap<Long, DownloadInfo> = HashMap()
    // label and info list map, without default label info list
    val labelToInfoMap: MutableMap<String?, MutableList<DownloadInfo>> = HashMap()
    val labelCountMap: MutableMap<String?, Long> = HashMap()
    // All labels without default label
    val labelList: MutableList<DownloadLabel> = mutableListOf()
    // O(1) label existence check
    val labelSet: HashSet<String> = HashSet()
    // Store download info with default label
    val defaultInfoList: MutableList<DownloadInfo> = ArrayList()

    /** Signals when async init is complete. */
    val initDeferred = CompletableDeferred<Unit>()

    @Volatile
    var initialized = false
        private set

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "DownloadRepository method must be called on the main thread, current: ${Thread.currentThread().name}"
        }
    }

    /**
     * Run [block] on the main thread. If already on main thread, runs inline.
     */
    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }

    /**
     * Holds the result of [loadDataFromDb]'s IO phase.
     */
    private data class LoadedDownloadData(
        val labels: List<DownloadLabel>,
        val extraSavedLabels: List<DownloadLabel>,
        val labelStrings: Set<String>,
        val allInfoList: List<DownloadInfo>,
        val labelToInfoList: Map<String?, MutableList<DownloadInfo>>
    )

    /**
     * Start async DB loading. Call once from DownloadManager init.
     * [onComplete] is called on the main thread after data is published.
     */
    fun startLoading(onComplete: () -> Unit) {
        scope.launch {
            try {
                loadDataFromDb(onComplete)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load download data from DB", e)
                runOnMainThread {
                    initialized = true
                    initDeferred.complete(Unit)
                    onComplete()
                }
            }
        }
    }

    private suspend fun loadDataFromDb(onComplete: () -> Unit) {
        val loadedLabels = EhDB.getAllDownloadLabelListAsync()
        val loadedInfos = EhDB.getAllDownloadInfoAsync()

        val labelStrings: HashSet<String> = HashSet()
        for (label in loadedLabels) {
            label.label?.let { labelStrings.add(it) }
        }

        val mapDraft: LinkedHashMap<String?, MutableList<DownloadInfo>> = LinkedHashMap()
        for (label in loadedLabels) {
            mapDraft[label.label] = ArrayList()
        }

        val orphanLabelStrings: MutableList<String> = ArrayList()

        for (info in loadedInfos) {
            val archiveUri = info.archiveUri
            if (archiveUri != null && archiveUri.startsWith("content://")) {
                try {
                    val uri = Uri.parse(archiveUri)
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore URI permission for $archiveUri", e)
                }
            }

            var list = mapDraft[info.label]
            if (list == null) {
                list = ArrayList()
                mapDraft[info.label] = list
                val infoLabel = info.label
                if (infoLabel != null && !labelStrings.contains(infoLabel)) {
                    orphanLabelStrings.add(infoLabel)
                    labelStrings.add(infoLabel)
                }
            }
            list.add(info)
        }

        val extraSavedLabels = EhDB.addDownloadLabelsAsync(orphanLabelStrings)

        val loaded = LoadedDownloadData(
            labels = loadedLabels,
            extraSavedLabels = extraSavedLabels,
            labelStrings = labelStrings,
            allInfoList = loadedInfos,
            labelToInfoList = mapDraft
        )

        runOnMainThread {
            publishLoadedData(loaded)
            onComplete()
        }
    }

    private fun publishLoadedData(loaded: LoadedDownloadData) {
        labelList.addAll(loaded.labels)
        labelList.addAll(loaded.extraSavedLabels)
        labelSet.addAll(loaded.labelStrings)

        allInfoList.addAll(loaded.allInfoList)
        for (info in loaded.allInfoList) {
            allInfoMap[info.gid] = info
        }

        for ((label, list) in loaded.labelToInfoList) {
            labelToInfoMap[label] = list
        }

        for ((key, value) in labelToInfoMap) {
            labelCountMap[key] = value.size.toLong()
        }

        initialized = true
        initDeferred.complete(Unit)
    }

    fun getInfoListForLabel(label: String?): MutableList<DownloadInfo>? {
        return if (label == null) defaultInfoList else labelToInfoMap[label]
    }

    fun containLabel(label: String?): Boolean {
        assertMainThread()
        return label != null && labelSet.contains(label)
    }

    fun containDownloadInfo(gid: Long): Boolean {
        assertMainThread()
        return allInfoMap.containsKey(gid)
    }

    fun getDownloadInfo(gid: Long): DownloadInfo? {
        assertMainThread()
        return allInfoMap[gid]
    }

    fun getDownloadState(gid: Long): Int {
        assertMainThread()
        return allInfoMap[gid]?.state ?: DownloadInfo.STATE_INVALID
    }

    fun getLabelCount(label: String?): Long {
        assertMainThread()
        return try {
            labelCountMap[label] ?: 0L
        } catch (e: NullPointerException) {
            com.hippo.ehviewer.Analytics.recordException(e)
            0L
        }
    }

    /**
     * Reload all download data from DB (e.g., after server switch).
     * Must be called on the main thread. [onComplete] runs on main thread
     * after data is published.
     */
    fun reload(onComplete: () -> Unit) {
        assertMainThread()
        allInfoList.clear()
        allInfoMap.clear()
        defaultInfoList.clear()
        for ((_, value) in labelToInfoMap) {
            value.clear()
        }

        scope.launch {
            val reloadedInfos = EhDB.getAllDownloadInfoAsync()
            runOnMainThread {
                allInfoList.addAll(reloadedInfos)
                for (info in reloadedInfos) {
                    allInfoMap[info.gid] = info
                    var list = getInfoListForLabel(info.label)
                    if (list == null) {
                        list = ArrayList()
                        labelToInfoMap[info.label] = list
                    }
                    list.add(info)
                }
                onComplete()
            }
        }
    }

    // ─── Label CRUD ─────────────────────────────────────────────

    /**
     * Add a new label. Returns the created DownloadLabel, or null if already exists.
     * [onSaved] is called on main thread with the persisted label.
     */
    fun addLabel(label: String?, onSaved: ((DownloadLabel) -> Unit)? = null) {
        assertMainThread()
        if (label == null || containLabel(label)) return

        val newLabel = DownloadLabel().apply { this.label = label }
        labelList.add(newLabel)
        labelSet.add(label)
        labelToInfoMap[label] = ArrayList()

        scope.launch {
            val saved = EhDB.addDownloadLabelAsync(label)
            runOnMainThread {
                // Update the in-memory label with the DB-assigned ID
                val idx = labelList.indexOf(newLabel)
                if (idx >= 0) labelList[idx] = saved
                onSaved?.invoke(saved)
            }
        }
    }

    fun moveLabel(fromPosition: Int, toPosition: Int) {
        assertMainThread()
        val item = labelList.removeAt(fromPosition)
        labelList.add(toPosition, item)
        scope.launch { EhDB.updateDownloadLabelOrderAsync(labelList) }
    }

    /**
     * Rename a label. Returns the list of infos that were updated.
     */
    fun renameLabel(from: String, to: String): List<DownloadInfo> {
        assertMainThread()
        if (!containLabel(from) || containLabel(to)) return emptyList()

        var rawLabel: DownloadLabel? = null
        for (label in labelList) {
            if (label.label == from) {
                rawLabel = label
                label.label = to
                break
            }
        }

        labelSet.remove(from)
        labelSet.add(to)

        val list = labelToInfoMap.remove(from) ?: return emptyList()
        labelToInfoMap[to] = list

        for (info in list) {
            info.label = to
        }

        val labelToUpdate = rawLabel
        val infosToUpdate = ArrayList(list)
        scope.launch {
            if (labelToUpdate != null) EhDB.updateDownloadLabelAsync(labelToUpdate)
            for (info in infosToUpdate) EhDB.putDownloadInfoAsync(info)
        }

        return list
    }

    /**
     * Delete a label. Returns the list of infos that were moved to default.
     */
    fun deleteLabel(label: String): List<DownloadInfo> {
        assertMainThread()

        var removedLabel: DownloadLabel? = null
        val iterator = labelList.iterator()
        while (iterator.hasNext()) {
            val raw = iterator.next()
            if (raw.label == label) {
                removedLabel = raw
                iterator.remove()
                break
            }
        }
        labelSet.remove(label)

        val list = labelToInfoMap.remove(label) ?: return emptyList()
        for (info in list) {
            info.label = null
            defaultInfoList.add(info)
        }

        val labelToRemove = removedLabel
        val infosToUpdate = ArrayList(list)
        scope.launch {
            if (labelToRemove != null) EhDB.removeDownloadLabelAsync(labelToRemove)
            for (info in infosToUpdate) EhDB.putDownloadInfoAsync(info)
        }

        return list
    }

    fun changeLabel(infos: List<DownloadInfo>, newLabel: String?) {
        assertMainThread()
        if (newLabel != null && !containLabel(newLabel)) return

        val dstList = getInfoListForLabel(newLabel) ?: return

        for (info in infos) {
            val srcList = getInfoListForLabel(info.label)
            srcList?.remove(info)
            info.label = newLabel
            insertSorted(dstList, info)
        }

        val snapshot = ArrayList(infos)
        scope.launch {
            for (info in snapshot) EhDB.putDownloadInfoAsync(info)
        }
    }

    // ─── Info CRUD ──────────────────────────────────────────────

    fun replaceInfo(newInfo: DownloadInfo, oldInfo: DownloadInfo) {
        assertMainThread()
        for (i in allInfoList.indices) {
            if (oldInfo.gid == allInfoList[i].gid) {
                allInfoList[i] = newInfo
                break
            }
        }
        val infoList = getInfoListForLabel(oldInfo.label)
        if (infoList != null) {
            for (i in infoList.indices) {
                if (oldInfo.gid == infoList[i].gid) {
                    infoList[i] = newInfo
                    break
                }
            }
        }
        allInfoMap.remove(oldInfo.gid)
        allInfoMap[newInfo.gid] = newInfo
    }

    fun addInfo(info: DownloadInfo): MutableList<DownloadInfo>? {
        val list = getInfoListForLabel(info.label) ?: return null
        list.add(0, info)
        allInfoList.add(0, info)
        allInfoMap[info.gid] = info
        scope.launch { EhDB.putDownloadInfoAsync(info) }
        return list
    }

    fun addInfoSorted(info: DownloadInfo): MutableList<DownloadInfo>? {
        val list = getInfoListForLabel(info.label) ?: return null
        insertSorted(list, info)
        insertSorted(allInfoList, info)
        allInfoMap[info.gid] = info
        scope.launch { EhDB.putDownloadInfoAsync(info) }
        return list
    }

    fun removeInfo(gid: Long): Pair<DownloadInfo, Int>? {
        val info = allInfoMap[gid] ?: return null
        allInfoList.remove(info)
        allInfoMap.remove(gid)

        val list = getInfoListForLabel(info.label)
        val index = list?.indexOf(info) ?: -1
        if (list != null && index >= 0) {
            list.removeAt(index)
        }

        scope.launch { EhDB.removeDownloadInfoAsync(gid) }
        return Pair(info, index)
    }

    fun removeInfoBatch(gidSet: Set<Long>): List<Long> {
        val gidsToRemove = mutableListOf<Long>()
        for (gid in gidSet) {
            val info = allInfoMap.remove(gid) ?: continue
            gidsToRemove.add(info.gid)
            getInfoListForLabel(info.label)?.remove(info)
        }
        allInfoList.removeAll { it.gid in gidSet }
        if (gidsToRemove.isNotEmpty()) {
            scope.launch { EhDB.removeDownloadInfoBatchAsync(gidsToRemove) }
        }
        return gidsToRemove
    }

    fun persistInfo(info: DownloadInfo) {
        scope.launch { EhDB.putDownloadInfoAsync(info) }
    }

    fun persistHistory(info: DownloadInfo) {
        scope.launch { EhDB.putHistoryInfoAsync(info) }
    }

    companion object {
        private const val TAG = "DownloadRepository"

        @JvmField
        val DATE_DESC_COMPARATOR: Comparator<DownloadInfo> = Comparator { lhs, rhs ->
            val dif = lhs.time - rhs.time
            when {
                dif > 0 -> -1
                dif < 0 -> 1
                else -> 0
            }
        }

        internal fun insertSorted(list: MutableList<DownloadInfo>, item: DownloadInfo) {
            val index = Collections.binarySearch(list, item, DATE_DESC_COMPARATOR)
            val insertionPoint = if (index < 0) -(index + 1) else index
            list.add(insertionPoint, item)
        }
    }
}
```

- [ ] **Step 2.2: 编写 DownloadRepositoryTest**

```kotlin
// app/src/test/java/com/hippo/ehviewer/download/DownloadRepositoryTest.kt
package com.hippo.ehviewer.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.Settings
import com.lanraragi.reader.client.api.LRRAuthManager
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.module.CoroutineModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DownloadRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: DownloadRepository
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Settings.initialize(context)
        ServiceRegistry.initializeForTest(CoroutineModule())
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        LRRAuthManager.initialize(context)
        val method = LRRAuthManager::class.java.declaredMethods.first {
            it.name.startsWith("initializeForTesting") &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == android.content.SharedPreferences::class.java
        }
        method.isAccessible = true
        method.invoke(null, context.getSharedPreferences("lrr_auth_test_repo", Context.MODE_PRIVATE))

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

        val dbField = EhDB::class.java.getDeclaredField("sDatabase")
        dbField.isAccessible = true
        dbField.set(EhDB, db)

        repo = DownloadRepository(context, testScope)
        repo.startLoading {}
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        testScope.cancel()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        db.close()
        LRRAuthManager.clear()
    }

    @Test
    fun initialState_emptyDb() {
        assertTrue(repo.allInfoList.isEmpty())
        assertTrue(repo.allInfoMap.isEmpty())
        assertTrue(repo.labelList.isEmpty())
        assertTrue(repo.initialized)
    }

    @Test
    fun containDownloadInfo_returnsTrueForExisting() {
        val info = DownloadInfo().apply {
            gid = 100L; token = "t1"; title = "Test"; state = DownloadInfo.STATE_NONE
            time = System.currentTimeMillis()
        }
        repo.addInfo(info)
        assertTrue(repo.containDownloadInfo(100L))
        assertFalse(repo.containDownloadInfo(999L))
    }

    @Test
    fun addAndRemoveInfo() {
        val info = DownloadInfo().apply {
            gid = 200L; token = "t2"; title = "Add"; state = DownloadInfo.STATE_NONE
            time = System.currentTimeMillis()
        }
        repo.addInfo(info)
        assertEquals(1, repo.allInfoList.size)
        assertNotNull(repo.getDownloadInfo(200L))

        val result = repo.removeInfo(200L)
        assertNotNull(result)
        assertEquals(200L, result!!.first.gid)
        assertTrue(repo.allInfoList.isEmpty())
    }

    @Test
    fun getInfoListForLabel_defaultReturnsDefaultList() {
        assertSame(repo.defaultInfoList, repo.getInfoListForLabel(null))
    }

    @Test
    fun getInfoListForLabel_namedReturnsLabelList() {
        repo.addLabel("Comics")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertNotNull(repo.getInfoListForLabel("Comics"))
    }

    @Test
    fun addLabel_createsLabelAndMap() {
        repo.addLabel("Manga")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(repo.containLabel("Manga"))
        assertNotNull(repo.getInfoListForLabel("Manga"))
    }

    @Test
    fun addLabel_duplicateIsNoOp() {
        repo.addLabel("Dup")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val sizeBefore = repo.labelList.size
        repo.addLabel("Dup")
        assertEquals(sizeBefore, repo.labelList.size)
    }

    @Test
    fun renameLabel_updatesInfoAndSets() {
        repo.addLabel("Old")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val info = DownloadInfo().apply {
            gid = 300L; token = "t3"; title = "Rename"; label = "Old"
            state = DownloadInfo.STATE_NONE; time = System.currentTimeMillis()
        }
        repo.labelToInfoMap["Old"]!!.add(info)
        repo.allInfoList.add(info)
        repo.allInfoMap[info.gid] = info

        val updated = repo.renameLabel("Old", "New")

        assertFalse(repo.containLabel("Old"))
        assertTrue(repo.containLabel("New"))
        assertEquals(1, updated.size)
        assertEquals("New", updated[0].label)
    }

    @Test
    fun deleteLabel_movesInfosToDefault() {
        repo.addLabel("ToDelete")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val info = DownloadInfo().apply {
            gid = 400L; token = "t4"; title = "Delete"; label = "ToDelete"
            state = DownloadInfo.STATE_NONE; time = System.currentTimeMillis()
        }
        repo.labelToInfoMap["ToDelete"]!!.add(info)
        repo.allInfoList.add(info)
        repo.allInfoMap[info.gid] = info

        val moved = repo.deleteLabel("ToDelete")

        assertFalse(repo.containLabel("ToDelete"))
        assertEquals(1, moved.size)
        assertNull(moved[0].label)
        assertTrue(repo.defaultInfoList.contains(info))
    }

    @Test
    fun replaceInfo_updatesMapAndList() {
        val old = DownloadInfo().apply {
            gid = 500L; token = "t5"; title = "Old"; state = DownloadInfo.STATE_NONE
            time = System.currentTimeMillis()
        }
        repo.addInfo(old)

        val new = DownloadInfo().apply {
            gid = 501L; token = "t5b"; title = "New"; state = DownloadInfo.STATE_FINISH
            time = System.currentTimeMillis()
        }
        repo.replaceInfo(new, old)

        assertNull(repo.getDownloadInfo(500L))
        assertNotNull(repo.getDownloadInfo(501L))
    }

    @Test
    fun getDownloadState_returnsCorrectState() {
        assertEquals(DownloadInfo.STATE_INVALID, repo.getDownloadState(999L))
        val info = DownloadInfo().apply {
            gid = 600L; token = "t6"; title = "State"
            state = DownloadInfo.STATE_FINISH; time = System.currentTimeMillis()
        }
        repo.addInfo(info)
        assertEquals(DownloadInfo.STATE_FINISH, repo.getDownloadState(600L))
    }

    @Test
    fun insertSorted_maintainsDateDescOrder() {
        val list = mutableListOf<DownloadInfo>()
        val times = listOf(300L, 100L, 200L)
        for (t in times) {
            val info = DownloadInfo().apply {
                gid = t; time = t; state = DownloadInfo.STATE_NONE
            }
            DownloadRepository.insertSorted(list, info)
        }
        // DATE_DESC: 300, 200, 100
        assertEquals(300L, list[0].time)
        assertEquals(200L, list[1].time)
        assertEquals(100L, list[2].time)
    }

    @Test
    fun removeInfoBatch_removesMultiple() {
        for (i in 1L..5L) {
            val info = DownloadInfo().apply {
                gid = i; token = "t$i"; title = "Batch$i"
                state = DownloadInfo.STATE_NONE; time = System.currentTimeMillis() + i
            }
            repo.addInfo(info)
        }
        assertEquals(5, repo.allInfoList.size)

        repo.removeInfoBatch(setOf(2L, 4L))
        assertEquals(3, repo.allInfoList.size)
        assertFalse(repo.containDownloadInfo(2L))
        assertFalse(repo.containDownloadInfo(4L))
        assertTrue(repo.containDownloadInfo(1L))
    }
}
```

- [ ] **Step 2.3: 运行测试验证 DownloadRepository**

Run: `./gradlew app:testAppReleaseDebugUnitTest --tests "com.hippo.ehviewer.download.DownloadRepositoryTest"`
Expected: 12 tests PASS

- [ ] **Step 2.4: Commit**

```bash
git add app/src/main/java/com/hippo/ehviewer/download/DownloadRepository.kt \
       app/src/test/java/com/hippo/ehviewer/download/DownloadRepositoryTest.kt
git commit -m "refactor: extract DownloadRepository from DownloadManager

Move collection management (allInfoList/Map, labels, default list)
and DB persistence into DownloadRepository. Includes label CRUD,
info add/remove/replace, and insertSorted. 12 unit tests.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: DownloadScheduler 抽取 + DownloadManager 内部布线

**Files:**
- Create: `app/src/main/java/com/hippo/ehviewer/download/DownloadScheduler.kt`
- Create: `app/src/test/java/com/hippo/ehviewer/download/DownloadSchedulerTest.kt`
- Modify: `app/src/main/java/com/hippo/ehviewer/download/DownloadManager.kt`

从 `DownloadManager` 中抽取 Worker 调度和状态机职责：
- `mWaitList`（第 67 行）
- `mActiveTasks`（第 76 行）
- `mActiveWorkers`（第 77 行）
- `ensureDownload()`（第 487-516 行）
- `stopDownloadInternal()`（第 1008-1040 行）
- `stopCurrentDownloadInternal()`（第 1043-1060 行）
- `stopRangeDownloadInternal()`（第 1063-1092 行）
- `PerTaskListener`（第 1390-1419 行）
- `DownloadEvent` sealed interface + `dispatchEvent` + `postEvent`（第 1270-1388 行）
- `isIdle`（第 1260-1264 行）

### Step 3: 创建 DownloadScheduler 类

- [ ] **Step 3.1: 编写 DownloadScheduler 类**

```kotlin
// app/src/main/java/com/hippo/ehviewer/download/DownloadScheduler.kt
package com.hippo.ehviewer.download

import android.content.Context
import android.os.Looper
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.settings.DownloadSettings
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.lib.image.Image
import kotlinx.coroutines.CoroutineScope

/**
 * Manages the download worker lifecycle: wait queue, active tasks, and state
 * transitions. Extracted from DownloadManager for single responsibility.
 *
 * All public methods must be called on the main thread.
 */
class DownloadScheduler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repo: DownloadRepository,
    private val eventBus: DownloadEventBus,
    private val speedTracker: DownloadSpeedTracker
) {

    val waitList: MutableList<DownloadInfo> = ArrayList()
    val activeTasks: MutableList<DownloadInfo> = ArrayList()
    val activeWorkers: MutableMap<DownloadInfo, LRRDownloadWorker> = HashMap()

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "DownloadScheduler method must be called on the main thread, current: ${Thread.currentThread().name}"
        }
    }

    val isIdle: Boolean
        get() {
            assertMainThread()
            return activeTasks.isEmpty() && waitList.isEmpty()
        }

    /**
     * Start workers up to the configured concurrency limit.
     */
    fun ensureDownload() {
        val maxConcurrent = DownloadSettings.getConcurrentDownloads()
        while (activeTasks.size < maxConcurrent && waitList.isNotEmpty()) {
            val info = waitList.removeAt(0)
            val worker = LRRDownloadWorker(context, info)
            activeTasks.add(info)
            activeWorkers[info] = worker
            worker.listener = PerTaskListener(info)
            info.state = DownloadInfo.STATE_DOWNLOAD
            info.speed = -1
            info.remaining = -1
            info.total = -1
            info.finished = 0
            info.downloaded = 0
            info.legacy = -1
            repo.persistInfo(info)
            speedTracker.start()
            eventBus.getDownloadListener()?.onStart(info)
            val list = repo.getInfoListForLabel(info.label)
            if (list != null) {
                eventBus.forEachListener { it.onUpdate(info, list, waitList) }
            }
            worker.start()
        }
    }

    /**
     * Stop a single download by gid. Returns the stopped info, or null.
     */
    fun stopDownload(gid: Long): DownloadInfo? {
        assertMainThread()
        val activeIt = activeTasks.iterator()
        while (activeIt.hasNext()) {
            val info = activeIt.next()
            if (info.gid == gid) {
                val w = activeWorkers.remove(info)
                w?.cancel()
                activeIt.remove()
                info.state = DownloadInfo.STATE_NONE
                info.speed = -1
                info.remaining = -1
                repo.persistInfo(info)
                eventBus.getDownloadListener()?.onCancel(info)
                if (activeTasks.isEmpty()) speedTracker.stop()
                return info
            }
        }

        val waitIt = waitList.iterator()
        while (waitIt.hasNext()) {
            val info = waitIt.next()
            if (info.gid == gid) {
                waitIt.remove()
                info.state = DownloadInfo.STATE_NONE
                repo.persistInfo(info)
                eventBus.getDownloadListener()?.onCancel(info)
                return info
            }
        }
        return null
    }

    /**
     * Stop all currently active tasks. Returns the list of stopped infos.
     */
    fun stopCurrentDownload(): DownloadInfo? {
        assertMainThread()
        if (activeTasks.isEmpty()) return null

        val stopped = ArrayList(activeTasks)
        for (info in stopped) {
            val w = activeWorkers.remove(info)
            w?.cancel()
            info.state = DownloadInfo.STATE_NONE
            info.speed = -1
            info.remaining = -1
            repo.persistInfo(info)
            eventBus.getDownloadListener()?.onCancel(info)
        }
        activeTasks.clear()
        speedTracker.stop()
        return if (stopped.isNotEmpty()) stopped[0] else null
    }

    /**
     * Stop a range of downloads by gid list.
     */
    fun stopRangeDownload(gidList: com.hippo.lib.yorozuya.collect.LongList) {
        assertMainThread()
        for (i in 0 until gidList.size()) {
            val gid = gidList.get(i)
            // Check active
            val activeIt = activeTasks.iterator()
            while (activeIt.hasNext()) {
                val info = activeIt.next()
                if (info.gid == gid) {
                    val w = activeWorkers.remove(info)
                    w?.cancel()
                    activeIt.remove()
                    info.state = DownloadInfo.STATE_NONE
                    info.speed = -1
                    info.remaining = -1
                    repo.persistInfo(info)
                    eventBus.getDownloadListener()?.onCancel(info)
                    break
                }
            }
            // Check wait
            val waitIt = waitList.iterator()
            while (waitIt.hasNext()) {
                val info = waitIt.next()
                if (info.gid == gid) {
                    waitIt.remove()
                    info.state = DownloadInfo.STATE_NONE
                    repo.persistInfo(info)
                    break
                }
            }
        }
        if (activeTasks.isEmpty()) speedTracker.stop()
    }

    /**
     * Stop all downloads (active + wait).
     */
    fun stopAllDownload() {
        assertMainThread()
        for (info in waitList) {
            info.state = DownloadInfo.STATE_NONE
            repo.persistInfo(info)
        }
        waitList.clear()
        stopCurrentDownload()
    }

    /**
     * Get a non-downloading info: stop it if active/waiting, return the info.
     */
    fun getNoneDownloadInfo(gid: Long): DownloadInfo? {
        assertMainThread()
        var wasActive = false
        for (info in activeTasks) {
            if (info.gid == gid) {
                wasActive = true
                break
            }
        }
        if (wasActive) {
            stopDownload(gid)
        } else {
            val iterator = waitList.iterator()
            while (iterator.hasNext()) {
                val info = iterator.next()
                if (info.gid == gid) {
                    info.state = DownloadInfo.STATE_NONE
                    iterator.remove()
                    break
                }
            }
        }
        return repo.getDownloadInfo(gid)
    }

    // ─── Download Events ────────────────────────────────────────

    /**
     * Sealed interface for immutable download events dispatched from worker threads.
     */
    internal sealed interface DownloadEvent {
        data class OnGetPages(val taskInfo: DownloadInfo, val pages: Int) : DownloadEvent
        data object OnGet509 : DownloadEvent
        data class OnPageDownload(
            val index: Int, val contentLength: Long,
            val receivedSize: Long, val bytesRead: Int
        ) : DownloadEvent
        data class OnPageSuccess(
            val taskInfo: DownloadInfo, val index: Int,
            val finished: Int, val downloaded: Int, val total: Int
        ) : DownloadEvent
        data class OnPageFailure(
            val taskInfo: DownloadInfo, val index: Int, val error: String?,
            val finished: Int, val downloaded: Int, val total: Int
        ) : DownloadEvent
        data class OnFinish(
            val taskInfo: DownloadInfo,
            val finished: Int, val downloaded: Int, val total: Int
        ) : DownloadEvent
    }

    internal fun dispatchEvent(event: DownloadEvent) {
        when (event) {
            is DownloadEvent.OnGetPages -> {
                val info = event.taskInfo
                info.total = event.pages
                val list = repo.getInfoListForLabel(info.label)
                if (list != null) {
                    eventBus.forEachListener { it.onUpdate(info, list, waitList) }
                }
            }
            is DownloadEvent.OnGet509 -> {
                eventBus.getDownloadListener()?.onGet509()
            }
            is DownloadEvent.OnPageDownload -> {
                speedTracker.onDownload(event.index, event.contentLength, event.receivedSize, event.bytesRead)
            }
            is DownloadEvent.OnPageSuccess -> {
                speedTracker.onDone(event.index)
                val info = event.taskInfo
                info.finished = event.finished
                info.downloaded = event.downloaded
                info.total = event.total
                eventBus.getDownloadListener()?.onGetPage(info)
                val list = repo.getInfoListForLabel(info.label)
                if (list != null) {
                    eventBus.forEachListener { it.onUpdate(info, list, waitList) }
                }
            }
            is DownloadEvent.OnPageFailure -> {
                speedTracker.onDone(event.index)
                val info = event.taskInfo
                info.finished = event.finished
                info.downloaded = event.downloaded
                info.total = event.total
                val list = repo.getInfoListForLabel(info.label)
                if (list != null) {
                    eventBus.forEachListener { it.onUpdate(info, list, waitList) }
                }
            }
            is DownloadEvent.OnFinish -> {
                speedTracker.onFinish()
                val info = event.taskInfo
                activeTasks.remove(info)
                activeWorkers.remove(info)
                if (activeTasks.isEmpty()) speedTracker.stop()
                info.finished = event.finished
                info.downloaded = event.downloaded
                info.total = event.total
                info.legacy = event.total - event.finished
                if (info.legacy == 0) {
                    info.state = DownloadInfo.STATE_FINISH
                } else {
                    info.state = DownloadInfo.STATE_FAILED
                }
                repo.persistInfo(info)
                eventBus.getDownloadListener()?.onFinish(info)
                val list = repo.getInfoListForLabel(info.label)
                if (list != null) {
                    eventBus.forEachListener { it.onUpdate(info, list, waitList) }
                }
                ensureDownload()
            }
        }
    }

    private fun postEvent(event: DownloadEvent) {
        eventBus.postToMain { dispatchEvent(event) }
    }

    internal inner class PerTaskListener(private val info: DownloadInfo) : SpiderQueen.OnSpiderListener {
        override fun onGetPages(pages: Int) = postEvent(DownloadEvent.OnGetPages(info, pages))
        override fun onGet509(index: Int) = postEvent(DownloadEvent.OnGet509)
        override fun onPageDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) =
            postEvent(DownloadEvent.OnPageDownload(index, contentLength, receivedSize, bytesRead))
        override fun onPageSuccess(index: Int, finished: Int, downloaded: Int, total: Int) =
            postEvent(DownloadEvent.OnPageSuccess(info, index, finished, downloaded, total))
        override fun onPageFailure(index: Int, error: String, finished: Int, downloaded: Int, total: Int) =
            postEvent(DownloadEvent.OnPageFailure(info, index, error, finished, downloaded, total))
        override fun onFinish(finished: Int, downloaded: Int, total: Int) =
            postEvent(DownloadEvent.OnFinish(info, finished, downloaded, total))
        override fun onGetImageSuccess(index: Int, image: Image) {}
        override fun onGetImageFailure(index: Int, error: String) {}
    }
}
```

- [ ] **Step 3.2: 编写 DownloadSchedulerTest**

```kotlin
// app/src/test/java/com/hippo/ehviewer/download/DownloadSchedulerTest.kt
package com.hippo.ehviewer.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.ServiceRegistry
import com.hippo.ehviewer.Settings
import com.lanraragi.reader.client.api.LRRAuthManager
import com.hippo.ehviewer.dao.AppDatabase
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.module.CoroutineModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DownloadSchedulerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var testScope: CoroutineScope
    private lateinit var repo: DownloadRepository
    private lateinit var eventBus: DownloadEventBus
    private lateinit var speedTracker: DownloadSpeedTracker
    private lateinit var scheduler: DownloadScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Settings.initialize(context)
        ServiceRegistry.initializeForTest(CoroutineModule())
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        LRRAuthManager.initialize(context)
        val method = LRRAuthManager::class.java.declaredMethods.first {
            it.name.startsWith("initializeForTesting") &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == android.content.SharedPreferences::class.java
        }
        method.isAccessible = true
        method.invoke(null, context.getSharedPreferences("lrr_auth_test_sched", Context.MODE_PRIVATE))

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        val dbField = EhDB::class.java.getDeclaredField("sDatabase")
        dbField.isAccessible = true
        dbField.set(EhDB, db)

        LRRAuthManager.setServerUrl("http://localhost:3000")

        repo = DownloadRepository(context, testScope)
        repo.startLoading {}
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        eventBus = DownloadEventBus()
        speedTracker = DownloadSpeedTracker(object : DownloadSpeedTracker.Callback {
            override fun getFirstActiveTask(): DownloadInfo? = scheduler.activeTasks.firstOrNull()
            override fun getInfoListForLabel(label: String?): List<DownloadInfo>? = repo.getInfoListForLabel(label)
            override fun getDownloadListener(): DownloadListener? = eventBus.getDownloadListener()
            override fun getDownloadInfoListeners(): List<WeakReference<DownloadInfoListener>> = eventBus.getInfoListenerRefs()
            override fun getWaitList(): List<DownloadInfo> = scheduler.waitList
        })
        scheduler = DownloadScheduler(context, testScope, repo, eventBus, speedTracker)
    }

    @After
    fun tearDown() {
        testScope.cancel()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        db.close()
        LRRAuthManager.clear()
    }

    @Test
    fun isIdle_initiallyTrue() {
        assertTrue(scheduler.isIdle)
    }

    @Test
    fun waitList_addAndStop() {
        val info = DownloadInfo().apply {
            gid = 1L; token = "t1"; title = "Test"
            state = DownloadInfo.STATE_WAIT; time = System.currentTimeMillis()
        }
        repo.addInfo(info)
        scheduler.waitList.add(info)

        assertFalse(scheduler.isIdle)

        val stopped = scheduler.stopDownload(1L)
        assertNotNull(stopped)
        assertEquals(DownloadInfo.STATE_NONE, stopped!!.state)
        assertTrue(scheduler.waitList.isEmpty())
    }

    @Test
    fun stopAllDownload_clearsWaitList() {
        for (i in 1L..3L) {
            val info = DownloadInfo().apply {
                gid = i; token = "t$i"; title = "G$i"
                state = DownloadInfo.STATE_WAIT; time = System.currentTimeMillis() + i
            }
            repo.addInfo(info)
            scheduler.waitList.add(info)
        }
        assertEquals(3, scheduler.waitList.size)

        scheduler.stopAllDownload()
        assertTrue(scheduler.waitList.isEmpty())
    }

    @Test
    fun getNoneDownloadInfo_stopsActiveAndReturns() {
        val info = DownloadInfo().apply {
            gid = 10L; token = "t10"; title = "Active"
            state = DownloadInfo.STATE_WAIT; time = System.currentTimeMillis()
        }
        repo.addInfo(info)
        scheduler.waitList.add(info)

        val result = scheduler.getNoneDownloadInfo(10L)
        assertNotNull(result)
        assertEquals(DownloadInfo.STATE_NONE, result!!.state)
        assertTrue(scheduler.waitList.isEmpty())
    }

    @Test
    fun stopDownload_nonExistentReturnsNull() {
        assertNull(scheduler.stopDownload(9999L))
    }

    @Test
    fun stopRangeDownload_stopsMultiple() {
        for (i in 1L..5L) {
            val info = DownloadInfo().apply {
                gid = i; token = "t$i"; title = "G$i"
                state = DownloadInfo.STATE_WAIT; time = System.currentTimeMillis() + i
            }
            repo.addInfo(info)
            scheduler.waitList.add(info)
        }

        val gidList = com.hippo.lib.yorozuya.collect.LongList()
        gidList.add(2L)
        gidList.add(4L)
        scheduler.stopRangeDownload(gidList)

        assertEquals(3, scheduler.waitList.size)
        assertFalse(scheduler.waitList.any { it.gid == 2L })
        assertFalse(scheduler.waitList.any { it.gid == 4L })
    }

    @Test
    fun ensureDownload_promotesWaitToActive() {
        val info = DownloadInfo().apply {
            gid = 20L; token = "t20"; title = "Promote"
            state = DownloadInfo.STATE_WAIT; time = System.currentTimeMillis()
        }
        repo.addInfo(info)
        scheduler.waitList.add(info)

        scheduler.ensureDownload()

        assertTrue(scheduler.waitList.isEmpty())
        assertEquals(1, scheduler.activeTasks.size)
        assertEquals(DownloadInfo.STATE_DOWNLOAD, info.state)
    }

    @Test
    fun ensureDownload_respectsConcurrencyLimit() {
        // Default concurrent downloads is typically 3; add 5 to wait list
        for (i in 1L..5L) {
            val info = DownloadInfo().apply {
                gid = 100L + i; token = "t$i"; title = "G$i"
                state = DownloadInfo.STATE_WAIT; time = System.currentTimeMillis() + i
            }
            repo.addInfo(info)
            scheduler.waitList.add(info)
        }

        scheduler.ensureDownload()

        // Some moved to active, some remain in wait
        assertTrue(scheduler.activeTasks.isNotEmpty())
        val totalScheduled = scheduler.activeTasks.size + scheduler.waitList.size
        assertEquals(5, totalScheduled)
    }

    @Test
    fun stopCurrentDownload_stopsAllActive() {
        val info = DownloadInfo().apply {
            gid = 30L; token = "t30"; title = "Current"
            state = DownloadInfo.STATE_WAIT; time = System.currentTimeMillis()
        }
        repo.addInfo(info)
        scheduler.waitList.add(info)
        scheduler.ensureDownload()
        assertTrue(scheduler.activeTasks.isNotEmpty())

        scheduler.stopCurrentDownload()
        assertTrue(scheduler.activeTasks.isEmpty())
    }

    @Test
    fun dispatchEvent_onFinish_removesFromActive() {
        val info = DownloadInfo().apply {
            gid = 40L; token = "t40"; title = "Finish"
            state = DownloadInfo.STATE_DOWNLOAD; time = System.currentTimeMillis()
        }
        repo.addInfo(info)
        scheduler.activeTasks.add(info)

        scheduler.dispatchEvent(
            DownloadScheduler.DownloadEvent.OnFinish(info, finished = 10, downloaded = 10, total = 10)
        )

        assertTrue(scheduler.activeTasks.isEmpty())
        assertEquals(DownloadInfo.STATE_FINISH, info.state)
    }
}
```

- [ ] **Step 3.3: 运行测试验证 DownloadScheduler**

Run: `./gradlew app:testAppReleaseDebugUnitTest --tests "com.hippo.ehviewer.download.DownloadSchedulerTest"`
Expected: 10 tests PASS

- [ ] **Step 3.4: Commit**

```bash
git add app/src/main/java/com/hippo/ehviewer/download/DownloadScheduler.kt \
       app/src/test/java/com/hippo/ehviewer/download/DownloadSchedulerTest.kt
git commit -m "refactor: extract DownloadScheduler from DownloadManager

Move worker lifecycle (wait queue, active tasks, state machine),
DownloadEvent dispatch, and PerTaskListener into DownloadScheduler.
10 unit tests cover idle state, stop operations, concurrency limit,
and event dispatch.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: DownloadManager Facade 瘦身

**Files:**
- Modify: `app/src/main/java/com/hippo/ehviewer/download/DownloadManager.kt` (重写为 Facade)
- Modify: `app/src/main/java/com/hippo/ehviewer/download/DownloadSpeedTracker.kt` (Callback 适配)
- Modify: `app/src/test/java/com/hippo/ehviewer/download/DownloadManagerTest.kt` (验证 API 不变)

将 DownloadManager 改为 Facade：保留所有公共 API 签名不变，内部委派给 `DownloadRepository`、`DownloadScheduler`、`DownloadEventBus`。

### Step 4: 重写 DownloadManager 为 Facade

- [ ] **Step 4.1: 重写 DownloadManager.kt**

保留：
- 构造函数签名 `DownloadManager(context, scope)` + 新增 `eventBus` 参数
- 所有公共方法签名完全不变
- `companion object` 的 `TAG`, `DOWNLOAD_INFO_FILENAME`, `DOWNLOAD_INFO_HEADER`, `DATE_DESC_COMPARATOR`, `insertSorted`

删除：
- 所有直接声明的集合字段（委派给 `repo`）
- `mWaitList`, `mActiveTasks`, `mActiveWorkers`（委派给 `scheduler`）
- `mDownloadListener`, `mDownloadInfoListeners`（委派给 `eventBus`）
- `forEachListener`, `assertMainThread`, `runOnMainThread`（组件内部自有）
- `LoadedDownloadData`, `loadDataFromDb`, `publishLoadedData`（在 `repo` 内）
- `ensureDownload`, `stopDownloadInternal`, `stopCurrentDownloadInternal`, `stopRangeDownloadInternal`（在 `scheduler` 内）
- `DownloadEvent`, `dispatchEvent`, `postEvent`, `PerTaskListener`（在 `scheduler` 内）

每个公共方法体变为 1-5 行委派代码。目标：~300-350 行。

关键委派示例：

```kotlin
class DownloadManager(
    private val mContext: Context,
    private val scope: CoroutineScope = ServiceRegistry.coroutineModule.ioScope,
    internal val eventBus: DownloadEventBus = DownloadEventBus()
) {
    internal val repo = DownloadRepository(mContext, scope)
    internal val scheduler: DownloadScheduler

    private val mSpeedReminder: DownloadSpeedTracker

    init {
        mSpeedReminder = DownloadSpeedTracker(object : DownloadSpeedTracker.Callback {
            override fun getFirstActiveTask(): DownloadInfo? =
                scheduler.activeTasks.firstOrNull()
            override fun getInfoListForLabel(label: String?): List<DownloadInfo>? =
                repo.getInfoListForLabel(label)
            override fun getDownloadListener(): DownloadListener? =
                eventBus.getDownloadListener()
            override fun getDownloadInfoListeners(): List<WeakReference<DownloadInfoListener>> =
                eventBus.getInfoListenerRefs()
            override fun getWaitList(): List<DownloadInfo> =
                scheduler.waitList
        })

        scheduler = DownloadScheduler(mContext, scope, repo, eventBus, mSpeedReminder)

        repo.startLoading {
            eventBus.forEachListener { it.onReload() }
        }
    }

    // ─── Init ─────────────────────────────────────────────
    suspend fun awaitInitAsync(timeoutMs: Long = 10_000L) {
        if (repo.initialized) return
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "awaitInitAsync() must not be called on the main thread"
        }
        kotlinx.coroutines.withTimeout(timeoutMs) { repo.initDeferred.await() }
    }

    // ─── Query ────────────────────────────────────────────
    fun containLabel(label: String?) = repo.containLabel(label)
    fun containDownloadInfo(gid: Long) = repo.containDownloadInfo(gid)
    val labelList: List<DownloadLabel> get() = repo.labelList
    fun getLabelCount(label: String?) = repo.getLabelCount(label)
    val allDownloadInfoList: List<DownloadInfo> get() = repo.allInfoList
    val defaultDownloadInfoList: List<DownloadInfo> get() = repo.defaultInfoList
    fun getLabelDownloadInfoList(label: String?) = repo.labelToInfoMap[label]
    val downloadInfoList: List<GalleryInfo> get() = ArrayList(repo.allInfoList)
    fun getDownloadInfo(gid: Long) = repo.getDownloadInfo(gid)
    fun getDownloadState(gid: Long) = repo.getDownloadState(gid)
    val isIdle: Boolean get() = scheduler.isIdle

    // ─── Listener ─────────────────────────────────────────
    fun addDownloadInfoListener(l: DownloadInfoListener?) = eventBus.addDownloadInfoListener(l!!)
    fun removeDownloadInfoListener(l: DownloadInfoListener?) {
        if (l != null) eventBus.removeDownloadInfoListener(l)
    }
    fun setDownloadListener(l: DownloadListener?) = eventBus.setDownloadListener(l)

    // ─── Mutations (delegate to repo + scheduler + eventBus) ───
    fun replaceInfo(newInfo: DownloadInfo, oldInfo: DownloadInfo) {
        repo.replaceInfo(newInfo, oldInfo)
        eventBus.forEachListener { it.onReplace(newInfo, oldInfo) }
    }

    fun startDownload(galleryInfo: GalleryInfo, label: String?) {
        // [保留原逻辑，但调用 repo/scheduler/eventBus 方法]
        // ... (详见实际实施时逐行迁移)
    }

    // ... 其余公共方法同理，每个方法体 1-10 行委派代码
```

- [ ] **Step 4.2: 运行全部 DownloadManagerTest**

Run: `./gradlew app:testAppReleaseDebugUnitTest --tests "com.hippo.ehviewer.download.DownloadManagerTest"`
Expected: 全部 29 个现有测试 PASS — 公共 API 未变

- [ ] **Step 4.3: 运行全部测试**

Run: `./gradlew app:testAppReleaseDebugUnitTest`
Expected: 全部测试 PASS

- [ ] **Step 4.4: 验证行数**

Run: `wc -l app/src/main/java/com/hippo/ehviewer/download/DownloadManager.kt`
Expected: < 400 行

- [ ] **Step 4.5: 编译验证**

Run: `./gradlew :app:assembleAppReleaseDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4.6: Commit**

```bash
git add app/src/main/java/com/hippo/ehviewer/download/DownloadManager.kt \
       app/src/main/java/com/hippo/ehviewer/download/DownloadSpeedTracker.kt \
       app/src/test/java/com/hippo/ehviewer/download/DownloadManagerTest.kt
git commit -m "refactor: slim DownloadManager to facade over Repository/Scheduler/EventBus

DownloadManager now delegates to three focused components:
- DownloadRepository: collection management + DB persistence
- DownloadScheduler: worker lifecycle + state machine
- DownloadEventBus: listener registration + event dispatch

All 29 existing DownloadManagerTest tests pass unchanged.
DownloadManager reduced from ~1448 to ~350 lines.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: DownloadsScene DownloadInfoListener 移入 ViewModel（W6-4）

**Files:**
- Modify: `app/src/main/java/com/hippo/ehviewer/ui/scene/download/DownloadsViewModel.kt`
- Modify: `app/src/main/java/com/hippo/ehviewer/ui/scene/download/DownloadsScene.kt`

将 `DownloadsScene` 的 `DownloadInfoListener` 实现完全移入 `DownloadsViewModel`，Scene 通过 StateFlow/SharedFlow 观察变化。

### Step 5: 移动 DownloadInfoListener 到 ViewModel

- [ ] **Step 5.1: 在 DownloadsViewModel 中实现 DownloadInfoListener**

在 `DownloadsViewModel` 中：

1. 实现 `DownloadInfoListener` 接口
2. 在 `init {}` 中注册自己到 `downloadManager.addDownloadInfoListener(this)`
3. 在 `onCleared()` 中解注册
4. 将 Scene 中的 9 个回调方法逻辑移入 ViewModel
5. 新增用于通知 Scene 刷新 UI 的 SharedFlow 事件

```kotlin
// 在 DownloadsViewModel 中添加

class DownloadsViewModel : ViewModel(), DownloadInfoListener {

    // ... 保留现有代码 ...

    // ─── DownloadInfoListener events → UI ───────────────────

    /** Emitted when a single item is added. */
    private val _onItemAdded = MutableSharedFlow<Pair<Int, DownloadInfo>>(extraBufferCapacity = 1)
    val onItemAdded: SharedFlow<Pair<Int, DownloadInfo>> = _onItemAdded.asSharedFlow()

    /** Emitted when a single item is removed. */
    private val _onItemRemoved = MutableSharedFlow<Pair<Int, DownloadInfo>>(extraBufferCapacity = 1)
    val onItemRemoved: SharedFlow<Pair<Int, DownloadInfo>> = _onItemRemoved.asSharedFlow()

    /** Emitted when a single item is updated. */
    private val _onItemUpdated = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val onItemUpdated: SharedFlow<Int> = _onItemUpdated.asSharedFlow()

    /** Emitted when data changes structurally (reload/updateAll). Carries a snapshot. */
    private val _onDiffUpdate = MutableSharedFlow<List<DownloadInfo>>(extraBufferCapacity = 1)
    val onDiffUpdate: SharedFlow<List<DownloadInfo>> = _onDiffUpdate.asSharedFlow()

    /** Emitted when labels need UI refresh. */
    private val _onLabelsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onLabelsChanged: SharedFlow<Unit> = _onLabelsChanged.asSharedFlow()

    /** Emitted when a replace happens. */
    private val _onReplaced = MutableSharedFlow<DownloadInfo>(extraBufferCapacity = 1)
    val onReplaced: SharedFlow<DownloadInfo> = _onReplaced.asSharedFlow()

    /** Emitted when current label was renamed. */
    private val _onLabelRenamed = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 1)
    val onLabelRenamed: SharedFlow<Pair<String, String>> = _onLabelRenamed.asSharedFlow()

    /** Emitted when label was deleted (onChange). */
    private val _onLabelDeleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onLabelDeleted: SharedFlow<Unit> = _onLabelDeleted.asSharedFlow()

    init {
        downloadManager.addDownloadInfoListener(this)
    }

    override fun onCleared() {
        super.onCleared()
        downloadManager.removeDownloadInfoListener(this)
    }

    // ─── DownloadInfoListener implementation ──────────────────

    override fun onAdd(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
        if (_downloadList.value !== list) return
        _onItemAdded.tryEmit(Pair(position, info))
    }

    override fun onReplace(newInfo: DownloadInfo, oldInfo: DownloadInfo) {
        if (_downloadList.value.isEmpty()) return
        updateForLabel()
        val index = _downloadList.value.indexOf(newInfo)
        _onReplaced.tryEmit(newInfo)
    }

    override fun onUpdate(info: DownloadInfo, list: List<DownloadInfo>, mWaitList: List<DownloadInfo>) {
        val currentList = _downloadList.value
        if (currentList !== list && !currentList.contains(info)) return
        val index = currentList.indexOf(info)
        if (index >= 0) {
            _onItemUpdated.tryEmit(index)
        }
    }

    override fun onUpdateAll() {
        val list = _downloadList.value
        _onDiffUpdate.tryEmit(ArrayList(list))
    }

    override fun onReload() {
        val list = _downloadList.value
        _onDiffUpdate.tryEmit(ArrayList(list))
    }

    override fun onChange() {
        resetToDefaultLabel()
        _onLabelDeleted.tryEmit(Unit)
    }

    override fun onRenameLabel(from: String, to: String) {
        if (_currentLabel.value != from) return
        onLabelRenamed(from, to)
        _onLabelRenamed.tryEmit(Pair(from, to))
    }

    override fun onRemove(info: DownloadInfo, list: List<DownloadInfo>, position: Int) {
        if (_downloadList.value !== list) return
        _onItemRemoved.tryEmit(Pair(position, info))
    }

    override fun onUpdateLabels() {
        _onLabelsChanged.tryEmit(Unit)
    }
```

- [ ] **Step 5.2: 在 DownloadsScene 中移除 DownloadInfoListener 实现**

在 `DownloadsScene` 中：

1. 类声明移除 `DownloadInfoListener` 接口
2. 删除 `_downloadManager` 字段和所有直接引用
3. 删除 `onCreate` 中的 `addDownloadInfoListener(this)`
4. 删除 `onDestroy` 中的 `removeDownloadInfoListener(this)`
5. 删除 9 个 `override fun onXxx()` 回调方法
6. 删除 `getMDownloadManager()` 方法
7. 在 `onViewCreated` 中添加 SharedFlow 收集：

```kotlin
// 在 onViewCreated 中添加

// Observe DownloadInfoListener events from ViewModel
collectFlow(viewLifecycleOwner, viewModel.onItemAdded) { (position, _) ->
    mAdapter?.notifyItemInserted(position)
    downloadLabelDraw?.updateDownloadLabels()
    updateView()
}

collectFlow(viewLifecycleOwner, viewModel.onItemRemoved) { (position, _) ->
    mAdapter?.notifyItemRemoved(listIndexInPage(position))
    updateView()
}

collectFlow(viewLifecycleOwner, viewModel.onItemUpdated) { index ->
    if (index >= 0 && mAdapter != null) {
        mAdapter!!.notifyItemChanged(listIndexInPage(index))
    }
}

collectFlow(viewLifecycleOwner, viewModel.onDiffUpdate) { newList ->
    if (mAdapter == null) return@collectFlow
    val result = DiffUtil.calculateDiff(
        DownloadInfoDiffCallback(mLastSnapshot, newList)
    )
    mLastSnapshot = ArrayList(newList)
    result.dispatchUpdatesTo(mAdapter!!)
    updateView()
}

collectFlow(viewLifecycleOwner, viewModel.onReplaced) { newInfo ->
    updateForLabel()
    updateView()
    val index = mList?.indexOf(newInfo) ?: -1
    if (index >= 0 && mAdapter != null) {
        mAdapter!!.notifyItemChanged(listIndexInPage(index))
    }
}

collectFlow(viewLifecycleOwner, viewModel.onLabelRenamed) { (_, _) ->
    updateForLabel()
    updateView()
}

collectFlow(viewLifecycleOwner, viewModel.onLabelDeleted) {
    updateForLabel()
    updateView()
}

collectFlow(viewLifecycleOwner, viewModel.onLabelsChanged) {
    downloadLabelDraw?.updateDownloadLabels()
}
```

8. 更新 `DownloadAdapterCallback` 接口中 `downloadManager` 的实现：从 `_downloadManager` 改为 `viewModel.downloadManager`。

9. 更新所有直接引用 `_downloadManager` 的地方（`stopAllDownload`, `resetAllReadingProgress`, `handleArguments` 等）改为 `viewModel.downloadManager`。

- [ ] **Step 5.3: 编译验证**

Run: `./gradlew :app:assembleAppReleaseDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5.4: 运行全部测试**

Run: `./gradlew app:testAppReleaseDebugUnitTest`
Expected: 全部测试 PASS

- [ ] **Step 5.5: 验证 DownloadsScene 行数**

Run: `wc -l app/src/main/java/com/hippo/ehviewer/ui/scene/download/DownloadsScene.kt`
Expected: < 1300 行（移除 9 个回调 + _downloadManager 字段及相关代码 ~150 行）

- [ ] **Step 5.6: 验证 Scene 不再 import DownloadInfoListener**

Run: `grep -n 'DownloadInfoListener' app/src/main/java/com/hippo/ehviewer/ui/scene/download/DownloadsScene.kt`
Expected: 0 匹配

- [ ] **Step 5.7: Commit**

```bash
git add app/src/main/java/com/hippo/ehviewer/ui/scene/download/DownloadsScene.kt \
       app/src/main/java/com/hippo/ehviewer/ui/scene/download/DownloadsViewModel.kt
git commit -m "refactor: move DownloadInfoListener from DownloadsScene into DownloadsViewModel

DownloadsScene no longer implements DownloadInfoListener or holds a
direct DownloadManager reference. ViewModel registers/unregisters the
listener in init/onCleared, and dispatches events to Scene via
SharedFlow. Scene observes flows in onViewCreated using collectFlow.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

## 验收检查清单

完成全部 5 个 Task 后，执行以下最终验证：

- [ ] `wc -l app/src/main/java/com/hippo/ehviewer/download/DownloadManager.kt` → < 400 行
- [ ] `wc -l app/src/main/java/com/hippo/ehviewer/ui/scene/download/DownloadsScene.kt` → < 1300 行
- [ ] `grep -rn 'DownloadInfoListener' app/src/main/java/com/hippo/ehviewer/ui/scene/download/DownloadsScene.kt` → 0 匹配
- [ ] `grep -rn '_downloadManager' app/src/main/java/com/hippo/ehviewer/ui/scene/download/DownloadsScene.kt` → 0 匹配
- [ ] `./gradlew app:testAppReleaseDebugUnitTest` → 全绿（29 + 8 + 12 + 10 = 59+ 测试）
- [ ] `./gradlew :app:assembleAppReleaseDebug` → BUILD SUCCESSFUL
- [ ] `./gradlew detekt` → 无新增 violation
- [ ] 冒烟测试：下载列表浏览、搜索、拖拽排序、批量删除、label 切换、下载进度更新全部正常

---

## 风险点和注意事项

1. **DownloadSpeedTracker.Callback 适配**：SpeedTracker 通过 Callback 接口访问 DownloadManager 内部状态。拆分后 Callback 的实现需要从 `scheduler.activeTasks`、`repo.getInfoListForLabel()`、`eventBus.getDownloadListener()` 等处获取数据。Task 4 的布线阶段必须正确连接。

2. **assertMainThread 重复**：三个组件各自有 `assertMainThread()`。这是有意的——每个组件独立保护自己的线程安全约束，不依赖外部调用者保证。

3. **DownloadManager 测试依赖 Robolectric ShadowLooper**：现有 DownloadManagerTest 使用 `ShadowLooper.idleMainLooper()` 驱动 Handler.post 回调。拆分后 EventBus 的 `postToMain` 仍走 Handler，测试模式不变。

4. **DownloadAdapter 和 DownloadLabelDraw 对 DownloadManager 的引用**：这两个类通过 `DownloadAdapterCallback.downloadManager` 访问 DownloadManager。Task 5 需要确保这些引用改为从 ViewModel 获取，不再走 Scene 的 `_downloadManager`。

5. **内存语义**: `forEachListener` 的 `inline` 修饰符在 EventBus 中保留——对性能友好（避免 lambda 对象分配）。

6. **notifyDataSetChanged 在 DownloadsScene**：Task 5 不消除 Scene 中残留的 `notifyDataSetChanged`（如 `updateForLabel`、`listChanged` flow）。那些属于 W6-4 的"搜索/排序结果全量刷新"场景，保持不变——审计 N4 建议的 DiffUtil 替换已在 W5-1 完成了 GalleryListScene 部分，DownloadsScene 的 DiffUtil 替换需要更细致的数据源分析，不在本次 scope 内。
