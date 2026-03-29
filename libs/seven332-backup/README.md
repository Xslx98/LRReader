# seven332 JitPack 依赖备份

此目录包含所有 `com.github.seven332` JitPack 依赖的离线 AAR 备份。

## 为什么需要备份

这些库来自 GitHub 用户 `seven332`，是原始 EhViewer 项目的组件。
该用户已多年不活跃，如果其 GitHub 仓库被删除，JitPack 缓存也将失效，
导致项目**无法构建**。

## 备份内容

### 直接依赖 (8 个)
- android-resource-0.1.0.aar
- animator-0.1.0.aar
- drawerlayout-ea2bb388f0.aar
- easyrecyclerview-0.1.1.aar
- hotspot-0.1.0.aar
- refreshlayout-0.1.0.aar
- ripple-0.1.2.aar
- streampipe-0.1.0.aar

### Force-resolved 传递依赖 (4 个, GL 渲染核心)
- glgallery-25893283ca.aar
- glview-ba6aee61d7.aar
- glview-image-68d94b0fc2.aar
- image-09b43c0c68.aar

### 其他传递依赖 (3 个)
- yorozuya-0.1.2.aar
- yorozuya-collect-0.1.4.aar
- yorozuya-thread-0.1.1.aar

## 恢复方法

如果 JitPack 不可用，可以配置 `flatDir` 仓库使用本地 AAR：

```gradle
// settings.gradle 中添加
repositories {
    flatDir { dirs 'libs/seven332-backup' }
}
```

然后将 `libs.versions.toml` 中的 JitPack 引用替换为本地引用。

## 备份来源

- 来自 JitPack CDN 直接下载 (2026-03-29)
- 来自 Gradle 本地缓存
