# LR Reader

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-9%2B-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-purple.svg)](https://kotlinlang.org)

一个基于 EhViewer 阅读框架的 [LANraragi](https://github.com/Difegue/LANraragi) Android 客户端。

An Android client for [LANraragi](https://github.com/Difegue/LANraragi), built upon the EhViewer reading framework.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="280" alt="Screenshot"/>
</p>

## ✨ 功能特性 / Features

| 功能 | 说明 |
|---|---|
| 🔍 **全功能搜索** | 关键词、分类筛选、排序、随机推荐 |
| 📖 **高性能阅读** | C 层图像解码引擎 + 智能预加载 |
| ⬇️ **离线下载** | 后台下载整本档案，支持断点续传 |
| 🏷️ **标签翻译** | 中文环境下自动翻译标签（EhTagDatabase） |
| ⭐ **档案评分** | 基于标签的 emoji 星级评分 |
| 📁 **分类管理** | 浏览、创建、编辑 LANraragi 分类 |
| 🔐 **安全认证** | API Key 加密存储（EncryptedSharedPreferences）+ 定向请求鉴权 |
| 🖥️ **多服务器** | 支持配置和切换多个 LANraragi 实例 |
| 📤 **上传管理** | 从设备上传档案 / 通过 URL 下载到服务器 |
| 🗑️ **远程删除** | 带倒计时确认的服务器档案删除 |
| 🌐 **10 种语言** | 中文简繁/粤语、日/韩/英/法/德/西/泰 |
| 🌙 **深色模式** | 跟随系统主题，支持纯黑模式 |

## 📥 下载 / Download

| 渠道 | 链接 |
|---|---|
| GitHub Releases | [最新版本](https://github.com/<YOUR_USERNAME>/<YOUR_REPO>/releases) |
| Google Play | *即将上线* |

## 🛠️ 构建 / Build

### 环境要求

| 工具 | 版本 |
|---|---|
| Android Studio | Ladybug 或更高 |
| JDK | 21+ |
| Android SDK | API 35 (compileSdk) |
| Kotlin | 2.1.0 |
| Android 最低版本 | 9.0 (API 28) |

### 快速开始

```bash
git clone https://github.com/<YOUR_USERNAME>/<YOUR_REPO>.git
cd <YOUR_REPO>
```

首次 clone 后，在根目录创建 `local.properties` 并添加签名配置：

```properties
sdk.dir=/path/to/your/Android/Sdk
RELEASE_STORE_FILE=keystore/release.jks
RELEASE_STORE_PASSWORD=REDACTED
RELEASE_KEY_ALIAS=lrreader
RELEASE_KEY_PASSWORD=REDACTED
```

构建：

```bash
# Debug APK
./gradlew :app:assembleAppReleaseDebug

# 签名 Release APK
./gradlew :app:assembleAppReleaseRelease

# AAB (Google Play)
./gradlew :app:bundleAppReleaseRelease
```

> 详细的签名配置和发布流程请参考 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 🏗️ 技术栈 / Tech Stack

| 层 | 技术 |
|---|---|
| **语言** | Java / Kotlin 混合 |
| **网络** | OkHttp 4.12 + Kotlin 协程 |
| **API 序列化** | kotlinx-serialization (LRR API) + Gson (遗留) |
| **数据库** | Room 2.6.1 + KSP（schema v9，显式 Migration） |
| **图像解码** | 自研 C/JNI 引擎 (libjpeg-turbo, libpng, libwebp) |
| **安全** | EncryptedSharedPreferences (API Key 存储) |
| **构建** | Gradle + R8/ProGuard 代码混淆 |
| **ABI** | arm64-v8a |

## 📂 项目结构

```
LRReader/
├── app/src/main/
│   ├── java/com/hippo/ehviewer/  # 主代码（EhViewer 框架）
│   │   ├── client/lrr/           # LANraragi REST API 客户端
│   │   ├── dao/                  # Room 数据库（AppDatabase.kt）
│   │   ├── ui/                   # Activity & Fragment
│   │   └── Settings.java         # 应用偏好设置
│   ├── cpp/                      # C/JNI 图像解码引擎
│   ├── res/                      # 资源文件（10 种语言）
│   └── assets/                   # 开源许可证页面
├── keystore/                     # 签名密钥（gitignored）
├── fastlane/                     # Google Play 元数据
├── CONTRIBUTING.md               # 贡献指南 & 构建说明
├── PRIVACY_POLICY.md             # 隐私政策
├── NOTICE                        # 上游项目致谢
└── LICENSE                       # GPLv3
```

## 🙏 致谢 / Acknowledgments

本项目基于以下开源项目二次开发：

| 项目 | 作者 | 许可证 |
|---|---|---|
| [EhViewer](https://github.com/seven332/EhViewer) | Hippo Seven | Apache 2.0 |
| [EhViewer_CN_SXJ](https://github.com/xiaojieonly/Ehviewer_CN_SXJ) | xiaojieonly (SXJ_LonelyDog) | GPLv3 |

### 依赖库

- [AndroidX](https://developer.android.com/jetpack/androidx) (AppCompat, Room, RecyclerView, Security)
- [OkHttp](https://github.com/square/okhttp) — 网络请求
- [kotlinx-serialization](https://github.com/Kotlin/kotlinx.serialization) — JSON 序列化
- [kotlinx-coroutines](https://github.com/Kotlin/kotlinx.coroutines) — 异步编程
- [Gson](https://github.com/google/gson) — JSON 解析（遗留）
- [UCrop](https://github.com/Yalantis/uCrop) — 图像裁剪
- [ReLinker](https://github.com/KeepSafe/ReLinker) — 原生库加载
- [jsoup](https://github.com/jhy/jsoup) — HTML 解析
- [libjpeg-turbo](https://libjpeg-turbo.org/) / [libpng](http://www.libpng.org/) — 原生图像解码

完整开源许可信息请查看应用内 **设置 → 关于 → 许可证**。

## 📜 许可证 / License

本项目基于 [GNU General Public License v3.0](LICENSE) 发布。

原始 EhViewer 代码基于 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)。
详见 [NOTICE](NOTICE)。
