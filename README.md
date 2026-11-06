<div align="center">

<img src="docs/brand/MeguvizHook.svg" width="96" height="96" alt="MeguvizHook">

# MeguvizHook

**面向 Android 模块开发的 Hook SDK**

Java API · JVM 执行核心 · Kotlin DSL · libxposed 适配

<br>

[![License](https://img.shields.io/badge/license-Apache--2.0-00AEEC?style=flat-square&labelColor=2d3a55)](LICENSE)
[![Language](https://img.shields.io/badge/lang-Java-DEA584?style=flat-square&labelColor=2d3a55)](https://github.com/Meguvizarchic/MeguvizHook)
[![Issues](https://img.shields.io/github/issues/Meguvizarchic/MeguvizHook?style=flat-square&labelColor=2d3a55)](https://github.com/Meguvizarchic/MeguvizHook/issues)

[功能](#功能) · [模块](#模块) · [简洁声明](#简洁声明) · [构建与示例](#构建与示例) · [运行时合同](#运行时合同) · [验证范围](#验证范围) · [许可证](#许可证)

</div>

---

<details>
<summary><b>目录</b></summary>

- [功能](#功能)
- [模块](#模块)
- [简洁声明](#简洁声明)
- [构建与示例](#构建与示例)
- [运行时合同](#运行时合同)
- [验证范围](#验证范围)
- [许可证](#许可证)

</details>

---

MeguvizHook 是一个面向 Android 模块开发的 Hook SDK，提供独立的 Java API、JVM 执行核心、Kotlin DSL 和 libxposed API 101／102 适配器。实际方法拦截由运行框架完成；SDK 不替代框架管理器、注入器或 Android Runtime。

Maven Central 已发布 `1.0.0`；当前工作树的默认开发版本为 `0.1.0-SNAPSHOT`，包含尚未发布的后续更改。真实框架和设备行为取决于所选框架及宿主环境，尚不能根据 JVM 测试推断所有设备兼容性或性能收益。

## 功能

- 使用 `HookSpec` 描述目标、回调、参数访问和异常策略，安装后通过回执确认结果并管理注册生命周期。
- 支持 before、after、around、提前返回、参数改写、只读参数视图和显式常量替换；around 的 continuation 每次调用最多执行一次。
- 支持单目标原子替换和在途调用的代次隔离；API102 可显式接入框架模块热重载，API101 不提供同一模块热重载生命周期。
- 可选的回调异常跟踪、JSONL 记录及 API102 远程文件和 Binder 适配；默认不启动服务、线程或远程采集。
- 可选的同步批量诊断交付和有界压缩，支持 zlib、固定诊断字段字典、完整试压及 RAW 回退。双方须明确选择相同格式与限制。
- 诊断交付可显式选择锁外批量预约或由调用方提供执行器；压缩和 Ref 查找也提供显式生命周期的可复用会话。当前开发版本的压缩会话可选择输出缓冲策略，详见 [压缩会话](docs/compression-session.md)。
- 可选的模块入口路由、显式资源租约及 API101/102 高级操作；可选 MeguvizRef 桥提供可扩展的成员定位。
- 当前开发源码减少了每次调用的分配和包装：101/102 按下标读取参数、通用分发直接返回值、参数改写和
  around 在首次访问时才取参数；诊断编码与队列快照减少复制，并可选择由捕获直接唤醒轮询。宿主 JVM
  微基准与取舍见[性能研究记录](docs/performance-research.md#第-1-轮优化当前开发源码)，尚无设备证据。
- 第 2 轮：`installAll` 批量安装、101/102 不再每次分配适配器对象、多线程命中同一 Hook 时在途计数不再争用
  同一缓存行，以及可选的到期即发送与切片写入，见[第 2 轮记录](docs/performance-research.md#第-2-轮优化当前开发源码)。

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
| `hook-ref-bridge` | 可选 MeguvizRef 1.0.0 成员定位桥；Java 17，不进入核心默认依赖 |
| `sample-java-contracts`、`sample-kotlin-contracts` | 在 JVM fake backend 上运行的消费示例 |
| `sample-module-101`、`sample-module-102`、`sample-module-kotlin-102` | Android 模块入口示例 |

选择与运行框架一致的一代后端；框架 API 由运行时提供。SDK 本身不自动发现服务、管理模块作用范围或配置框架权限。
建议将 API、核心和后端统一为同一发行版本。较新的高级操作依赖对应版本的核心；强制将新后端与
旧核心混用，可能在混淆构建或运行时遇到缺失类型。

## 简洁声明

Kotlin 可以复用模块 owner，并直接传入已解析的方法；返回的仍是完整安装回执：

```kotlin
val hooks = environment.forOwner("my-module")
val receipt = hooks.install("feature.title", resolvedMethod) {
    replaceConstant("")
}
// 检查 receipt.isInstalled 和其他状态，调用方仍负责关闭注册。
```

Java 可使用 `HookSpec.forMember("feature.title", "my-module", resolvedMethod)` 获得
普通 Builder。类型令牌、构造器、条件替换和完整示例见 [Kotlin DSL](hook-dsl/README.md)
及 [Java 消费样例](sample-java-contracts/src/main/java/io/github/jichuo1/meguviz/hook/sample/java/JavaContractsSample.java)。

需要读写参数时，当前开发源码的类型化回调直接给出对应调用类型，并自动选择参数形状：

```kotlin
hooks.install("feature.title", resolvedMethod) {
    beforeArgs { log(it.getArgument(0)) }                 // READ_ARGS_VIEW
    beforeMutableArgs { it.setArgument(0, "rewritten") }  // MUTABLE_ARGS
}
```

Java 对应 `beforeArgs`、`beforeMutableArgs`、`afterArgs`。模块启动时一次安装多个 Hook 用
`installAll`（Kotlin：`hooks.installAll { hook(id, target) { ... } }`），同一个类只加载一次、只复制一次成员表。
从依赖到诊断和关闭的完整步骤见[接入指南](docs/integration-guide.md)。

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

Maven Central 的已发布制品与后续发布准备、独立消费检查和签名打包步骤见
[中央仓库发布说明](docs/maven-central.md)。已发布的 `1.0.0` 和此前本地签名候选均不包含
当前工作树的后续更改；本地构建不会自动更新中央仓库。

从源码接入时，请参考 [Java 合同示例](sample-java-contracts/src/main/java/io/github/jichuo1/meguviz/hook/sample/java/JavaContractsSample.java)、[API101 模块](sample-module-101/) 和 [API102 模块](sample-module-102/)。示例中的目标、作用范围和框架生命周期由实际模块自行配置；fake backend 仅用于测试。

## 运行时合同

- 入参快照只复制参数槽，不深拷贝对象；回调借用视图不可在回调结束后或跨线程使用。
- 默认异常策略不会重放原方法；原方法异常的身份和致命 VM 错误按合同保留。
- 多目标安装逐项返回回执，不承诺跨目标原子替换；单目标替换与关闭仍有框架能力和生命周期边界。
- 显式异常诊断只记录调用方包装的回调，不是全局崩溃捕获；日志与堆栈可能包含敏感信息。
- 批帧、压缩帧和 JSONL 是不同格式；未配对的旧接收端不能直接读取新格式。压缩帧的校验和不提供认证或保密。

完整语义见 [回调合同](docs/contracts/callback_contract.md)、[运行时合同 ADR](docs/adr/ADR-0001-runtime-contract.md)、[Hook 身份作用域 ADR](docs/adr/ADR-0003-hook-identity-scope.md) 和 [API102 热重载](docs/hot-reload-102.md)。
扩展使用边界见 [模块入口与生命周期](docs/module-entry-lifecycle.md)、
[高级 Hook 操作](docs/advanced-hooks.md) 和 [MeguvizRef 桥接](hook-ref-bridge/README.md)。
多个描述符需要并发定位时，可参考[显式并行预解析与顺序安装](docs/prepared-install.md)。
需要在 backend 回调中提出关闭请求时，参见[显式请求关闭环境](docs/close-request.md)。
诊断用法见 [非等待预约](docs/nonblocking-trace-delivery.md)、
[调用方执行器](docs/trace-batch-dispatcher.md)、[后台批量轮询](docs/trace-batch-poller.md)
和 [压缩会话](docs/compression-session.md)；
[性能研究记录](docs/performance-research.md) 区分资料、实验假设与实际证据；JVM 微基准位于
`benchmark-jvm`（`./gradlew :benchmark-jvm:jmh`，不发布，不代表 ART）。

## 验证范围

JVM 合同、两代后端的桩测试、公开 API 检查和受限 Android 制品构建已有覆盖；部分框架路径曾在隔离环境中做过功能检查。批量与压缩已通过本地边界和组合测试，但真实 Binder 身份、跨进程投递、进程重启后的可靠性及端到端性能仍需独立验证。项目尚未建立正式的设备性能资格，不承诺相对原生 API 的性能提升。

## 许可证

见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。
