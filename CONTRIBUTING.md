# Contributing to LRReader

感谢你对 LRReader 的关注！欢迎提交 Bug 报告、功能建议和代码贡献。

## 开发环境

- **Android Studio** Ladybug 或更高版本
- **JDK** 17+
- **Android SDK** API 35 (compile), API 28 (min)
- **Kotlin** 2.1.0

## 构建

```bash
git clone https://github.com/xiaojieonly/Ehviewer_CN_SXJ.git
cd Ehviewer_CN_SXJ
./gradlew :app:assembleAppReleaseDebug
```

生成的 APK 在 `app/build/outputs/apk/appRelease/debug/` 目录下。

## 签名配置

项目使用 `keystore/release.jks` 作为发布签名密钥，签名凭据通过 `local.properties` 注入（该文件已 gitignore，不会提交到仓库）。

**首次 clone 后，在项目根目录的 `local.properties` 中添加：**

```properties
RELEASE_STORE_FILE=keystore/release.jks
RELEASE_STORE_PASSWORD=REDACTED
RELEASE_KEY_ALIAS=lrreader
RELEASE_KEY_PASSWORD=REDACTED
```

> ⚠️ **密钥信息**
> - Keystore 路径：`keystore/release.jks`
> - Key Alias：`lrreader`
> - Store/Key Password：`REDACTED`
> - 算法：RSA 2048，有效期 10000 天
>
> **请妥善保管 `release.jks` 文件和密码。一旦丢失，将无法更新已发布的应用。**

## 发布构建

```bash
# 构建签名 APK（直接分发）
./gradlew :app:assembleAppReleaseRelease

# 构建 AAB（Google Play 上传）
./gradlew :app:bundleAppReleaseRelease
```

| 产物 | 路径 | 用途 |
|---|---|---|
| APK | `app/build/outputs/apk/appRelease/release/` | GitHub Releases / 直接安装 |
| AAB | `app/build/outputs/bundle/appReleaseRelease/` | Google Play Console 上传 |

## 提交规范

### Commit Message

采用简洁的中文或英文描述，格式：

```
<类型>: <简要描述>

[可选的详细说明]
```

**类型**：
- `feat` — 新功能
- `fix` — Bug 修复
- `refactor` — 代码重构（无功能变更）
- `docs` — 文档变更
- `chore` — 构建/依赖/配置变更

### 分支策略

- 基于 `BiLi_PC_Gamer` 分支创建你的 feature/fix 分支
- PR 标题应清晰描述改动内容

## 代码规范

- Java 和 Kotlin 代码混合项目，新代码建议使用 **Kotlin**
- 网络调用使用 **Kotlin 协程** (`suspend fun` + `withContext(Dispatchers.IO)`)
- LRR API 相关代码放在 `client/lrr/` 包下
- 遵循现有代码风格（4 空格缩进，花括号不换行）

## 报告 Bug

请在 [Issues](https://github.com/xiaojieonly/Ehviewer_CN_SXJ/issues) 中提交，包含以下信息：

1. 设备型号和 Android 版本
2. LRReader 版本号
3. 复现步骤
4. 期望行为 vs 实际行为
5. 日志截图（如有）

## 许可证

贡献的代码将按照项目的 [GPLv3 许可证](LICENSE) 发布。
