<div align="center">
<img src="docs/brand/MeguvizHook.svg" width="96" height="96" alt="MeguvizHook">

# MeguvizHook

**面向 Android 模块的 Java 与 Kotlin Hook SDK**

[Maven Central](https://central.sonatype.com/artifact/io.github.jichuo1.meguviz.hook/hook-core) · [快速开始](#快速开始) · [许可证](LICENSE)

</div>

MeguvizHook 为 Android 模块提供方法拦截的声明、安装回执与关闭接口。使用 Java API 或 Kotlin DSL 编写调用前、调用后和环绕回调，再选用与运行框架对应的 libxposed API 101 或 102 适配器。实际拦截由宿主框架执行。

## 快速开始

在 Gradle 的仓库配置中启用 `google()` 和 `mavenCentral()`。Android 后端要求编译 SDK 37.2 或更新版本，设备最低 API 27。下面以 libxposed API 102 为例。

```kotlin
dependencies {
    implementation("io.github.jichuo1.meguviz.hook:hook-core:1.2.0")
    implementation("io.github.jichuo1.meguviz.hook:backend-libxposed-102:1.2.0")
}
```

使用 API 101 时改为 `backend-libxposed-101`。框架负责提供运行期 API，模块以 `compileOnly` 声明相应的编译依赖。[API 101 模块示例](sample-module-101/) · [API 102 模块示例](sample-module-102/)

把已解析的方法或构造器交给 `HookSpec`，声明回调和需要的参数访问权限。安装时检查每个目标的回执，模块退出时关闭注册。环绕回调每次调用至多执行一次原方法，回调借用的参数视图只在本次回调中有效。

## 按需选择模块

下表制品使用 groupId `io.github.jichuo1.meguviz.hook`。

| 制品 | 用途 |
| --- | --- |
| `hook-api`、`hook-core` | Java 公共接口、安装与回调分发 |
| `hook-dsl` | Kotlin 声明式写法 |
| `backend-libxposed-101`、`backend-libxposed-102` | 对应版本的 Android 框架适配 |
| `hook-ref-bridge`、`hook-dex-bridge` | 可选的成员定位与候选转换 |
| `hook-testing` | JVM 模块测试 |

## 文档

- [模块接入与生命周期](docs/module-entry-lifecycle.md) · [Kotlin DSL](hook-dsl/README.md) · [回调说明](docs/contracts/callback_contract.md)
- [高级 Hook 操作](docs/advanced-hooks.md) · [MeguvizRef 接入](hook-ref-bridge/README.md)
- [Apache License 2.0](LICENSE)
