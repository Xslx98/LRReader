# Error UI 改进设计文档

**日期：** 2026-04-03  
**状态：** 已批准

---

## 背景

app 在遇到服务器错误（404、503 等）时，`ensureSuccess()` 会将 HTTP 响应体（可能是原始 HTML）拼接进异常消息，最终通过 Toast 或 tip TextView 暴露给用户。目标是：

1. 彻底截断 HTML 内容进入错误链路
2. 在列表场景中以风格统一（图标 + 友好文字）的视图替代纯文本提示

---

## 设计原则

- **最小改动**：不重构整体错误处理架构，不改动阅读器 GL 渲染层
- **风格统一**：遵循现有 Material Components 主题、颜色、尺寸规范
- **面向用户**：错误信息对普通用户可读，不暴露技术细节或 HTML

---

## Layer 1 — 根源修复

**文件：** `app/src/main/java/com/hippo/ehviewer/client/lrr/LRRApiUtils.kt`

**当前问题（`ensureSuccess()`，约第 34–52 行）：**

```kotlin
val errorBody = try {
    response.body?.string() ?: ""
} catch (_: Exception) { "" }

throw IOException(friendlyMsg + if (errorBody.isEmpty()) "" else ": $errorBody")
```

`errorBody` 在服务器返回 HTML 错误页时直接被拼入异常消息。

**修改方案：**

移除 `errorBody` 的读取与拼接，仅抛出 `friendlyMsg`：

```kotlin
internal fun ensureSuccess(response: Response) {
    if (!response.isSuccessful) {
        val friendlyMsg = when (response.code) {
            401, 403 -> "认证失败，请检查 API Key 是否正确"
            404      -> "资源未找到 (404)"
            in 500..503 -> "服务器错误 (${response.code})，请稍后重试"
            else     -> "请求失败 (HTTP ${response.code})"
        }
        throw IOException(friendlyMsg)
    }
}
```

**影响范围：** 所有调用 `ensureSuccess()` 的 API 方法（`LRRArchiveApi`、`LRRSearchApi`、`LRRCategoryApi` 等）自动受益，无需逐一修改。

---

## Layer 2 — 可复用错误视图

### 2a. 新建 layout：`view_error_state.xml`

**路径：** `app/src/main/res/layout/view_error_state.xml`

结构（竖向居中 LinearLayout）：

| 元素 | 规范 |
|---|---|
| 图标 ImageView | `v_alert_red_x48`，48×48dp，居中，`android:id="@+id/error_icon"` |
| 标题 TextView | `@dimen/text_little_small`（16sp），`?android:attr/textColorPrimary`，`android:id="@+id/error_title"` |
| 副文本 TextView | `@dimen/text_small`（14sp），`?android:attr/textColorSecondary`，`android:id="@+id/error_message"`，可选显示 |
| 重试 Button | `style="@style/ButtonInCard"`，`android:textColor="?attr/colorPrimary"`，`android:id="@+id/error_retry"`，`android:visibility="gone"`（默认隐藏） |

背景透明，间距：图标与标题 12dp，标题与副文本 4dp，副文本与按钮 16dp，水平 padding 24dp。

### 2b. 修改 `scene_lrr_categories.xml`

将现有：

```xml
<TextView android:id="@+id/tip" ... />
```

替换为：

```xml
<include
    android:id="@+id/error_view"
    layout="@layout/view_error_state"
    android:visibility="gone" />
```

### 2c. 修改 `LRRCategoriesScene.java`

新增两个状态方法，替换现有 `showTip(String)`：

```java
// 错误状态：红色图标 + 标题 + 重试按钮
private void showError(String message) {
    mProgress.setVisibility(View.GONE);
    mRecyclerView.setVisibility(View.GONE);
    mErrorView.setVisibility(View.VISIBLE);
    mErrorTitle.setText(R.string.lrr_error_title);   // "出错了"
    mErrorMessage.setText(message);
    mErrorMessage.setVisibility(View.VISIBLE);
    mErrorRetry.setVisibility(View.VISIBLE);
    mErrorRetry.setOnClickListener(v -> fetchCategories());
}

// 空状态：隐藏图标和按钮，仅显示提示文字
private void showEmpty(String message) {
    mProgress.setVisibility(View.GONE);
    mRecyclerView.setVisibility(View.GONE);
    mErrorView.setVisibility(View.VISIBLE);
    mErrorTitle.setText(message);
    mErrorMessage.setVisibility(View.GONE);
    mErrorRetry.setVisibility(View.GONE);
    // 图标也隐藏（空状态不需要警告色）
    mErrorIcon.setVisibility(View.GONE);
}
```

调用点替换：
- `showTip(getString(R.string.lrr_categories_error, e.getMessage()))` → `showError(LRRApiUtilsKt.friendlyError(e))`
- `showTip(getString(R.string.lrr_categories_empty))` → `showEmpty(getString(R.string.lrr_categories_empty))`

---

## 不在本次范围内

- 阅读器 GL 渲染层（`GalleryView` / `StringTexture`）：Layer 1 修复后消息内容已干净，维持现有渲染方式
- `GalleryListScene` 的 `ContentLayout` 错误状态：现有逻辑不直接显示异常 message，暂不修改
- Toast 错误提示的样式：Toast 本身由系统渲染，不在统一改动范围内

---

## 新增字符串资源

```xml
<string name="lrr_error_title">出错了</string>
```

现有字符串 `lrr_categories_empty` 继续沿用。

---

## 验证标准

1. 服务器返回 404/503 时，Categories 场景显示红色图标 + "出错了" + 友好描述 + "重试"按钮
2. 点击重试按钮重新触发 `fetchCategories()`
3. 空分类列表时显示纯文字提示（无图标、无按钮）
4. 任何场景下用户均看不到 HTML 标签或原始响应体
5. 浅色/深色/纯黑三套主题下颜色正确（依赖 `?attr/` 动态属性，无需额外处理）
