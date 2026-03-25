# Edge-to-Edge 适配踩坑记录

> 背景：`androidx.activity` 从 1.2.4 升级到 1.10.1 后，所有 Activity 默认启用 edge-to-edge。
>
> **相关文档**：[onboard.md](onboard.md)（入门指南）| [PROJECT_TECHNICAL_DOC.md](PROJECT_TECHNICAL_DOC.md)（架构指南）

## 核心事实

| API 级别 | 行为 |
|----------|------|
| < 35 | `setStatusBarColor()` 有效，但 `activity:1.10.1` 的 compat 层仍会尝试启用 edge-to-edge |
| 35 | edge-to-edge 强制启用，`setStatusBarColor()` 是 **no-op** |
| 36 | `windowOptOutEdgeToEdgeEnforcement` 被彻底移除，**没有任何 opt-out 手段** |

> [!CAUTION]
> 在 API 35+ 上，以下方法全部无效：
> - `window.setStatusBarColor(color)` — no-op
> - 主题 XML 中的 `android:statusBarColor` — 被忽略
> - `EdgeToEdge.enable()` + `SystemBarStyle.dark(color)` — 只影响 API < 35
> - `WindowCompat.setDecorFitsSystemWindows(window, true)` — 不影响状态栏颜色

## 状态栏着色的正确做法

状态栏**永远透明**，颜色只能靠**布局内容在状态栏区域后面绘制**来实现。

### 方案 A：DrawerLayout（本项目主页面采用）

`DrawerLayout` + `fitsSystemWindows="true"` 内置了 `colorPrimaryDark` 遮罩绘制逻辑。

```xml
<DrawerLayout
    android:fitsSystemWindows="true"
    ...>
    <!-- DrawerLayout 自动在状态栏区域绘制 colorPrimaryDark 遮罩 -->
</DrawerLayout>
```

### 方案 B：根布局着色（本项目 Settings 页面采用）

根布局使用主题色背景 + `fitsSystemWindows="true"`，`fitsSystemWindows` 产生的顶部 padding 区域露出根布局的彩色背景。内层 content 用 `windowBackground` 覆盖。

```xml
<LinearLayout
    android:background="?attr/colorPrimary"
    android:fitsSystemWindows="true"
    android:orientation="vertical">

    <FrameLayout
        android:id="@+id/content"
        android:background="?android:attr/windowBackground"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />
</LinearLayout>
```

### 方案 C：CoordinatorLayout + AppBarLayout

`AppBarLayout` 天然支持延伸到状态栏后面，是 Material Design 推荐方案。

## 排查清单

新增 Activity 或修改现有 Activity 布局时，检查以下项：

- [ ] 状态栏区域是否有彩色内容填充（DrawerLayout / 根布局着色 / AppBarLayout）
- [ ] `fitsSystemWindows="true"` 是否设置在正确的层级
- [ ] 多主题（Light / Dark / Black）下状态栏颜色是否一致
- [ ] 导航栏区域是否需要类似处理

## 本次踩坑时间线

| 尝试 | 方法 | 结果 |
|------|------|------|
| 1 | `activity_settings.xml` 加 `fitsSystemWindows="true"` | ✅ 布局不再被遮挡，但状态栏变白 |
| 2 | `EhActivity.onPostCreate()` 中 `content.setFitsSystemWindows(true)` | ❌ 不影响状态栏颜色 |
| 3 | 主题 XML 加 `android:statusBarColor` | ❌ API 36 下被忽略 |
| 4 | `TypedValue` 解析 → `window.setStatusBarColor()` | ❌ API 36 下是 no-op |
| 5 | `WindowCompat.setDecorFitsSystemWindows(window, true)` | ❌ 不影响状态栏颜色 |
| 6 | `EdgeToEdge.enable()` + `SystemBarStyle.dark(color)` | ❌ API 36 下 scrim 不生效 |
| 7 | 根布局 `colorPrimary` 背景 + `fitsSystemWindows` | ✅ 绿色背景透过透明状态栏显示 |
