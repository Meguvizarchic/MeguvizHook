# Java 消费与依赖边界

`hook-api` 和 `hook-core` 是纯 JVM 模块，构建为 Java 8 兼容字节码；Java 使用者不需要启用 Kotlin DSL、Android 运行时或代码生成器。两代 libxposed 后端是独立 Android 适配器，调用方按目标框架选择对应制品。

## 公开 API 检查

实际签名以 `work/hook/api_dump_hook-api.txt`、`work/hook/api_dump_hook-core.txt` 和 `work/hook/api_dump_hook-dsl.txt` 为准。运行 `./gradlew apiCheck` 检查构建产物与清单的一致性；这是当前签名快照核对，不自动证明旧制品的所有二进制兼容路径或 Android 运行行为。

`sample-java-contracts` 展示纯 Java 消费；Android 样例分别通过手工模块入口接入对应代际。框架依赖的解析与运行时隔离按各模块的 Gradle 配置及 CI 检查判断。

## 可选能力

热重载、异常诊断、批量传输和压缩均需要调用方明确配置；普通 Hook 路径不自动创建 service、后台线程或 IPC。API102 可选远程文件和 Binder 接入、发送确认及持久化边界见 [远程诊断说明](../../docs/remote-diagnostics-102.md)。项目不包含生产 native/JNI 模块。

设备上的框架加载、不同类加载器和版本组合、实际权限及性能，均不能由纯 JVM 消费测试推断。
