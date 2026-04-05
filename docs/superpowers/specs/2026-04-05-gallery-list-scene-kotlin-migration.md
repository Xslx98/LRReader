# GalleryListScene Kotlin Migration Design

## Current State

`GalleryListScene.java` — **2162 lines**, the largest file in the project. Main gallery browsing scene.

### Structure Map

```
GalleryListScene extends BaseScene (lines 138-2162)
├── Constants + Fields (138-240)
│   - State annotation (@IntDef: NORMAL, SIMPLE_SEARCH, SEARCH, SEARCH_SHOW_LIST)
│   - 30+ view fields (@Nullable, nulled in onDestroyView)
│   - Helper references (mUploadHelper, mSearchHelper)
│   - Navigation state (mHasFirstRefresh, mUrlBuilder, etc.)
│
├── Lifecycle (301-460)
│   - onGetLaunchMode, handleArgs, onNewArguments
│   - onCreate, onInit, onRestore, onSaveInstanceState
│   - onDestroy, onCreateView (513-700, massive view inflation)
│
├── View Setup (700-860)
│   - FAB layout + click handlers
│   - SearchBar + SearchLayout wiring
│   - RecyclerView + adapter setup
│
├── Navigation + State (860-1200)
│   - navToDetail, showQuickSearchTipDialog, showAddQuickSearchDialog
│   - onItemClick, onItemLongClick (context menu)
│   - Category/filter apply
│
├── FAB Actions (1200-1600)
│   - goTo dialog, LRR category filter
│   - selectActionFab, setState animation
│
├── SearchBar Callbacks (1680-1860)
│   - onClickTitle, onApplySearch, onChangeSearchMode
│   - onSortChanged, onSelectImage
│
├── API Callbacks (1888-1930)
│   - onGetGalleryListSuccess, onGetLRRSearchSuccess, onGetGalleryListFailure
│
├── Inner: GalleryListAdapter (1931-1949)
│   - extends GalleryAdapterNew
│
├── Inner: GalleryListHelper (1951-2074)
│   - extends GalleryInfoContentHelper
│   - getPageData — THE key pagination method (calls LRR search API)
│
└── Inner: EhCallback listeners (2076-2162)
    - GetGalleryListListener, AddToFavoriteListener, RemoveFromFavoriteListener
```

### External Dependencies (14 files reference GalleryListScene)

| File | How it references |
|---|---|
| `MainActivity.java` | `registerLaunchMode(GalleryListScene.class)`, navigation target |
| `GalleryDetailScene.java` | `startScene(GalleryListScene.class)` for tag/uploader search |
| `ServerListScene.kt` | Navigation after server switch |
| `ServerConfigScene.java` | Navigation after server setup |
| `LRRCategoriesScene.kt` | Navigation after category select |
| `BookmarksDraw.java` | References `mHelper`, `mUrlBuilder` for quick search |
| `SubscriptionDraw.java` | Same pattern |
| `GallerySearchHelper.java` | References via Callback interface |
| `GalleryUploadHelper.java` | References via Callback interface |
| `GalleryListSceneDialog.kt` | Helper dialog |
| `SubscriptionsScene.java` | Navigation |
| `SolidScene.java` | Navigation |
| `AppearanceSettings.kt` | `GalleryListScene.KEY_ACTION` constant |
| `GalleryListViewModel.kt` | Standalone (no direct reference) |

### Key Risks

1. **Size** — 2162 lines is the largest Kotlin conversion we've done (DownloadManager was 1355)
2. **BaseScene Java superclass** — Scene lifecycle methods (`onCreateView2`, `startScene`, `popScene`) are Java
3. **Inner classes** — `GalleryListAdapter`, `GalleryListHelper`, 3 EhCallback subclasses — all reference outer class state
4. **ContentHelper inheritance** — `GalleryInfoContentHelper` extends `ContentLayout.ContentHelper` (Java framework, stays Java)
5. **14 external files** reference this class — breakage has wide blast radius
6. **Paging 3 integration** — design says to integrate after Kotlin conversion, so this conversion is a prerequisite

---

## Migration Strategy: 3-Phase Approach

### Phase 1: Extract one more helper, reduce to ~1900 lines (1 commit)

Extract the **FAB action handlers + category filter dialog** (~200 lines, 1200-1450) into `GalleryFabHelper.java`. This reduces risk by making the conversion smaller.

Methods to extract:
- `showGoToDialog()` + `GoToDialogHelper` inner class usage
- `showLRRCategoryFilterDialog()`
- `selectActionFab(boolean animation)`
- Related FAB click dispatch logic in the `switch(position)` block

### Phase 2: Convert GalleryListScene to Kotlin (1 commit, the big one)

Mechanical J2K conversion + manual cleanup:

1. Run Android Studio's J2K converter as baseline
2. Manual fixes:
   - `@State` IntDef → Kotlin `companion object` constants
   - `@Nullable` view fields → `var fieldName: Type? = null`
   - Inner classes → `inner class` / `private class`
   - `GalleryListHelper` keeps `GalleryInfoContentHelper` Java superclass
   - All `@JvmField` on fields accessed by Java callers (BookmarksDraw, SubscriptionDraw, GallerySearchHelper, GalleryUploadHelper)
   - `companion object` with `@JvmStatic` for `startScene()`, constants
3. Preserve all public API for the 14 external callers

**Important interop points:**
- `mHelper` field accessed by `BookmarksDraw.java` → `@JvmField`
- `mUrlBuilder` field accessed by `BookmarksDraw.java` → `@JvmField`
- `KEY_ACTION`, `ACTION_HOMEPAGE` etc. → `companion object` `const val`
- `startScene(SceneFragment, ListUrlBuilder)` → `@JvmStatic`
- `setState(int)` called by `BookmarksDraw` → public method, keep accessible

### Phase 3: Integrate Paging 3 (1 commit)

After Kotlin conversion:
1. Get `GalleryListViewModel` via `ViewModelProvider`
2. Replace `GalleryListHelper.getPageData()` with `viewModel.galleryFlow.collectLatest`
3. Replace `GalleryListAdapter` (extends `GalleryAdapterNew`) with a `PagingDataAdapter`
4. Remove `ContentHelper` dependency for this Scene

This is the highest-risk phase — defer if not stable.

---

## Detailed Phase 2 Conversion Notes

### Fields pattern
```kotlin
// Java:
@Nullable private RecyclerView mRecyclerView;
// Kotlin:
private var mRecyclerView: RecyclerView? = null
```

### Inner class: GalleryListAdapter
```kotlin
// Currently extends GalleryAdapterNew (Java)
// Keep as inner class — accesses mHelper, mUrlBuilder from outer
private inner class GalleryListAdapter(
    resources: Resources, recyclerView: RecyclerView, type: Int
) : GalleryAdapterNew(resources, recyclerView, type)
```

### Inner class: GalleryListHelper
```kotlin
// Extends GalleryInfoContentHelper which extends ContentHelper (Java)
// Keep as inner class
inner class GalleryListHelper : GalleryInfoContentHelper() {
    override fun getPageData(taskId: Int, type: Int, page: Int) { ... }
    // ...
}
```

### EhCallback inner classes
```kotlin
// These are static in Java → nested class (no inner) in Kotlin
private class GetGalleryListListener(context: Context, stageId: Int, sceneTag: String)
    : EhCallback<GalleryListScene, GalleryListParser.Result>(context, stageId, sceneTag)
```

### BookmarksDraw interop
```java
// BookmarksDraw.java accesses:
scene.mHelper.refresh()
scene.mUrlBuilder.set(...)
scene.onUpdateUrlBuilder()
scene.setState(GalleryListScene.STATE_NORMAL)
scene.closeDrawer(Gravity.RIGHT)
```
All these must remain accessible from Java → public + `@JvmField` where needed.

---

## Execution Plan

| Phase | Commit | Risk | Est. |
|---|---|---|---|
| 1 | Extract GalleryFabHelper | Low | 1 hr |
| 2 | J2K conversion | Medium-High | 3-4 hr |
| 3 | Paging 3 integration | High (optional) | 4-6 hr |

**Recommendation:** Do Phase 1 + 2. Defer Phase 3 until Phase 2 is stable.
