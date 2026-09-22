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

## 📥 下载 | Download

| 渠道 / Channel | 链接 / Link |
|---|---|
| GitHub Releases | [最新版本 / Latest](https://github.com/Xslx98/LRReader/releases) |

> Release 仅提供 arm64-v8a APK（Android 9+）。应用内可自动检查并安装更新。
>
> Releases ship an arm64-v8a APK only (Android 9+). The app can check for and install updates itself.

## 🚀 三步开始使用 | Get Started in Three Steps

LR Reader 是你自己 LANraragi 服务器的手机客户端，所以第一次打开需要告诉它服务器在哪、怎么进门。整个过程只要一两分钟。

LR Reader is a phone client for your own LANraragi server, so on first launch it needs to know where the server is and how to get in. It takes a minute or two.

**1. 填服务器地址 / Enter the server address**

打开 App 会看到引导页。在「服务器地址」里填 LANraragi 的地址和端口，比如 `192.168.1.100:3000`。不用写 `http://` 或 `https://`——App 会先试加密连接，局域网地址再自动改用明文（改用明文时会请你确认一次）。如果从外网访问，建议把 LANraragi 放在支持 HTTPS 的反向代理后面。

The onboarding page opens on first launch. Put your LANraragi address and port in the server field, e.g. `192.168.1.100:3000`. No need to type `http://` or `https://` — the app tries an encrypted connection first and falls back to plain HTTP for LAN addresses (it asks you once before doing so). For access from outside your home, put LANraragi behind an HTTPS reverse proxy.

**2. 拿到 API Key / Get an API Key**

API Key 是 App 进入服务器的钥匙，在 LANraragi 网页端设置：

The API Key is what lets the app into your server. Set it in the LANraragi web UI:

1. 在电脑或手机浏览器打开 LANraragi，点右上角 **Admin Login** 登录。管理员密码默认是 `kamimamita`，登录后请尽快改掉。
   Open LANraragi in a browser and log in through **Admin Login**. The default admin password is `kamimamita` — change it once you are in.
2. 点 **Settings**，左侧选 **Security**，勾选 **Enable Password**（不勾的话看不到 API Key 这一栏）。
   Click **Settings**, pick **Security** on the left and tick **Enable Password** (the API Key field only appears when it is on).
3. 在 **API Key** 里填一串你自己想的字符（这不是登录密码，留空无效），点 **Save Settings**。
   Type any string of your choosing into **API Key** (it is not the login password, and an empty key does not work), then **Save Settings**.
4. 把这串字符填进 App 的「API Key」栏。
   Copy that string into the app's API Key field.

> 服务器没有开密码时，App 里的 API Key 可以留空。如果开了 **No-Fun Mode**，连看图都要密码，这时一定要填 Key。
>
> If your server has no password turned on, leave the key empty. With **No-Fun Mode** on, even reading needs a password, so the key is required.

**3. 测试并保存 / Test and save**

点「测试连接」，看到服务器名和版本就说明通了，再点保存。以后想连别的 LANraragi，在侧边抽屉的「切换服务器」里添加即可，每台服务器的 Key 都单独加密保存。

Tap **Test connection**; seeing the server name and version means you are in — then save. To add more LANraragi servers later, use **Switch Server** in the side drawer; each server keeps its own encrypted key.

## ✨ 功能特性 | Features

| 功能 / Feature | 说明 / Description |
|---|---|
| 🔍 **全功能搜索 / Full Search** | 关键词、分类筛选、排序、随机推荐 / Keywords, categories, sorting, random |
| 🕘 **搜索历史 / Search History** | 按服务器记录最近搜索，可逐条删除或一键清空 / Per-server recent searches with per-entry delete and clear-all |
| 🙈 **隐藏已读完 / Hide Finished** | 浏览时可过滤掉已读完的档案 / Optionally filter finished archives out of browsing (LANraragi 0.9.8+) |
| 📖 **高性能阅读 / High-Performance Reader** | GL 渲染管线 + 按需解码 + 智能预加载 / GL rendering pipeline, on-demand decoding and smart preloading |
| ⏭️ **跨档案续读 / Reader Continuation** | 读到末页自动衔接下一本，无需回列表 / End-of-book panel jumps straight to the next archive |
| ▶️ **继续阅读 / Continue Reading** | 桌面快捷方式一键回到上次阅读的档案与页码 / Launcher shortcut back to your last archive and page |
| 📚 **合订本 / Tankoubon** | 合订本像一本书：专属详情页（封面、评分、分类、标签、成员条、页面预览）；标签自动保持为成员标签的并集，静态分类随成员一起收录；任意页设为封面；新建时自动从所选标题提取名称；成员管理支持多选整理、拖动手柄与「按标题排序」（识别第N话/回/卷/章、Vol/Ch、范围、上/中/下，番外殿后，可撤销） / A tankoubon reads like one book: its own detail page (cover, rating, category, tags, member strip, page previews); tags kept as the union of member tags and static categories following members in; cover from any page; a name suggested from the selected titles on create; member management with multi-select reordering, a drag handle and an undoable episode-aware "Sort by title" (LANraragi 0.9.8+) |
| 📕 **整本无缝阅读 / Seamless Tank Reading** | 合订本作为一本书阅读：全局页码、双向无缝跨成员翻页、阅读位置续读 / Read a whole tankoubon as one book: global page numbering, seamless page turns across members in both directions, resumable position (LANraragi 0.9.8+) |
| 📦 **合订本下载 / Tank Download** | 一键下载整个合订本，下载列表聚合为单卡片，点击即离线整本阅读；已下载成员自动并入零重复下载 / One-tap whole-tank download aggregated into a single card that opens the offline whole-tank session; already-downloaded members merge in with zero re-download (LANraragi 0.9.8+) |
| ⏬ **边下边读 / Read While Downloading** | 下载中的档案（含合订本成员）可直接阅读：已落盘页秒开，阅读器取回的页写入下载目录供下载器复用；离线时缺页显示错误页而非截断 / Read an archive (or tankoubon member) while it downloads: landed pages open instantly, pages fetched by the reader are written into the download directory for the worker to reuse; offline, missing pages show as errors instead of ending early |
| 🔖 **页面标注 / Page Stamps** | 阅读器内查看、放置、编辑页面标注 / View, place and edit per-page stamps in the reader (LANraragi 0.9.8+) |
| 🔄 **进度同步 / Progress Sync** | 阅读进度与服务器双向同步，跨设备续读 / Two-way reading-progress sync with the server |
| 📊 **阅读统计 / Reading Stats** | 阅读量、分服务器统计与标签偏好分析 / Reading totals, per-server breakdown and tag-preference analysis |
| 🖼️ **页面预览 / Page Previews** | 详情页全页面缩略图网格，支持跳页与密度调节 / Per-page thumbnail grid on detail page with jump-to-page and configurable density |
| ⬇️ **离线下载 / Offline Download** | 后台下载整本档案，断点续传 + 断网自动等网恢复 / Background archive download with resume and automatic recovery after network loss |
| ☑️ **多选批量 / Batch Operations** | 长按后滑动即可连续多选：批量下载、加分类、加入合订本（带汇入动画，无合订本时直接新建）、清 NEW、删除 / Long-press then slide to select a run of rows: batch download, categorize, add to tankoubon (with a merge animation; creates one when none exists), clear-new, delete |
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

构建产物 | Output: Debug APK 位于 `app/build/outputs/apk/appRelease/debug/`，Release APK 位于 `app/build/outputs/apk/appRelease/release/`。
The Debug APK lands in `app/build/outputs/apk/appRelease/debug/`, the Release APK in `app/build/outputs/apk/appRelease/release/`.

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
| **数据库 / Database** | Room 2.8 + KSP (schema v31, 多服务器复合主键 / composite key for multi-server state) |
| **图像解码 / Image Decoding** | Android ImageDecoder（按目标尺寸采样）+ 轻量 JNI 辅助 / Platform ImageDecoder with target-size sampling + minimal JNI helpers |
| **安全 / Security** | EncryptedSharedPreferences (API Key, 模式锁 / pattern lock) |
| **构建 / Build** | Gradle + R8/ProGuard |
| **ABI** | Release: arm64-v8a · Debug: arm64-v8a + x86_64 |

## 📂 项目结构 | Project Structure

```
LRReader/
├── app/src/main/
│   ├── java/
│   │   ├── com/lanraragi/reader/       # Business code (Kotlin)
│   │   │   ├── client/api/             # LANraragi REST client (LRRArchiveApi, LRRTankoubonApi, …)
│   │   │   ├── dao/                    # Room Database (AppDatabase.kt, schema v31)
│   │   │   ├── domain/                 # Domain models (Archive, …)
│   │   │   ├── download/               # Download subsystem (DownloadManager facade, worker)
│   │   │   ├── gallery/                # Reader providers (streaming / local dir / hybrid / tankoubon)
│   │   │   ├── tankoubon/              # Member sorting + reorder logic (pure Kotlin)
│   │   │   ├── settings/               # Modular settings (Privacy, Network, Reading, …)
│   │   │   ├── stats/                  # Reading statistics + daily aggregate
│   │   │   ├── ui/                     # Activities, Scenes, ViewModels
│   │   │   └── ServiceRegistry.kt      # Module registry (network / data / coroutine …)
│   │   └── com/lanraragi/framework/    # Legacy GLView / Conaco / widget framework (Java)
│   ├── cpp/                            # Minimal JNI helpers (GIF background decode, GL texImage)
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

完整开源许可信息请查看应用内 **设置 - 关于 - 许可证**。

Full license details available in-app under **Settings - About - License**.

## 📜 许可证 | License

本项目基于 [GNU General Public License v3.0](LICENSE) 发布。

This project is licensed under the [GNU General Public License v3.0](LICENSE).

原始 EhViewer 代码基于 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)。详见 [NOTICE](NOTICE)。

Original EhViewer code is licensed under [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). See [NOTICE](NOTICE).
