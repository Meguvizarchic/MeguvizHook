# MeguvizHook

MeguvizHook 是一个面向 Android 模块开发的 Hook SDK，提供独立的 Java API、JVM 执行核心、Kotlin DSL 和 libxposed API 101／102 适配器。实际方法拦截由运行框架完成；SDK 不替代框架管理器、注入器或 Android Runtime。

当前版本为 `0.1.0-SNAPSHOT`，仍在开发中。真实框架和设备行为取决于所选框架及宿主环境，尚不能根据 JVM 测试推断所有设备兼容性或性能收益。

## 功能

- 使用 `HookSpec` 描述目标、回调、参数访问和异常策略，安装后通过回执确认结果并管理注册生命周期。
- 支持 before、after、around、提前返回、参数改写、只读参数视图和显式常量替换；around 的 continuation 每次调用最多执行一次。
- 支持单目标原子替换和在途调用的代次隔离；API102 可显式接入框架模块热重载，API101 不提供同一模块热重载生命周期。
- 可选的回调异常跟踪、JSONL 记录及 API102 远程文件和 Binder 适配；默认不启动服务、线程或远程采集。
- 可选的同步批量诊断交付和有界压缩，支持 zlib、固定诊断字段字典、完整试压及 RAW 回退。双方须明确选择相同格式与限制。

批量交付确认的含义取决于接收端：接收回调正常返回不代表持久化。发送失败可能已有副作用，显式重试可能重复；跨进程使用者仍需处理授权、连接、队列容量、隐私和保存策略。参见 [异常诊断与远程传输](docs/remote-diagnostics-102.md)。

## 模块

| 模块 | 内容 |
| --- | --- |
| `hook-api` | 公共类型、参数形状、回执及生命周期合同 |
| `hook-core` | 计划构建、分发和诊断；Java 8 字节码 |
| `hook-dsl` | 可选 Kotlin DSL |
| `backend-libxposed-101` | libxposed API 101 后端适配 |
| `backend-libxposed-102` | libxposed API 102 后端适配 |
| `hook-testing` | JVM fake backend 与合同验证工具 |
| `sample-java-contracts`、`sample-kotlin-contracts` | 在 JVM fake backend 上运行的消费示例 |
| `sample-module-101`、`sample-module-102`、`sample-module-kotlin-102` | Android 模块入口示例 |

选择与运行框架一致的一代后端；框架 API 由运行时提供。SDK 本身不自动发现服务、管理模块作用范围或配置框架权限。

## 构建与示例

工具链以仓库版本目录与构建配置为准：Gradle 9.8.2、AGP 9.4.1、Kotlin 2.4.21、Android compile SDK 37.2、min SDK 27；本地构建使用 JDK 17 或兼容版本。

```bash
git clone https://github.com/Meguvizarchic/MeguvizHook.git
cd MeguvizHook

./gradlew :hook-core:test :hook-dsl:test :hook-testing:test
./gradlew :backend-libxposed-101:testDebugUnitTest :backend-libxposed-102:testDebugUnitTest
./gradlew :sample-java-contracts:run :sample-kotlin-contracts:run
./gradlew apiCheck
./gradlew :backend-libxposed-101:assembleRelease :backend-libxposed-102:assembleRelease
```

需要 Android SDK platform 37.2 和 build-tools 37.0.0。Windows 可使用 `gradlew.bat`。Android 的单元测试使用框架接口与平台桩，不等同于真实 Hook 或跨进程测试。

从源码接入时，请参考 [Java 合同示例](sample-java-contracts/src/main/java/io/github/jichuo1/meguviz/hook/sample/java/JavaContractsSample.java)、[API101 模块](sample-module-101/) 和 [API102 模块](sample-module-102/)。示例中的目标、作用范围和框架生命周期由实际模块自行配置；fake backend 仅用于测试。

## 运行时合同

- 入参快照只复制参数槽，不深拷贝对象；回调借用视图不可在回调结束后或跨线程使用。
- 默认异常策略不会重放原方法；原方法异常的身份和致命 VM 错误按合同保留。
- 多目标安装逐项返回回执，不承诺跨目标原子替换；单目标替换与关闭仍有框架能力和生命周期边界。
- 显式异常诊断只记录调用方包装的回调，不是全局崩溃捕获；日志与堆栈可能包含敏感信息。
- 批帧、压缩帧和 JSONL 是不同格式；未配对的旧接收端不能直接读取新格式。压缩帧的校验和不提供认证或保密。

完整语义见 [回调合同](docs/contracts/callback_contract.md)、[运行时合同 ADR](docs/adr/ADR-0001-runtime-contract.md)、[Hook 身份作用域 ADR](docs/adr/ADR-0003-hook-identity-scope.md) 和 [API102 热重载](docs/hot-reload-102.md)。

## 验证范围

JVM 合同、两代后端的桩测试、公开 API 检查和受限 Android 制品构建已有覆盖；部分框架路径曾在隔离环境中做过功能检查。批量与压缩已通过本地边界和组合测试，但真实 Binder 身份、跨进程投递、进程重启后的可靠性及端到端性能仍需独立验证。项目尚未建立正式的设备性能资格，不承诺相对原生 API 的性能提升。

## 许可证

见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。
