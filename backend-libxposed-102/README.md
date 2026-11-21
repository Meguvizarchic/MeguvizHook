# API 102 框架 ID

默认构造 `new LibXposed102Backend(framework, context)` 不调用框架的 `setId`。SDK 逻辑 `HookId` 仅在各自的 `HookEnvironment` 内有效，不会自动映射为框架 ID。

确实需要使用 API 102 具名 Hook 时，请通过显式工厂方法指定由调用方管理的框架 ID：

```java
LibXposed102Backend backend = LibXposed102Backend.withFrameworkId(
        framework, context, "my-module.owned-hook");
HookEnvironment environment = HookEnvironment.create(backend);
```

- 该 ID 会用于此后端实例初次安装的每一个目标；若同一模块在同一目标方法上再次使用相同框架 ID 安装，可能按 API 102 合同原子替换既有 Hook 并使旧句柄失效，而旧 `HookEnvironment` 不会自动收到通知，调用方须自行协调 ID 归属。不同目标方法使用相同 ID 不构成冲突。
- `environment.replace(...)` 基于已有 `HookHandle` 调用 `replaceHook`，会自动保留原框架 ID；匿名 Hook 同样支持通过句柄替换。此处行为基于 API 102 接口合同与 JVM 替身测试，实际设备表现仍需在目标框架验证。
