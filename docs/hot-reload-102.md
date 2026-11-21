# API 102 模块热重载接入

API 102 的模块热重载会在同一目标进程中创建新的模块代码代次，这与在同一 `HookEnvironment` 内调用 `replace` 更新回调属于完全不同的生命周期事件。以下指南仅适用于支持 API 102 热重载的宿主框架（API 101 不提供此生命周期）。

> **适用范围与验证说明**：以下约定基于 libxposed API `102.0.0` 接口合同与 JVM 框架替身验证，真实设备上的热重载支持、触发方式与跨代性能需在目标环境单独验证。

## 接入前提

- **按需启用元数据**：如期望在更新模块 APK 时自动触发热重载，需在模块元数据中配置 `autoHotReload=true`，且旧代 `onHotReloading` 须显式返回 `true`。SDK 不会自动代为开启该配置。
- **建立无歧义的跨代映射**：旧代必须持久化记录足以唯一识别每个待移交 Hook 的跨类加载器中性元数据，新代据此将框架交来的每个旧 `HookHandle` 精确对应到新的 `HookSpec`。切勿仅按目标方法猜映射（同一模块可对同一方法挂多个 Hook，且 `HookHandle` 不暴露 `owner`、`priority` 或异常策略）。
- **禁止跨代传递旧 SDK 对象**：SDK 的 `HookId` 仅属于各自的 `HookEnvironment`，不等于全局框架 ID，也不可依赖 `getOldHookHandles()` 的列表顺序。Saved state 中只能放入基础类型、字符串及其数组等中性数据，禁止放入旧 `HookEnvironment`、回调、`HookRegistration` 或模块自定义类实例。

## 生命周期协作骨架

下面示例中的 `module*` 方法代表业务模块自行实现并审查的逻辑：

```java
// 旧模块实例：保留已安装的框架 Hook，交由新代接管。
@Override
public boolean onHotReloading(HotReloadingParam param) {
    Object neutralState = moduleBuildNeutralState();
    // 停止本模块自己启动的线程、外部回调、native Hook 与 JNI 引用。
    // 若无法确认清理完成，返回 false 拒绝热重载。
    if (!moduleStopOwnedResources()) return false;
    param.setSavedInstanceState(neutralState);
    // 切勿在此调用旧 environment.close()：否则会提前卸载待移交的框架 Hook。
    return true;
}

// 新模块实例：热重载时框架不会重新回调 onModuleLoaded / onPackageReady。
@Override
public void onHotReloaded(HotReloadedParam param) {
    Object state = param.getSavedInstanceState();
    LibXposed102Backend backend = new LibXposed102Backend(this,
            new LibXposed102Context(param.getProcessName(), moduleHostLoader(), moduleNewEpoch(state)));
    HookEnvironment env = HookEnvironment.create(backend);

    // 每次热重载回调内创建并关闭专属 session，禁止跨回调缓存 session 或 adoption 令牌。
    try (LibXposed102ReloadSession session = new LibXposed102ReloadSession(backend, param)) {
        for (XposedInterface.HookHandle old : session.oldHandles()) {
            HookSpec next = moduleResolveUnambiguousSpec(old, state);
            if (next == null) {
                moduleHandleUnclaimedOldHook(old); // 由模块显式决定 unhook 或保留记录
                continue;
            }
            RegistrationReceipt receipt = env.adopt(next, session.adoption(old));
            if (!receipt.isInstalled()) {
                moduleHandleAdoptionReceipt(old, receipt);
            }
        }
        moduleKeepNewEnvironment(env); // 交由新代模块生命周期托管直至退出
    }
}
```

`moduleHostLoader()` 必须返回宿主目标实际使用的 `ClassLoader`，而非新模块自身的加载器。

## 非阻塞请求关闭与重入保护

- 接管期间，`LibXposed102ReloadSession` 会持锁调用框架句柄的检查与替换方法（`close()` 与 `oldHandles()` 同样获取该锁）。若外部回调线程需要避免因请求关闭而互等，可调用 `session.requestClose()`（不加锁、不调用框架、不启动线程），并由另一条不在等待链中的协调路径在 **`onHotReloaded` 返回前** 调用同步 `session.close()`。
- `isCloseRequested()` 仅表示关闭请求已发起或同步关闭已启动，不能证明接管或清理已结束。若关闭请求先于接管入口检查到达，`oldHandles()` 与签发令牌会拒绝，未经确认的 `env.adopt` 会返回确定未提交的 `FAILED` 回执，不会调用框架替换。
- 若框架在检查旧句柄时重入关闭了同一个 `session` 且令牌尚未调用替换，SDK 会判定本次接管确定未提交；若该令牌在嵌套调用中已尝试替换，外层则按未知结果保守隔离，不再重复替换。

## 失败处理与结果语义

- **单目标原子替换**：API 102 的 `replaceHook` 会保留原目标、优先级、异常策略和框架 ID，返回新句柄后旧句柄失效，改由新代 SDK 注册持有卸载权。
- **异常策略须显式一致**：`session.adoption(old)` 默认按 `ErrorPolicy.PROPAGATE` 声明；若旧代采用了 `FRAMEWORK_PROTECTIVE`，必须显式调用 `session.adoption(old, ErrorPolicy.FRAMEWORK_PROTECTIVE)` 且新 `HookSpec` 使用相同策略。框架句柄不提供策略 getter，此一致性需由模块自己保证。
- **隔离未知结果**：若接管过程中抛出未声明异常、`Error`、返回空句柄，或提交后发生身份漂移，切勿假设旧 Hook 仍然完好。SDK 会停用新通道、隔离该 `HookId` 并保留新句柄；**切勿对可能已失效的旧句柄重试接管或盲目 `unhook()`**。
- **不承诺跨 Hook 原子性与即时内存回收**：多 Hook 逐一接管时不保证整体原子切换；已在途调用按原快照继续完成，旧代类加载器与 native 资源何时回收由 ART 运行时决定。

上游合同参考：[libxposed API 102 生命周期](https://github.com/libxposed/api/blob/102.0.0/api/src/main/java/io/github/libxposed/api/XposedModuleInterface.java) 与 [HookHandle 替换合同](https://github.com/libxposed/api/blob/102.0.0/api/src/main/java/io/github/libxposed/api/XposedInterface.java)。
