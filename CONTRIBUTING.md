# Contributing to LR Reader | 贡献指南

感谢你对 LRReader 的关注！欢迎提交 Bug 报告、功能建议和代码贡献。

Thank you for your interest in LRReader! Bug reports, feature requests, and code contributions are welcome.

## 开发环境 | Development Environment

| 工具 / Tool | 版本 / Version |
|---|---|
| Android Studio | Ladybug+ |
| JDK | 17+ |
| Android SDK | API 35 (compile), API 28 (min) |
| Kotlin | 2.1.0 |

## 构建 | Build

```bash
git clone https://github.com/Xslx98/LRReader.git
cd LRReader
./gradlew :app:assembleAppReleaseDebug
```

生成的 APK 在 `app/build/outputs/apk/appRelease/debug/` 目录下。

The generated APK is located at `app/build/outputs/apk/appRelease/debug/`.

## 签名配置 | Signing Config

项目使用 `keystore/release.jks` 作为发布签名密钥。签名凭据通过 `local.properties` 注入（已 gitignore）。

The project uses `keystore/release.jks` for release signing. Credentials are injected via `local.properties` (gitignored).

**首次 clone 后，在 `local.properties` 中添加 | After cloning, add to `local.properties`:**

```properties
RELEASE_STORE_FILE=keystore/release.jks
RELEASE_STORE_PASSWORD=lrreader2026
RELEASE_KEY_ALIAS=lrreader
RELEASE_KEY_PASSWORD=lrreader2026
```

> ⚠️ **密钥信息 | Key Info**
> - Keystore: `keystore/release.jks`
> - Key Alias: `lrreader`
> - Store/Key Password: `lrreader2026`
> - Algorithm: RSA 2048, validity 10000 days
>
> **请妥善保管 release.jks 文件和密码。一旦丢失，将无法更新已发布的应用。**
>
> **Keep release.jks and its password safe. If lost, published apps cannot be updated.**

## 发布构建 | Release Build

```bash
# 签名 APK / Signed APK (direct distribution)
./gradlew :app:assembleAppReleaseRelease
```

| 产物 / Artifact | 路径 / Path | 用途 / Usage |
|---|---|---|
| APK | `app/build/outputs/apk/appRelease/release/` | GitHub Releases |

## 提交规范 | Commit Guidelines

### Commit Message

采用简洁的中文或英文描述 | Use concise Chinese or English descriptions:

```
<type>: <brief description>

[optional details]
```

**类型 | Types**:
- `feat` - 新功能 / New feature
- `fix` - Bug 修复 / Bug fix
- `refactor` - 代码重构 / Code refactoring
- `docs` - 文档变更 / Documentation
- `chore` - 构建/依赖/配置 / Build/deps/config

### 分支策略 | Branch Strategy

- 基于 `main` 分支创建 feature/fix 分支
- Create your feature/fix branch from `main`
- PR 标题应清晰描述改动内容
- PR titles should clearly describe the changes

## 代码规范 | Code Style

- Java 和 Kotlin 混合项目，新代码建议使用 **Kotlin**
- Java/Kotlin hybrid project; prefer **Kotlin** for new code
- 网络调用使用 **Kotlin 协程** (`suspend fun` + `withContext(Dispatchers.IO)`)
- Network calls should use **Kotlin Coroutines**
- LRR API 相关代码放在 `client/lrr/` 包下
- LRR API code goes in the `client/lrr/` package
- 遵循现有代码风格（4 空格缩进，花括号不换行）
- Follow existing style (4-space indent, same-line braces)

## 报告 Bug | Reporting Bugs

请在 [Issues](https://github.com/Xslx98/LRReader/issues) 中提交，包含以下信息：

Please file an [Issue](https://github.com/Xslx98/LRReader/issues) with:

1. 设备型号和 Android 版本 / Device model and Android version
2. LRReader 版本号 / LRReader version
3. 复现步骤 / Steps to reproduce
4. 期望行为 vs 实际行为 / Expected vs actual behavior
5. 日志截图（如有）/ Log screenshots (if any)

## 许可证 | License

贡献的代码将按照项目的 [GPLv3 许可证](LICENSE) 发布。

All contributions are released under the project's [GPLv3 License](LICENSE).
