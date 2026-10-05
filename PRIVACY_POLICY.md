# Privacy Policy - LR Reader | 隐私政策

**生效日期 | Effective Date:** 2026-10-04

LR Reader（以下简称"本应用"）是一个开源的 LANraragi Android 客户端，用于连接用户自建的漫画/档案管理服务器。本隐私政策说明应用如何处理您的数据。

LR Reader ("the App") is an open-source Android client for LANraragi, a self-hosted manga/archive management server. This privacy policy explains how the App handles your data.

## 数据收集 | Data Collection

**LR Reader 不收集、传输或在外部服务器存储任何个人数据。**

**LR Reader does not collect, transmit, or store any personal data on external servers.**

所有数据仅保留在您的设备或您配置的 LANraragi 服务器上。

All data remains on your device or on the LANraragi server that you configure.

## 本地存储的数据 | Data Stored Locally

本应用仅在您的设备上存储以下数据：

The App stores the following data only on your device:

- **服务器连接信息 / Server connection details** (URL, API key) - 通过 Android EncryptedSharedPreferences 加密存储 / encrypted via EncryptedSharedPreferences
- **阅读历史和偏好设置 / Reading history and preferences** - 存储在本地 SQLite 数据库中 / stored in a local SQLite database
- **缓存图像 / Cached images** - 用于提升性能的临时文件，自动清理 / temporary files, automatically cleaned up
- **头像和背景图片 / Avatar and background images** - 用户自定义的个人资料图片 / user-customized profile images
- **崩溃与诊断报告 / Crash and diagnostic reports** - 默认开启，仅保存在应用私有目录（每类最近 5 份），可在「设置 → 高级」关闭；内容为错误堆栈、设备型号与系统版本、应用版本及近期脱敏事件，不含服务器地址、API 密钥或档案标题 / on by default, kept only in the app-private directory (the newest 5 of each kind) and can be turned off in Settings → Advanced; they contain error stack traces, device model and OS version, app version and recent redacted events, never server addresses, API keys or archive titles

## 诊断信息分享 | Sharing Diagnostics

本应用不会自动上传任何报告。只有当您在「设置 → 高级」点击「分享诊断信息」并选择分享目标时，才会生成一个 zip 文件交给系统分享面板。该文件包含上述报告、本应用进程的近期日志、应用/数据库/服务器版本、下载队列统计（数量与失败原因）以及脱敏后的设置；服务器地址（仅保留 http/https 与是否为局域网）、API 密钥、密码、下载路径与标签均已移除。

The App never uploads reports on its own. Only when you tap "Share diagnostics" in Settings → Advanced and pick a share target does it build a zip file and hand it to the system share sheet. The file contains the reports above, this app's recent log lines, app/database/server versions, download-queue counts (totals and failure reasons) and redacted settings; the server address (only http/https and LAN yes/no are kept), API keys, passwords, download paths and labels are removed.

## 网络通信 | Network Communication

本应用仅与您明确配置的 LANraragi 服务器通信。不会向任何第三方服务、分析平台或广告网络发送数据。

The App communicates only with the LANraragi server(s) you explicitly configure. No data is sent to any third-party services, analytics platforms, or advertising networks.

## 权限 | Permissions

| 权限 / Permission | 用途 / Purpose |
|---|---|
| `INTERNET` | 连接您的 LANraragi 服务器 / Connect to your LANraragi server |
| `CAMERA` | 拍照设置头像（可选）/ Take photos for avatar (optional) |
| `FOREGROUND_SERVICE` | 后台下载档案 / Download archives in background |

## 第三方服务 | Third-Party Services

LR Reader 未集成任何第三方分析、崩溃报告或广告 SDK。

LR Reader does not integrate any third-party analytics, crash reporting, or advertising SDKs.

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

如有隐私相关问题，请在项目的 GitHub 仓库中提交 Issue。

For privacy-related questions, please open an issue on the project's GitHub repository.
