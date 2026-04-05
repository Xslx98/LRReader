# Tag Editor Redesign

## Goal
Replace the raw text tag editor with a grouped Chip-based editor, move the entry point to the tag display area, and add multi-language support.

## Changes

### 1. Entry Point

**Remove:** `action_lrr_edit_tags` from the overflow menu in `scene_gallery_detail.xml`.

**Add:** A pencil `ImageButton` in the top-right corner of the tag area layout (`gallery_detail_tags.xml`). Visible only when connected to LANraragi (`LRRAuthManager.getServerUrl() != null`). Click opens the Chip editor dialog.

Layout change in `gallery_detail_tags.xml`:
```xml
<LinearLayout id="@+id/tags" orientation="vertical">
    <FrameLayout>  <!-- new wrapper for title area -->
        <TextView id="@+id/no_tags" ... />
        <ImageButton id="@+id/edit_tags_btn"
            android:src="@drawable/ic_edit_24"
            android:layout_gravity="end"
            android:visibility="gone" />
    </FrameLayout>
    <!-- tag groups added dynamically below -->
</LinearLayout>
```

`GalleryDetailScene` sets `edit_tags_btn` visible when LRR is connected, and attaches the click listener.

### 2. Chip Editor Dialog

Uses the app's existing `AlertDialog.Builder` with a custom ScrollView content. **No new visual components** — reuses the same `RoundSideRectDrawable` + `AutoWrapLayout` + `item_gallery_tag.xml` tag style from the detail page, adding interactive affordances (✕ suffix for delete, click for edit).

**Layout structure:**
```
┌──────────────────────────────────────┐
│ Edit Tags            [Cancel] [Save] │
├──────────────────────────────────────┤
│ ScrollView                           │
│                                      │
│ ┌─────────┐                     [+] │
│ │ artist  │ ┌──────┐ ┌────────┐    │
│ └─────────┘ │name1✕│ │name2 ✕ │    │
│             └──────┘ └────────┘    │
│                                      │
│ ┌─────────┐                     [+] │
│ │ parody  │ ┌────────┐             │
│ └─────────┘ │series ✕│             │
│             └────────┘             │
│                                      │
│         [+ Add Namespace]            │
└──────────────────────────────────────┘
```

Tag visual style: identical to the detail page — `RoundSideRectDrawable(tagBackgroundColor)`, white text, 4dp margin. Namespace labels use `tagGroupBackgroundColor`. The `✕` is appended as text suffix (no new icon). This ensures the editor looks like an editable version of the display area.

**Data model (in-memory, not saved until "Save"):**
```kotlin
data class EditableTagGroup(
    var namespace: String,
    val tags: MutableList<String>
)
// Dialog state: MutableList<EditableTagGroup>
```

**Tag interactions:**
- **Click tag** → `AlertDialog` with `EditText` pre-filled with current tag text. OK replaces, Cancel keeps old.
- **Long-press tag** or **click ✕ suffix** → Remove tag from the group.
- **Click [+] on a namespace row** → `AlertDialog` with `AutoCompleteTextView` (using `LRRTagCache.suggest()` filtered to namespace). OK adds tag.
- **Click [+ Add Namespace]** → `AlertDialog` with `EditText` + common namespace suggestions (artist, parody, character, group, language, misc as hint text). OK creates new empty group.

**Save button:**
- Reconstructs the tag string: `namespace1:tag1, namespace1:tag2, namespace2:tag3, ...`
- Calls `LRRArchiveApi.updateMetadata()` on IO thread
- On success: dismisses dialog, refreshes gallery detail
- On failure: shows error toast, dialog stays open

### 3. Tag Input with Autocomplete

Add/edit dialogs use `AutoCompleteTextView` with `LRRTagCache.suggest()`. When editing within a specific namespace group, filter suggestions to that namespace. Standard Android dropdown — no custom component.

### 3.5 Visual Consistency

**Critical:** No new visual themes, components, or styles introduced. The editor reuses:
- `RoundSideRectDrawable` for tag backgrounds (same class used in detail page)
- `?attr/tagBackgroundColor` and `?attr/tagGroupBackgroundColor` from existing theme attrs
- `@style/CardMessage` text style from `item_gallery_tag.xml`
- `AutoWrapLayout` for tag flow (same as detail page)
- `AlertDialog.Builder` for all dialogs (same as rest of app)
- Existing vector drawables from the project (pencil icon: `v_pencil_dark_x24` or similar)

### 4. Multi-Language Support

Add translations for these strings across all 10 locale files:

| Key | en | zh-rCN | ja |
|-----|-----|--------|-----|
| `lrr_edit_tags` | Edit Tags | 编辑标签 | タグを編集 |
| `lrr_tags_updated` | Tags updated | 标签已更新 | タグが更新されました |
| `lrr_add_tag` | Add tag | 添加标签 | タグを追加 |
| `lrr_add_namespace` | Add namespace | 添加分类 | 名前空間を追加 |
| `lrr_edit_tag` | Edit tag | 编辑标签 | タグを編集 |
| `lrr_namespace_hint` | e.g. artist, parody | 如 artist, parody | 例: artist, parody |
| `lrr_tag_hint` | Tag name | 标签名称 | タグ名 |
| `lrr_save` | Save | 保存 | 保存 |
| `lrr_empty_namespace` | Namespace cannot be empty | 分类名不能为空 | 名前空間は空にできません |

Also translate for: zh-rHK, zh-rTW, ko, es, fr, de, th.

### 5. Files Changed

| File | Change |
|------|--------|
| `res/layout/gallery_detail_tags.xml` | Add FrameLayout wrapper + edit button |
| `res/layout/dialog_tag_editor.xml` | New: Chip editor dialog layout |
| `res/layout/item_tag_namespace_group.xml` | New: Per-namespace row (title + ChipGroup + add button) |
| `res/drawable/v_edit_dark_x24.xml` | New: Pencil vector icon (matching existing v_*_dark_x24 naming) |
| `res/menu/scene_gallery_detail.xml` | Remove `action_lrr_edit_tags` |
| `res/values/strings.xml` + 10 locales | Add/update strings |
| `TagEditDialog.kt` | Rewrite: Chip-based editor replacing raw EditText |
| `GalleryDetailScene.java` | Wire edit button, remove overflow menu item |
