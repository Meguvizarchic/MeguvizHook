# ADR-0003：限定逻辑 HookId 作用域，避免 API102 隐式替换

状态：Accepted（2026-11-01；源码、JVM/stub 与静态构建已验证，设备尚未验证）
范围：`HookEnvironment` 逻辑身份与 `backend-libxposed-102` 的初始安装、显式替换；本决策不自动启用热重载。
本文补充 ADR-0001 第 5 节，不改变其调用、在途快照及未知提交状态的合同。

## 背景

libxposed API102 的 `HookBuilder.setId` 将 ID 限定于同一模块和 executable；同一模块在同一
executable 上用相同框架 ID 新建 Hook 时，框架按合同原子替换旧 Hook，旧 handle 不再有效。
来源为 [API102 `XposedInterface` 源码](https://raw.githubusercontent.com/libxposed/api/102.0.0/api/src/main/java/io/github/libxposed/api/XposedInterface.java)
中的 `HookBuilder.setId` 与 `HookHandle.replaceHook`。这是一项上游源码合同，不是设备执行证据。

`HookEnvironment` 的注册表由每个环境分别维护。如果两个环境为同一模块、同一目标提供相同
`HookId`，将逻辑 ID 原样传给 `setId` 会使第二次安装在框架内替换第一次，而第一个环境并未参与
这次替换或更新其回执与句柄，因而可能仍报告旧注册已安装。这与显式替换和环境内的注册合同不符。

## 决策

1. `HookId` 是**单个 `HookEnvironment` 内的逻辑身份**。同一环境内重复 ID 仍按 ADR-0001 的完整
   语义、目标和注册状态处理；不同环境的相同 ID 是各自独立的注册，不进行跨环境的冲突协调、
   幂等比较或全局隔离。
2. API102 初次安装**默认不调用 `setId`**，使用上游默认的无 ID Hook；`HookId` 留在 SDK 自己的
   计划与回执中，不被自动赋为框架 Hook ID。调用方也可使用 `LibXposed102Backend.withFrameworkId`
   为单个 backend 实例显式指定框架 ID，须自行管理同模块、同目标的所有权：同 ID 安装会替换旧
   Hook，旧环境不会自动获知。未选择此选项的多个环境不会因相同逻辑 ID 触发框架的同 ID 替换。
3. API102 的显式单目标替换继续调用旧 handle 的 `replaceHook`，不使用卸载加安装模拟。API102
   源码明确 handle 形式也适用于无 ID Hook；成功后新 handle 承担卸载所有权，旧代、在途调用、
   失败与未知提交状态仍按 ADR-0001 处理。API101 不支持框架原生替换；SDK 可在保留同一框架入口时
   原子切换单目标 channel，边界见 ADR-0001。
4. 不提供通过框架 ID 查找或重建 SDK 注册。API102 模块热重载通过显式接手入口、调用方提供的
   跨代身份映射和生命周期管理实现；不能把环境局部 `HookId` 当作模块级全局框架 ID。

## 边界与验证

- 此决策不在不同环境之间协调相同 `HookId` 的冲突或关闭；它们的 Hook 仍由框架按自己的链规则
  组合，同优先级先后顺序不由本 SDK 保证。
- 本决策不协调已经由旧版本或其他代码在同一进程安装的具名 Hook，也不承诺运行中升级或热重载；
  不得凭本 ADR 推断旧具名句柄已经解除或可以安全重放。
- 需用具备 API102 同 ID 替换行为的 stub 覆盖：两个环境同 ID/同目标不互相替换，分别 close；
  显式 `replaceHook` 对无 ID Hook 生效，旧 close 不能卸载新代；同时保留本环境重复注册与失败路径
  测试。stub 不等于真实框架或设备资格。
- 部分框架路径曾在隔离环境进行功能检查；其他框架与设备仍需独立验证，不能仅凭源码或单测推断其行为。
