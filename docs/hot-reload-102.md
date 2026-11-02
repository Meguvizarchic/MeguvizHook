# API102 模块热重载接入

API102 的模块热重载会在同一目标进程中创建新的模块代码代次；它与同一 `HookEnvironment`
内调用 `replace` 更新回调不是同一件事。以下接入仅适用于提供 API102 热重载生命周期的框架；
API101 没有这组模块生命周期。框架支持、模块正确接入和真实设备表现需要分别验证。

## 接入前提

- 模块入口按需启用框架的热重载触发方式；如选择更新 APK 时触发，需在模块元数据中配置
  `autoHotReload=true`，并且旧代 `onHotReloading` 仍须明确返回 `true`。SDK 不替应用启用它。
- 模块建立自己的跨代映射。旧代必须记录足以**无歧义**识别待移交 Hook 的中性元数据；新代明确
  将框架交付的每个旧 handle 与新的 `HookSpec` 对应。不要仅根据 executable 猜测：同一模块可在
  同一目标上有多个 Hook，且框架 handle 不暴露完整的 owner、priority 和异常模式。
- SDK 的普通 API102 安装不设置框架 ID，SDK 的 `HookId` 仅在各自的 `HookEnvironment` 内有效。
  因此不能把 `HookId` 直接当成框架全局 ID，也不能依赖 `getOldHookHandles()` 的列表顺序。
  如果无法建立唯一、可信的对应关系，就不要接管该 handle；由模块按自己的明确策略处理。
- 跨代只传不依赖旧模块类加载器的值，例如基础类型、字符串及其数组。不得将旧 `HookEnvironment`、
  回调、SDK 注册、模块自定义对象或包含它们的对象图放进 saved state。新代必须自行重建自己的
  backend、环境、回调和描述符。

## 生命周期示意

下面展示调用顺序；`module*` 方法代表模块自行实现并审查的业务逻辑，不是 SDK 内置函数。
模块负责保证映射和清理条件，SDK 无法代替它验证这些约束。

```java
// 旧模块实例：保留已经安装的框架 Hook 供新代接管。
@Override
public boolean onHotReloading(HotReloadingParam param) {
    // 只保存基础类型、字符串等跨类加载器中性数据。
    Object neutralState = moduleBuildNeutralState();
    // 停止本模块拥有的线程、外部回调、native hook 和 JNI 引用等。
    // 若不能确认清理完成，拒绝重载；不要承诺安全交接。
    if (!moduleStopOwnedResources()) return false;
    param.setSavedInstanceState(neutralState);
    // 不要在此调用旧 environment.close()：它会卸载待移交的框架 Hook。
    return true;
}

// 新模块实例：框架不会重放 onModuleLoaded / package 生命周期。
@Override
public void onHotReloaded(HotReloadedParam param) {
    Object state = param.getSavedInstanceState();
    // 从新代实际的宿主目标类加载器和进程信息重建上下文；epoch 使用模块自己的代次方案。
    LibXposed102Backend backend = new LibXposed102Backend(this,
            new LibXposed102Context(param.getProcessName(), moduleHostLoader(), moduleNewEpoch(state)));
    HookEnvironment env = HookEnvironment.create(backend);

    // 每次框架回调只创建一个 session，并在回调返回前关闭；不能跨回调缓存 session/token。
    try (LibXposed102ReloadSession session = new LibXposed102ReloadSession(backend, param)) {
        for (XposedInterface.HookHandle old : session.oldHandles()) {
            // 依据可信的跨代元数据和模块自己的安装记录做唯一匹配。
            // 同时确认目标、owner、priority、异常模式与旧注册兼容；无法确认时返回 null。
            HookSpec next = moduleResolveUnambiguousSpec(old, state);
            if (next == null) {
                moduleHandleUnclaimedOldHook(old); // 模块显式决定是否 unhook 或保留并报告
                continue;
            }
            RegistrationReceipt receipt = env.adopt(next, session.adoption(old));
            if (!receipt.isInstalled()) {
                moduleHandleAdoptionReceipt(old, receipt);
            }
        }
        moduleKeepNewEnvironment(env); // 按模块生命周期持有，并在最终关闭时处理
    }
}
```

示意中的 host loader 必须是目标实际使用的类加载器，而不是新模块自身的类加载器；模块可用
自己的可靠记录或其他经过验证的宿主信息重建它。`moduleResolveUnambiguousSpec` 必须在接管前
验证目标和旧代属性，不能按 `oldHandles()` 的序号匹配。对既没有唯一映射又不属于模块管理范围
的 handle，不要擅自处理。

## 结果和失败处理

- 成功接管只覆盖一个已解析目标：API102 `replaceHook` 保留原框架目标、优先级、异常处理模式和
  框架 ID，返回新 handle 后旧 handle 失效。新代 SDK 注册持有卸载权；旧代的对象不能负责卸载新代。
- `session.adoption(old)` 按旧代 `PROPAGATE` 声明；旧代使用 `FRAMEWORK_PROTECTIVE` 时，必须显式
  使用 `session.adoption(old, ErrorPolicy.FRAMEWORK_PROTECTIVE)` 且新计划选择同一策略。框架 handle
  没有模式 getter，此声明由模块保证，SDK 无法独立验证；错误声明不能安全接管。
- `FAILED` 回执表示 bridge 确认本次未提交，旧 hook 仍有效；模块可按明确策略处理。预检查
  拒绝并不表示完成了交接。
- 未检查异常、`Error`、空 handle，以及接管提交后身份改变或发布失败，都不能假定旧 hook 仍在。
  新 channel 会被停用、相关 ID 被隔离，已取得的 handle 会保留；**不要对可能已失效的旧 handle
  重试接管或盲目 unhook**。模块应记录并报告异常情况，由其生命周期策略决定后续处理。
- 每个旧 handle 是否接管、移除或保留，均由模块负责。对未尝试接管且模块明确拥有的 handle，
  可根据模块政策调用 `unhook()`；不应把这套政策应用到已提交或结果未知的 handle。
- 替换是单目标行为，不保证多个 Hook 共同原子切换；在途调用按原有快照继续，旧模块类加载器及
  native 制品何时释放由运行时决定。不要把该功能表述成物理卸载保证。
- session 是否只在框架回调内使用、目标的真实身份和模块资源是否清理完毕，最终都由调用方保证。
  保留 session/旧 handle 副本可能延长旧代对象的生命周期。

上游合同见 [libxposed API102 生命周期](https://github.com/libxposed/api/blob/102.0.0/api/src/main/java/io/github/libxposed/api/XposedModuleInterface.java)
和 [HookHandle 替换合同](https://github.com/libxposed/api/blob/102.0.0/api/src/main/java/io/github/libxposed/api/XposedInterface.java)。
