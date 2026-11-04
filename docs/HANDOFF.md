# 项目状态

Maven Central 已发布 `io.github.jichuo1.meguviz.hook` 的 `1.0.0`，包含 API、核心、DSL、测试工具和 API101／102 后端。当前源码默认开发版本为 `0.1.0-SNAPSHOT`，包含发布版之后的改动；不能假定已发布制品包含这些改动。可选的 `hook-ref-bridge` 不在既有 1.0.0 六组件中。

发布当前源码需要选择尚未占用的版本、固定源码及模块清单，并重新构建、核验和签名。发布要求及命令见[中央仓库发布说明](maven-central.md)。

当前源码包含 JVM Hook 核心、libxposed API101／102 后端、Kotlin DSL、可选 Ref 定位桥、模块入口和显式资源租约；高级操作及框架适配见[模块入口与生命周期](module-entry-lifecycle.md)和[高级操作](advanced-hooks.md)。

单目标替换提供原子切换与在途调用的代次隔离，跨目标不承诺原子性。安装和替换期间的外部调用仍可能与调用方或框架形成等待环；非等待关闭请求不等于清理完成。API102 热重载需要框架支持及模块自行处理生命周期和句柄交接；API101 没有相同的模块热重载生命周期。参见[关闭请求](close-request.md)和[热重载接入](hot-reload-102.md)。

诊断批量、压缩、Ref 会话和并行预解析均需显式选择。调用方负责后台执行、端点授权、会话与句柄生命周期；接收确认不代表持久化，失败后重试可能重复。详见[远程诊断](remote-diagnostics-102.md)、[批量轮询](trace-batch-poller.md)、[压缩会话](compression-session.md)和[预解析合同](prepared-install.md)。

JVM 合同、后端替身、公开 API 检查及部分 Android 构建已有覆盖；它们不能证明全部真实框架、ART、Binder、设备兼容或端到端性能。性能设计依据和验证边界见[性能研究](performance-research.md)。

当前源码还包含第 1 轮性能与易用性改动：类型化参数回调、101/102 按下标读参、通用分发直接返回值、诊断编码与队列快照减少复制，以及可选的捕获唤醒轮询。宿主 JVM 微基准与取舍见[性能研究](performance-research.md#第-1-轮优化当前开发源码)，接入步骤见[接入指南](integration-guide.md)。这些改动同样没有设备或真实框架证据。

第 2 轮在此基础上加入：`installAll` 与可选 `BatchMemberResolver`、101/102 的 flyweight 链传输、按争用自适应的在途计数，以及可选的到期评估、切片写入和更少分配的提交路径。见[性能研究](performance-research.md#第-2-轮优化当前开发源码)。在途数现在是非原子观察值：不为负、静止时精确，定义见 ADR-0001 §5。
