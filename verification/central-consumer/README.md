# 独立 Maven 消费端验证

本独立 Gradle 工程仅从显式指定的 Maven 仓库解析 MeguvizHook 制品，不包含对源码子工程的 `project(...)` 依赖，也不执行真机操作。Android 消费端分别为 API 101、API 102 与可选 Ref 桥建立独立应用，后端应用各自把对应框架 API 声明为 `compileOnly`；JVM 消费端则针对 `FakeBackend` 安装并关闭 Hook，并经由真实 Ref 桥验证描述符解析。

请在仓库根目录依次运行两种元数据解析模式（把仓库 URI 与版本号替换为实际候选；本地 staging 请使用绝对 `file:` URI，工具链仍需 Android SDK `37.2` 与 `build-tools 37.0.0`）：

```sh
./gradlew -p verification/central-consumer verifyCandidate \
  -PcentralRepo=file:///absolute/path/to/maven/repository -PcentralVersion=1.2.0 \
  -PmetadataMode=gradle --no-daemon
python verification/central-consumer/verify_apks.py

./gradlew -p verification/central-consumer verifyCandidate \
  -PcentralRepo=file:///absolute/path/to/maven/repository -PcentralVersion=1.2.0 \
  -PmetadataMode=pom --no-daemon
python verification/central-consumer/verify_apks.py
```

- **`gradle` 模式**：强制通过 Gradle Module Metadata 解析，不回退 POM；**`pom` 模式**：忽略 Gradle 元数据重定向，纯按 POM 解析。两者共同验证传递依赖、候选版本号、各后端 `compileOnly` 框架类编译期可用且运行时不打包。
- Ref 桥消费端会校验 `io.github.jichuo1.meguviz:meguviz-ref-core:1.2.0`（或对应基线）传递依赖；`verify_apks.py` 会检查输出 APK 的 DEX 类列表，确认包含桥与 Ref 类并拒绝打包入框架类或对立代次后端（如需保留两种模式的产物证据，每次运行后请及时检查，第二次 Gradle 运行会覆盖调试 APK）。

> **适用范围与验证说明**：APK 打包校验与基于 `FakeBackend` 的 JVM 执行不代表真实 Android 设备、Binder 通信或宿主框架拦截行为。
