# Privacy Policy - LR Reader | 隐私政策

**生效日期 | Effective Date:** 2026-10-06

LR Reader（以下简称"本应用"）是一个开源的 LANraragi Android 客户端，用于连接用户自建的漫画/档案管理服务器。本隐私政策说明应用如何处理您的数据。

LR Reader ("the App") is an open-source Android client for LANraragi, a self-hosted manga/archive management server. This privacy policy explains how the App handles your data.

## 数据收集 | Data Collection

**本应用的开发者不收集、也不接收您的任何个人数据。** 本应用没有自己的服务器，也不包含分析、广告或崩溃上报 SDK。

**The App's developer does not collect or receive any personal data from you.** The App has no server of its own and contains no analytics, advertising or crash-reporting SDK.

您的数据保存在您的设备和您配置的 LANraragi 服务器上。本应用会联系的其他地址见下方「网络通信」。

Your data stays on your device and on the LANraragi server(s) you configure. The other addresses the App contacts are listed under "Network Communication" below.

## 本地存储的数据 | Data Stored Locally

本应用在您的设备上存储以下数据：

The App stores the following data on your device:

- **服务器连接信息 / Server connection details** (URL, API key) - API 密钥与应用锁图案使用保存在 Android 密钥库（Android Keystore）中的密钥加密存储 / the API key and the app-lock pattern are stored encrypted with a key kept in the Android Keystore
- **阅读历史、进度与偏好设置 / Reading history, progress and preferences** - 存储在本地数据库与设置中；网络不可用时未能发送到服务器的阅读进度和合订本标签更新也暂存在本地，联网后发送 / stored in a local database and in the app settings; reading progress and tankoubon tag updates that could not reach the server while offline are also kept locally and sent once the network is back
- **下载的档案 / Downloaded archives** - 保存在您选择的下载位置，直到您删除。这些文件不加密，应用锁不保护它们：通过 USB 连接的电脑或有存储权限的应用可以读取 / kept in the download location you choose until you delete them. They are not encrypted and the app lock does not protect them: a computer over USB or an app with storage access can read them
- **缓存 / Caches** - 阅读页面、缩略图和网络响应的缓存，大小有上限并随系统分配的缓存配额缩小，可在系统的应用信息中清除，系统空间不足时也可能自动清理 / caches of reader pages, thumbnails and network responses; their sizes are capped and shrink with the cache quota the system grants, they can be cleared from the system app info screen, and the system may clear them when storage runs low
- **标签翻译数据 / Tag translation data** - 仅在显示标签翻译时下载（见下文）/ downloaded only while tag translations are shown (see below)
- **崩溃与诊断报告 / Crash and diagnostic reports** - 默认开启，仅保存在应用私有目录（每类最近 5 份），可在「设置 → 高级」关闭；内容为错误堆栈、设备型号与系统版本、应用版本及近期脱敏事件，不含服务器地址、API 密钥或档案标题 / on by default, kept only in the app-private directory (the newest 5 of each kind) and can be turned off in Settings → Advanced; they contain error stack traces, device model and OS version, app version and recent redacted events, never server addresses, API keys or archive titles

## 备份文件 | Backup Files

只有在您于「设置 → 高级 → 备份数据」中选择保存位置时，本应用才会写出备份文件。文件为未加密的 JSON，包含服务器名称与地址（不含 API 密钥）、阅读历史与档案标题、收藏、下载记录（不含文件本身）、阅读进度与统计、搜索记录和非敏感设置；应用锁与 API 密钥从不写入。文件保存在您选择的位置，由您自行保管；本应用不会上传它。

The App writes a backup file only when you pick a location under Settings → Advanced → Back up data. The file is unencrypted JSON holding server names and addresses (without API keys), reading history with archive titles, favourites, download records (not the files), reading progress and statistics, search history and non-sensitive settings; the app lock and API keys are never written. It is stored where you choose and is yours to keep safe; the App never uploads it.

## 诊断信息分享 | Sharing Diagnostics

本应用不会自动上传任何报告。只有当您在「设置 → 高级」点击「分享诊断信息」并选择分享目标时，才会生成一个 zip 文件交给系统分享面板。该文件包含上述报告、本应用进程的近期日志、应用/数据库/服务器版本、下载队列统计（数量与失败原因）以及脱敏后的设置；服务器地址（仅保留 http/https 与是否为局域网）、API 密钥、密码、下载路径与标签均已移除。

The App never uploads reports on its own. Only when you tap "Share diagnostics" in Settings → Advanced and pick a share target does it build a zip file and hand it to the system share sheet. The file contains the reports above, this app's recent log lines, app/database/server versions, download-queue counts (totals and failure reasons) and redacted settings; the server address (only http/https and LAN yes/no are kept), API keys, passwords, download paths and labels are removed.

## 网络通信 | Network Communication

本应用会联系以下地址。除您自己的服务器外，这些请求会让 GitHub（及相应仓库的所有者）看到您的 IP 地址和请求时间，但不包含您的服务器信息、API 密钥或阅读内容。

The App contacts the following addresses. Apart from your own servers, these requests show your IP address and the time of the request to GitHub (and to the owner of the repository involved); none of them carries your server details, API keys or reading content.

1. **您配置的 LANraragi 服务器 / The LANraragi server(s) you configure** - 浏览、阅读、下载和同步。使用 `http://` 地址时，API 密钥与阅读活动以明文传输，应用会将此类服务器标记为「未加密（HTTP）」/ browsing, reading, downloading and syncing. With an `http://` address the API key and reading activity travel unencrypted; the App labels such servers "Unencrypted (HTTP)".
2. **更新检查 / Update check** (`api.github.com`, `raw.githubusercontent.com/Xslx98/LRReader`) - 默认开启，每天最多一次查询本项目的最新版本及更新公告（advisory.json）；可在「设置 → 更新与支持 → 自动检查更新」关闭。只有在您确认更新后，才会从 GitHub（`github.com`、`objects.githubusercontent.com`）下载安装包 / on by default: at most once a day the App asks for this project's latest release and its update notices (advisory.json); turn it off in Settings → About → Auto-check for updates. The installer is downloaded from GitHub (`github.com`, `objects.githubusercontent.com`) only after you accept an update.
3. **标签翻译数据集 / Tag translation dataset** (`raw.githubusercontent.com/xiaojieonly/EhTagTranslation`) - 第三方仓库。仅当系统语言为中文且开启了「显示标签翻译」时下载，每天最多检查一次 / a third-party repository. Downloaded only when the system language is Chinese and "Show tag translations" is on, checked at most once a day.

本应用不会向任何分析平台或广告网络发送数据。

The App sends no data to any analytics platform or advertising network.

## 权限 | Permissions

| 权限 / Permission | 用途 / Purpose |
|---|---|
| `INTERNET` | 连接您的服务器及上文列出的地址 / Connect to your servers and the addresses listed above |
| `ACCESS_NETWORK_STATE` | 判断网络是否可用、是否为计费网络，以暂停或恢复下载 / Detect connectivity and metered networks to pause or resume downloads |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | 在后台下载档案 / Download archives in the background |
| `POST_NOTIFICATIONS` | 显示下载进度通知 / Show download progress notifications |
| `WAKE_LOCK` | 下载过程中保持 CPU 运行 / Keep the CPU awake while downloading |
| `USE_BIOMETRIC` | 用指纹解锁应用锁（可选）/ Unlock the app lock with a fingerprint (optional) |
| `REQUEST_INSTALL_PACKAGES` | 安装您确认下载的应用更新 / Install an app update you chose to download |

## 第三方服务 | Third-Party Services

本应用未集成任何第三方分析、崩溃上报或广告 SDK。上文「网络通信」列出了本应用会联系的全部第三方地址。

The App does not integrate any third-party analytics, crash-reporting or advertising SDK. "Network Communication" above lists every third-party address the App contacts.

## 儿童隐私 | Children's Privacy

本应用不面向 13 岁以下的儿童，我们不会有意收集儿童信息。

The App is not directed at children under 13. We do not knowingly collect information from children.

## 开源 | Open Source

LR Reader 是基于 GNU General Public License v3.0 (GPLv3) 发布的开源软件。

LR Reader is open-source software licensed under the GNU General Public License v3.0 (GPLv3).

源代码 / Source: [GitHub](https://github.com/Xslx98/LRReader)

## 政策变更 | Changes to This Policy

任何变更将在本文档中体现，并更新生效日期。

Any changes will be reflected in this document with an updated effective date.

## 联系方式 | Contact

如有隐私相关问题，请在项目的 GitHub 仓库中提交 Issue；安全问题请按 SECURITY.md 的说明私下报告。

For privacy-related questions, please open an issue on the project's GitHub repository; report security problems privately as described in SECURITY.md.
