# 能力矩阵

本表描述 SDK 的实现范围和已覆盖的测试类型；源码、代理测试、真实框架运行与性能结论分别判断。`UNKNOWN` 不视为支持。

| 能力 | JVM fake | API101 后端 | API102 后端 |
| --- | --- | --- | --- |
| 框架调用链 | 模拟 | 适配 101 Hooker/Chain | 适配 102 Hooker/Chain |
| 多回调组合 | 同一 core | 同一 core | 同一 core |
| classic callback 后端 | 不支持 | 不支持 | 不支持 |
| 框架保护异常策略 | 未模拟，不声明支持 | 显式映射 `PROTECTIVE` | 显式映射 `PROTECTIVE` |
| 单目标原子替换 | fake channel 切换 | 单框架入口内切换 SDK channel，不是框架原生替换 | 使用框架 handle 的 `replaceHook` |
| 模块级热重载 | 不支持 | 无 API102 热重载生命周期 | 提供显式旧句柄接手；框架能力、映射和资源清理由模块负责 |
| 目标选择 | FIRST、UNIQUE、ALL；ALL 提供逐目标回执 | 同一 core | 同一 core |
| 可选异常诊断 | 显式回调包装、有界队列、批量与压缩 | 可使用 core 能力 | 可组合调用方提供的 Binder、只读 Remote Files 和模块侧写入 |
| native / JNI | 无生产模块 | 无生产模块 | 无生产模块 |

fake 同一目标上的多个注册按优先级和不可变快照执行；相同优先级的确定性顺序不代表真实框架的保证。API102 初装不设置框架 ID，详见 [身份约束](../../docs/adr/ADR-0003-hook-identity-scope.md)；热重载接入见 [API102 模块热重载](../../docs/hot-reload-102.md)。

异常诊断和压缩均默认关闭，批量与压缩格式必须由收发双方显式配对。真实 Binder、Remote Files、热重载生命周期、跨版本框架行为和端到端性能需要各自的运行时验证；本表不能作为完整设备资格证明。
