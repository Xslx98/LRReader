# Path A: Complete Modernization Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kotlin-migrate remaining ehviewer Java files, integrate Paging 3, and add test coverage — bringing Kotlin ratio from 42% to ~75% with zero UI/interaction changes.

**Architecture:** Four phases executed sequentially. Phase 1 (small batch conversions) builds confidence and momentum. Phase 2 tackles medium files. Phase 3 handles the three large Scenes with helper extraction + conversion. Phase 4 integrates Paging 3 and adds tests. Each phase produces a stable, releasable state.

**Tech Stack:** Kotlin 2.1, Room + Flow, Paging 3, Robolectric, JUnit 4

---

## Inventory

### 141 remaining Java files (26,810 lines)

| Category | Files | Lines | Plan |
|----------|-------|-------|------|
| Tiny (<100 lines) | ~50 | ~2,500 | Phase 1: batch convert |
| Small (100-400) | ~30 | ~7,000 | Phase 1: batch convert |
| Medium (400-800) | 10 | ~6,000 | Phase 2: individual convert |
| Large (>800) | 3 Scenes + MainActivity | ~6,400 | Phase 3: extract + convert |
| Framework stubs/parsers | ~10 | ~500 | Skip (E-Hentai legacy, rarely touched) |
| GalleryActivity (reader) | 1 | 737 | Phase 2 (standalone, no Scene deps) |

### Files to SKIP (keep as Java)
- `client/parser/*` — E-Hentai stub parsers, referenced by ContentHelper
- `client/exception/*` — tiny exception classes, no benefit from conversion
- `callBack/*` — tiny interfaces (6-14 lines each)
- `ui/annotation/*` — annotation definitions (35 lines each)
- `event/*` — tiny event classes

**~20 files skipped, ~120 files to convert.**

---

## Phase 1: Batch Convert Small Files (~80 files, ~9,500 lines)

### Task 1: Convert tiny utility/callback files (batch of ~15)

**Files (<60 lines each):**
- `GetText.java` (41)
- `ui/UConfigActivity.java` (21)
- `ui/scene/TransitionNameFactory.java` (36)
- `ui/scene/GalleryHolder.java` (54)
- `ui/scene/download/DownloadLabelItem.java` (20)
- `ui/scene/download/DownloadLabelAdapter.java` (55)
- `ui/scene/gallery/list/DrawViewPagerAdapter.java` (41)
- `ui/scene/gallery/list/EnterGalleryDetailTransaction.java` (58)
- `widget/EhStageLayout.java` (57)
- `widget/EhDrawerView.java` (57)
- `widget/TileThumb.java` (52)
- `widget/TileThumbNew.java` (52)
- `shortcuts/ShortcutsActivity.java` (44)
- `preference/VersionPreference.java` (57)
- `EhProxySelector.java` (34)

- [ ] Convert all files, delete .java, create .kt
- [ ] Build: `./gradlew :app:assembleAppReleaseDebug`
- [ ] Tests: `./gradlew app:testAppReleaseDebugUnitTest`
- [ ] Commit: `refactor: convert 15 tiny utility files to Kotlin`

### Task 2: Convert preference/fragment files (batch of ~10)

**Files (50-120 lines each):**
- `ui/fragment/BasePreference.java` (33)
- `ui/fragment/BasePreferenceFragmentCompat.java` (51)
- `ui/fragment/PrivacyFragment.java` (52)
- `ui/fragment/ReadFragment.java` (37)
- `ui/fragment/SettingsHeaders.java` (58)
- `preference/ClearDownloadPathCachePreference.java` (30)
- `ui/SettingsActivity.java` (96)
- `FavouriteStatusRouter.java` (80)
- `Hosts.java` (375)
- `EhProxySelector.java` — if not done in Task 1

- [ ] Convert, build, test, commit: `refactor: convert preference/fragment files to Kotlin`

### Task 3: Convert client/UI helpers (batch of ~10)

**Files:**
- `client/EhUrl.java` (321)
- `client/EhRequestBuilder.java` (58)
- `client/EhConfig.java` (~155)
- `client/data/PreviewSet.java` (34)
- `ui/GalleryOpenHelper.java` (~60)
- `ui/scene/ThumbSpanHelper.java` (272)
- `ui/scene/SolidScene.java` (~100)
- `ui/scene/ProgressScene.java` (267)
- `ui/scene/SecurityScene.java` (272)
- `ui/gallery/GallerySliderController.java` (307)

- [ ] Convert, build, test, commit: `refactor: convert client/UI helper files to Kotlin`

### Task 4: Convert download-related small files (batch of ~8)

**Files:**
- `download/DownloadInfoListener.java` (52)
- `download/DownloadListener.java` (52)
- `ui/scene/download/DownloadLabelAdapter.java` — if not done
- `ui/scene/download/DownloadImportHelper.java` (290)
- `ui/scene/download/DownloadLabelsScene.java` (394)
- `sync/DownloadListInfosExecutor.java` (450)
- `spider/SpiderInfo.java` (351)
- `spider/SpiderDen.java` (413)

- [ ] Convert, build, test, commit: `refactor: convert download-related files to Kotlin`

### Task 5: Convert gallery list helpers (batch of ~8)

**Files:**
- `ui/scene/gallery/list/GalleryAdapter.java` (282)
- `ui/scene/gallery/list/GalleryAdapterNew.java` (360)
- `ui/scene/gallery/list/GallerySearchHelper.java` (328)
- `ui/scene/gallery/list/GalleryUploadHelper.java` (232)
- `ui/scene/gallery/list/BookmarksDraw.java` (~140)
- `ui/scene/gallery/list/SubscriptionDraw.java` (327)
- `ui/scene/gallery/list/SubscriptionsScene.java` (342)
- `ui/scene/gallery/list/QuickSearchScene.java` (288)

- [ ] Convert, build, test, commit: `refactor: convert gallery list helpers to Kotlin`

### Task 6: Convert remaining small files (batch of ~15)

**Everything else <400 lines not yet converted:**
- `ui/BlackListActivity.java` (394)
- `ui/HostsActivity.java` (276)
- `ui/gallery/GalleryImageOperations.java` (339)
- `ui/scene/ServerConfigScene.java` (346)
- `spider/SpiderQueen.java` (91 — stub, constants only)
- `client/EhCookieStore.java` (~200)
- `client/EhHosts.java` — if not already Kotlin
- Any remaining misc files

- [ ] Convert, build, test, commit: `refactor: convert remaining small files to Kotlin`

**Phase 1 checkpoint:** ~80 files converted. Build + full test suite. Kotlin ratio should be ~65%.

---

## Phase 2: Convert Medium Files (400-800 lines)

### Task 7: Convert DownloadAdapter.java (778 lines)

The download list adapter with thumbnail extraction, drag-sort, archive handling.

- [ ] Read file fully to understand inner classes and Java callers
- [ ] Convert to Kotlin, `@JvmField` on fields accessed by Java DownloadsScene
- [ ] Build, test, commit: `refactor: convert DownloadAdapter to Kotlin`

### Task 8: Convert DownloadFragment.java (581 lines)

Download progress fragment with BroadcastReceiver.

- [ ] Convert, build, test, commit: `refactor: convert DownloadFragment to Kotlin`

### Task 9: Convert ArchiverDownloadDialog.java (494 lines)

Archive download dialog with DownloadManager integration.

- [ ] Convert, build, test, commit: `refactor: convert ArchiverDownloadDialog to Kotlin`

### Task 10: Convert GalleryActivity.java (737 lines)

The gallery reader activity. Creates GalleryProviders (already Kotlin). Contains GL surface setup.

- [ ] Convert, build, test, commit: `refactor: convert GalleryActivity to Kotlin`

### Task 11: Convert MainActivity.java (928 lines)

App entry point. Navigation drawer, scene routing, theme handling.
Must keep all `@JvmField`/`@JvmStatic` for Scene callers.

- [ ] Convert, build, test, commit: `refactor: convert MainActivity to Kotlin`

**Phase 2 checkpoint:** All medium files done. Kotlin ratio ~72%. Only 3 large Scenes remain as Java.

---

## Phase 3: Large Scene Migrations

### Task 12: Extract + Convert GalleryDetailScene (1486 lines)

**Step 12a: Extract GalleryDetailDownloadHelper (~200 lines)**
- Extract download-related methods: `onDownload()`, `updateDownloadText()`, `updateDownloadState()`, and `DownloadInfoListener` callbacks
- Use Callback interface pattern (same as GalleryUploadHelper)
- Commit: `refactor: extract GalleryDetailDownloadHelper`

**Step 12b: Convert GalleryDetailScene to Kotlin (~1280 lines)**
- J2K conversion of the remaining Scene
- `@JvmField` on fields accessed by Java framework (BaseScene)
- Inner classes: EhCallback subclasses → nested class
- Commit: `refactor: convert GalleryDetailScene to Kotlin`

### Task 13: Extract + Convert DownloadsScene (1887 lines)

**Step 13a: Extract DownloadBatchHelper (~200 lines)**
- Multi-select batch operations (delete, move label, etc.)
- Commit: `refactor: extract DownloadBatchHelper from DownloadsScene`

**Step 13b: Convert DownloadsScene to Kotlin (~1680 lines)**
- The DiffUtil and Room Flow subscription stay
- `@JvmField` for fields accessed by DownloadAdapter (if still Java)
- Commit: `refactor: convert DownloadsScene to Kotlin`

### Task 14: Extract + Convert GalleryListScene (2162 lines)

**Step 14a: Extract GalleryFabHelper (~200 lines)**
- FAB action handlers, category filter dialog, GoTo dialog
- Commit: `refactor: extract GalleryFabHelper from GalleryListScene`

**Step 14b: Convert GalleryListScene to Kotlin (~1960 lines)**
- Largest single conversion
- `@JvmField` on `mHelper`, `mUrlBuilder` (accessed by BookmarksDraw, SubscriptionDraw)
- `companion object` with `@JvmStatic` for `startScene()`, constants
- Inner classes: GalleryListAdapter, GalleryListHelper, EhCallback subclasses
- Commit: `refactor: convert GalleryListScene to Kotlin`

**Phase 3 checkpoint:** All Scenes converted. Kotlin ratio ~75%+. Only `com.hippo.*` framework (230 files) remains Java.

---

## Phase 4: Paging 3 Integration + Tests

### Task 15: Wire Paging 3 into GalleryListScene

Prerequisite: Task 14 (GalleryListScene is Kotlin).

- [ ] Get `GalleryListViewModel` via `ViewModelProvider` in `onCreate()`
- [ ] Create `GalleryPagingAdapter` extending `PagingDataAdapter<GalleryInfo, GalleryHolder>`
- [ ] In `GalleryListHelper.getPageData()`, instead of manual API call + append, collect from `viewModel.galleryFlow`
- [ ] Add `LoadStateAdapter` for footer loading spinner
- [ ] Keep `ContentHelper` as fallback for non-LRR modes (if any)
- [ ] Build, test, commit: `feat: integrate Paging 3 into GalleryListScene`

### Task 16: Add test coverage for critical paths

**16a: GalleryInfo Parcelable round-trip test**
- Create/parcelize/unparcelize, assert all fields match
- Commit: `test: add GalleryInfo Parcelable round-trip test`

**16b: TagEditDialog logic tests**
- `parseTagGroups()` → `EditableTagGroup` list
- `editableGroupsToString()` → correct format
- Commit: `test: add TagEditDialog parsing tests`

**16c: EhFilter logic tests**
- Title filtering, tag filtering, uploader filtering
- Commit: `test: add EhFilter logic tests`

**16d: LRRArchivePagingSource edge case tests**
- Sort parameters, new-only filter, empty query
- Commit: `test: add PagingSource edge case tests`

---

## Execution Order and Dependencies

```
Phase 1 (Tasks 1-6): Independent batches, parallelize where possible
    ↓
Phase 2 (Tasks 7-11): Sequential (some files reference each other)
    ↓
Phase 3 (Tasks 12-14): Sequential (each Scene extract → convert)
    ↓
Phase 4 (Tasks 15-16): Paging after Task 14, tests independent
```

## Risk Mitigation

- **Each task is one atomic commit** — revertable independently
- **Build + test after every commit** — no accumulated breakage
- **Phase checkpoints** — can stop after any phase and release
- **`@JvmField`/`@JvmStatic`** on every field/method accessed by remaining Java code
- **No UI changes** — pure backend refactoring, zero visual impact

## Expected Final State

| Metric | Before | After |
|--------|--------|-------|
| ehviewer Java files | 141 | ~20 (stubs/parsers/annotations) |
| ehviewer Kotlin files | 108 | ~230 |
| Kotlin ratio | 42% | ~75% |
| Test files | 36 | ~40 |
| Paging 3 | Infrastructure only | Integrated in gallery list |
| Three large Scenes | 5,535 lines Java | Kotlin + extracted helpers |
