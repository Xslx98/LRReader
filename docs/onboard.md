# LRReader 项目 Onboard 文档

> 最后更新：2026-03-25  
> 当前版本：v1.9.0 (versionCode 12)

## 文档导航

本文档是项目的入口指南。以下是所有文档及其关系：

```
README.md                          ← 项目主页（面向用户/GitHub）
├── CONTRIBUTING.md                ← 贡献指南 + 签名配置 + 构建命令
├── PRIVACY_POLICY.md              ← 隐私政策（Google Play 必需）
├── NOTICE                         ← 上游项目开源致谢
├── LICENSE                        ← GPLv3 全文
└── docs/
    ├── onboard.md                 ← ★ 本文档（开发者入门指南）
    ├── PROJECT_TECHNICAL_DOC.md   ← 项目架构指南（构建配置 + 架构图 + 核心系统）
    ├── PROJECT_TECHNICAL_SUMMARY.md ← 深度技术评估（适配器模式 + 安全 + 网络 + API）
    ├── EDGE_TO_EDGE.md            ← Edge-to-Edge 状态栏适配踩坑记录
    └── ROADMAP.md (gitignored)    ← 内部开发路线图（13 Phase 历史记录）
```

> [!TIP]
> 新开发者建议按此顺序阅读：**本文档 → CONTRIBUTING.md → PROJECT_TECHNICAL_DOC.md**

---

## 项目概述

LRReader 是一个基于 EhViewer 阅读框架深度改造的 Android 客户端，用于连接 **LANraragi** 自部署漫画/档案管理服务器。项目保留了 EhViewer 的 UI 框架和高性能阅读体验（C/JNI 图像解码 + OpenGL 渲染），将后端从 E-Hentai 网站完全切换为 LANraragi REST API。

### 技术栈

| 层 | 技术 |
|---|---|
| 语言 | Java / Kotlin 混合（新代码用 Kotlin） |
| 网络 | OkHttp 4.12 + Kotlin 协程 |
| API 序列化 | kotlinx-serialization (LRR API) + Gson (遗留) |
| 数据库 | Room 2.6.1 + KSP（schema v9，显式 Migration） |
| 图像解码 | 自研 C/JNI 引擎 (libjpeg-turbo, libpng, libwebp) |
| 安全 | EncryptedSharedPreferences (API Key 加密存储) |
| 构建 | Gradle 8.13 + AGP + R8 代码混淆 |
| ABI | arm64-v8a |
| 最低版本 | Android 9 (API 28)，目标 API 35 |

### 构建环境

详细的签名配置和构建命令请参考 [CONTRIBUTING.md](../CONTRIBUTING.md#签名配置)。

```bash
# 快速构建 Debug 版本
./gradlew :app:assembleAppReleaseDebug

# 签名 Release APK（需要 local.properties 中的签名配置）
./gradlew :app:assembleAppReleaseRelease

# AAB（Google Play 上传）
./gradlew :app:bundleAppReleaseRelease
```

---

## 项目目录结构

```
LRReader/
├── app/                         # Android 应用源码
│   ├── build.gradle             # 应用级构建（签名、依赖、ABI）
│   ├── proguard-rules.pro       # R8/ProGuard 规则
│   └── src/main/
│       ├── java/com/hippo/
│       │   ├── ehviewer/        # 主应用代码（保留旧包名）
│       │   │   ├── client/lrr/  # ★ LANraragi API 客户端（19 个文件）
│       │   │   ├── dao/         # Room 数据库（AppDatabase.kt + 3 DAO）
│       │   │   ├── download/    # 下载管理
│       │   │   ├── gallery/     # 画廊提供者（LRRGalleryProvider）
│       │   │   └── ui/          # Activity / Fragment / Scene
│       │   ├── widget/          # 通用控件库
│       │   └── lib/             # 底层库（图像处理）
│       ├── cpp/                 # C/JNI 图像解码引擎
│       ├── res/                 # 资源文件（10 种语言）
│       └── assets/              # 开源许可证 HTML 页面
├── keystore/                    # 签名密钥（gitignored）
├── fastlane/                    # Google Play 元数据
├── docs/                        # 开发文档（gitignored，仅内部使用）
└── [根目录文档]                  # README, CONTRIBUTING, LICENSE, NOTICE, PRIVACY_POLICY
```

---

## 关键文件速查

| 文件 | 角色 |
|------|------|
| `LRREngine.kt` | LANraragi API 封装（suspend + kotlinx-serialization + 指数退避重试） |
| `LRRGalleryProvider.java` | LANraragi 画廊阅读器（页面下载/解码/预加载/LRU 淘汰） |
| `LRRDownloadWorker.java` | LANraragi 画廊下载器（验证/重试/原子重命名） |
| `AppDatabase.kt` | Room 数据库单例（v9 schema，11 张表，显式 Migration） |
| `EhDB.kt` | ~1170 行双层桥接 API（suspend + runBlocking for Java callers） |
| `GalleryDetailScene.java` | 画廊详情页（107KB） |
| `GalleryListScene.java` | 画廊列表页（85KB） |
| `EhApplication.java` | Application 入口（惰性 OkHttpClient） |
| `proguard-rules.pro` | R8 规则（Log 剥离 + JNI/Room/Serialization keep） |

---

## 开发注意事项

### ⚠️ 包名说明

代码包名仍为 `com.hippo.ehviewer`，但 `applicationId` 已改为 `com.lanraragi.reader`。经评估（501 文件 + 50+ JNI 函数名硬编码），重命名包名的 ROI 极度负面，决定保持现状。

详见 [PROJECT_TECHNICAL_DOC.md §6](PROJECT_TECHNICAL_DOC.md#6-已知技术债与改进方向)。

### ⚠️ Edge-to-Edge 适配

涉及 Activity 布局或状态栏时，**必读** [EDGE_TO_EDGE.md](EDGE_TO_EDGE.md)。`androidx.activity:1.10.1` + API 35+ 下 `setStatusBarColor()` 完全无效，状态栏着色只能通过布局实现。

### ⚠️ 数据库 Migration

v9 是首个公开发布版本。未来修改数据库结构时，必须编写显式 `Migration` 对象。**严禁使用 `fallbackToDestructiveMigration()`**。

详见 [PROJECT_TECHNICAL_DOC.md §4.4](PROJECT_TECHNICAL_DOC.md#44-数据持久化-room--异步-dao)。

---

## 版本历程

| 版本 | versionCode | 里程碑 |
|------|-------------|--------|
| v1.0 – v1.4 | 1–5 | 初始 LANraragi 适配，基础功能 |
| v1.5 | 6 | Phase 1-7 优化（minSdk 28, OkHttp 4, Room, KSP, 死代码清理, 协程迁移） |
| v1.6 | 7 | 头像/背景自定义，x86_64 支持 |
| v1.7 | 8–9 | Bug 修复（R8 标签翻译, 头像裁剪），多服务器管理 UI |
| v1.8 | 10–11 | 安全增强（权限清理, DB Migration），Settings UI 修复 |
| v1.9 | 12 | 开源合规（开发者信息统一, GPLv3 许可证, 开源致谢），Google Play 准备 |
