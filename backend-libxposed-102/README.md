# API102 框架 ID

默认构造 `new LibXposed102Backend(framework, context)` 不调用框架的 `setId`。SDK 逻辑 Hook ID 只属于对应的 `HookEnvironment`，不会自动转换成框架 ID。

确实需要 API102 的具名 Hook 时，明确选择一个由调用方管理的 ID：

```java
LibXposed102Backend backend = LibXposed102Backend.withFrameworkId(
        framework, context, "my-module.owned-hook");
HookEnvironment environment = HookEnvironment.create(backend);
```

该 ID 用于这个后端实例初次安装的每个目标；同一模块在同一目标上再次安装相同框架 ID 可能按 API102 合同原子替换已有 Hook，原 handle 失效。旧 `HookEnvironment` 不会因此自动收到状态变更通知，调用方须协调 ID 所有权。不同目标使用同一 ID 不构成该冲突。

`environment.replace(...)` 仍使用已有 handle 的 `replaceHook`，保留框架 ID；匿名 Hook 同样支持 handle 替换。此处描述的是 API 合同和 JVM 测试假体，不保证特定设备框架的实际表现。
