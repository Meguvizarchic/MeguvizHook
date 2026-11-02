# 构建与工具链

本表记录仓库当前配置；一次构建成功只证明该构建所覆盖的任务和环境。具体版本以配置文件为准，设备运行与性能需要单独验证。

| 项目 | 配置来源 |
| --- | --- |
| Gradle | `gradle/wrapper/gradle-wrapper.properties` 中锁定发行版及校验和 |
| Android Gradle Plugin、Kotlin、libxposed、JUnit | `gradle/libs.versions.toml` |
| Android compile/min/target SDK | `gradle.properties` 与各 Android 模块的 Gradle 配置；compile SDK minor 由模块配置指定 |
| JVM 核心 | Java 8 字节码目标，见根 `build.gradle.kts` |
| Android 适配器与样例 | 各模块构建文件中的 Java 和 Android 配置 |
| CI 所用 JDK 和 Android SDK | `.github/workflows/verify.yml`；实际运行结论以对应提交的 CI 状态为准 |

## 验证入口

- `./gradlew :hook-core:test :hook-dsl:test :hook-testing:test`：纯 JVM 合同与消费测试。
- `./gradlew :backend-libxposed-101:testDebugUnitTest :backend-libxposed-102:testDebugUnitTest`：两代后端的 JVM/stub 测试。
- `./gradlew apiCheck`：按 `work/hook/api_dump_hook-*.txt` 检查公开签名；签名有意更改时先审查兼容性，再更新对应清单。
- `./gradlew lint`：Android lint；各模块 `assembleRelease` 构建发布变体，不等于已签名、已安装或已在框架中运行。

实际执行的完整任务列表、依赖隔离和检查步骤见 CI 工作流。单次任务的成功不会自动证明其他任务、设备、ART、Binder 或性能通过。
