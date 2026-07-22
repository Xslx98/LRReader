# LR Reader

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-9%2B-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-purple.svg)](https://kotlinlang.org)

一个基于 EhViewer 阅读框架的 [LANraragi](https://github.com/Difegue/LANraragi) Android 客户端。

An Android client for [LANraragi](https://github.com/Difegue/LANraragi), built upon the EhViewer reading framework.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="200" alt="Gallery List"/>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="200" alt="Gallery Grid"/>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="200" alt="Switch Server"/>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="200" alt="Settings"/>
</p>

---

## ✨ 功能特性 | Features

| 功能 / Feature | 说明 / Description |
|---|---|
| 🔍 **全功能搜索 / Full Search** | 关键词、分类筛选、排序、随机推荐 / Keywords, categories, sorting, random |
| 🕘 **搜索历史 / Search History** | 按服务器记录最近搜索，可逐条删除或一键清空 / Per-server recent searches with per-entry delete and clear-all |
| 🙈 **隐藏已读完 / Hide Finished** | 浏览时可过滤掉已读完的档案 / Optionally filter finished archives out of browsing (LANraragi 0.9.8+) |
| 📖 **高性能阅读 / High-Performance Reader** | C 层图像解码引擎 + 智能预加载 / Native C image decoder + smart preloading |
| ⏭️ **跨档案续读 / Reader Continuation** | 读到末页自动衔接下一本，无需回列表 / End-of-book panel jumps straight to the next archive |
| ▶️ **继续阅读 / Continue Reading** | 桌面快捷方式一键回到上次阅读的档案与页码 / Launcher shortcut back to your last archive and page |
| 📚 **合订本 / Tankoubon** | 浏览与管理合订本：成员排序、全局阅读进度、成员间链式续读 / Browse & manage tankoubons: member ordering, global progress, chained reading (LANraragi 0.9.8+) |
| 🔖 **页面标注 / Page Stamps** | 阅读器内查看、放置、编辑页面标注 / View, place and edit per-page stamps in the reader (LANraragi 0.9.8+) |
| 🔄 **进度同步 / Progress Sync** | 阅读进度与服务器双向同步，跨设备续读 / Two-way reading-progress sync with the server |
| 📊 **阅读统计 / Reading Stats** | 阅读量、分服务器统计与标签偏好分析 / Reading totals, per-server breakdown and tag-preference analysis |
| 🖼️ **页面预览 / Page Previews** | 详情页全页面缩略图网格，支持跳页与密度调节 / Per-page thumbnail grid on detail page with jump-to-page and configurable density |
| ⬇️ **离线下载 / Offline Download** | 后台下载整本档案，断点续传 + 断网自动等网恢复 / Background archive download with resume and automatic recovery after network loss |
| ☑️ **多选批量 / Batch Operations** | 列表长按多选：批量下载、加分类、清 NEW、删除 / Long-press multi-select for batch download, categorize, clear-new, delete |
| 🏷️ **标签翻译 / Tag Translation** | 中文环境下自动翻译标签 / Auto-translate tags in Chinese locale (EhTagDatabase) |
| ⭐ **档案评分 / Archive Rating** | 基于标签的 emoji 星级评分 / Tag-based emoji star rating |
| 📁 **分类管理 / Category Management** | 浏览、创建、编辑 LANraragi 分类 / Browse, create, edit LANraragi categories |
| 🔐 **安全认证 / Secure Auth** | API Key 加密存储 + 定向请求鉴权 + 图案应用锁 / Encrypted API Key storage, per-request auth, pattern app lock |
| 🖥️ **多服务器 / Multi-Server** | 支持配置和切换多个 LANraragi 实例，跨服务器打开与下载 / Configure and switch between server instances, cross-server detail & download |
| 📤 **上传管理 / Upload** | 从设备上传档案 / 通过 URL 下载到服务器，实时进度 / Upload from device or by URL with live progress |
| 🗑️ **远程删除 / Remote Delete** | 服务器档案删除，带可选 3 秒确认倒计时 / Server-side deletion with optional 3-second confirmation cooldown |
| 🚀 **应用内更新 / In-App Update** | 自动检查 GitHub Releases 并安装新版本 / Auto-check GitHub Releases and install updates |
| 🌐 **10 种语言 / 10 Languages** | 中文简繁/粤语、日/韩/英/法/德/西/泰 / CJK + EN/FR/DE/ES/TH |
| 🌙 **深色模式 / Dark Mode** | 跟随系统主题，支持纯黑模式 / System theme + AMOLED black |

> 兼容所有较新的 LANraragi 版本；页面标注、合订本、隐藏已读完等 0.9.8 新能力在旧版服务器上自动降级。
>
> Works with any reasonably recent LANraragi; 0.9.8-only capabilities (stamps, tankoubons, hide-finished) degrade gracefully on older servers.

## 📥 下载 | Download

| 渠道 / Channel | 链接 / Link |
|---|---|
| GitHub Releases | [最新版本 / Latest](https://github.com/Xslx98/LRReader/releases) |

## 🛠️ 构建 | Build

### 环境要求 | Requirements

| 工具 / Tool | 版本 / Version |
|---|---|
| Android Studio | 支持 AGP 8.13 的版本 / Any version supporting AGP 8.13 |
| JDK | 21+ |
| Android SDK | API 35 (compileSdk) |
| Kotlin | 2.3.21 (KSP 2.3.9) |
| Android 最低版本 / Min SDK | 9.0 (API 28) |

### 快速开始 | Quick Start

```bash
git clone https://github.com/Xslx98/LRReader.git
cd LRReader
```

首次 clone 后，在根目录创建 `local.properties` 并添加签名配置：

After cloning, create `local.properties` in the project root with your signing config:

```properties
sdk.dir=/path/to/your/Android/Sdk
RELEASE_STORE_FILE=keystore/release.jks
RELEASE_STORE_PASSWORD=<your-store-password>
RELEASE_KEY_ALIAS=<your-key-alias>
RELEASE_KEY_PASSWORD=<your-key-password>
```

构建 | Build:

```bash
# Debug APK
./gradlew :app:assembleAppReleaseDebug

# 签名 Release APK / Signed Release APK
./gradlew :app:assembleAppReleaseRelease
```

> 详细的签名配置和发布流程请参考 [CONTRIBUTING.md](CONTRIBUTING.md)。
>
> See [CONTRIBUTING.md](CONTRIBUTING.md) for detailed signing and release instructions.

## 🏗️ 技术栈 | Tech Stack

| 层 / Layer | 技术 / Technology |
|---|---|
| **语言 / Language** | Kotlin (业务代码 100%) + Java (GLView / widget 遗留框架) / Kotlin (all business code) + Java (legacy GLView / widget framework) |
| **网络 / Network** | OkHttp 4.12 + Kotlin Coroutines |
| **API 序列化 / Serialization** | kotlinx-serialization (all JSON) |
| **列表分页 / Paging** | Jetpack Paging 3 |
| **数据库 / Database** | Room 2.8 + KSP (schema v29, 多服务器复合主键 / composite key for multi-server state) |
| **图像解码 / Image Decoding** | Custom C/JNI engine (libjpeg-turbo, libpng, libwebp) |
| **安全 / Security** | EncryptedSharedPreferences (API Key, 模式锁 / pattern lock) |
| **构建 / Build** | Gradle + R8/ProGuard |
| **ABI** | Release: arm64-v8a · Debug: arm64-v8a + x86_64 |

## 📂 项目结构 | Project Structure

```
LRReader/
├── app/src/main/
│   ├── java/
│   │   ├── com/hippo/ehviewer/         # Business code (Kotlin)
│   │   │   ├── dao/                    # Room Database (AppDatabase.kt, schema v29)
│   │   │   ├── download/               # Download subsystem (DownloadManager facade)
│   │   │   ├── settings/               # Modular settings (Privacy, Network, Reading, …)
│   │   │   ├── stats/                  # Reading statistics + daily aggregate
│   │   │   ├── ui/                     # Activities, Scenes, Fragments, ViewModels
│   │   │   └── Settings.kt             # Shared preferences entry
│   │   ├── com/lanraragi/reader/       # LANraragi-specific code
│   │   │   ├── client/api/             # REST API client (LRRArchiveApi, LRRClientProvider, …)
│   │   │   └── domain/                 # Domain models (Archive, …)
│   │   └── com/hippo/{glview,widget,…} # Legacy GLView / Conaco / widget framework (Java)
│   ├── cpp/                            # C/JNI native image decoder
│   ├── res/                            # Resources (10 languages)
│   └── assets/                         # Open-source license page
├── fastlane/metadata/android/          # Play Store metadata + per-release changelogs
├── keystore/                           # Signing keys (gitignored)
├── CONTRIBUTING.md                     # Contributing guide
├── PRIVACY_POLICY.md                   # Privacy policy
├── NOTICE                              # Upstream credits
└── LICENSE                             # GPLv3
```

## 🙏 致谢 | Acknowledgments

本项目基于以下开源项目二次开发：

This project is built upon the following open-source projects:

| 项目 / Project | 作者 / Author | 许可证 / License |
|---|---|---|
| [EhViewer](https://github.com/seven332/EhViewer) | Hippo Seven | Apache 2.0 |
| [EhViewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ) | xiaojieonly (SXJ_LonelyDog) | GPLv3 |

### 依赖库 | Dependencies

- [AndroidX](https://developer.android.com/jetpack/androidx) (AppCompat, Room, RecyclerView, Security)
- [OkHttp](https://github.com/square/okhttp) - HTTP client
- [kotlinx-serialization](https://github.com/Kotlin/kotlinx.serialization) - JSON serialization
- [kotlinx-coroutines](https://github.com/Kotlin/kotlinx.coroutines) - Async programming
- [UCrop](https://github.com/Yalantis/uCrop) - Image cropping
- [ReLinker](https://github.com/KeepSafe/ReLinker) - Native library loading
- [jsoup](https://github.com/jhy/jsoup) - HTML parsing
- [libjpeg-turbo](https://libjpeg-turbo.org/) / [libpng](http://www.libpng.org/) - Native image decoding

完整开源许可信息请查看应用内 **设置 - 关于 - 许可证**。

Full license details available in-app under **Settings - About - License**.

## 📜 许可证 | License

本项目基于 [GNU General Public License v3.0](LICENSE) 发布。

This project is licensed under the [GNU General Public License v3.0](LICENSE).

原始 EhViewer 代码基于 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)。详见 [NOTICE](NOTICE)。

Original EhViewer code is licensed under [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). See [NOTICE](NOTICE).
