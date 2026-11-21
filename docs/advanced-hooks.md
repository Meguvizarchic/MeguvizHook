# 可选高级 Hook 操作

`AdvancedHookOperations` 是独立于普通 `HookEnvironment` 的扩展接口，核心层不依赖 Android 或特定框架。API 101 与 API 102 分别提供 `LibXposed101Advanced` 和 `LibXposed102Advanced` 适配器，由调用方传入已附着的 `XposedInterface` 并负责其生命周期；第三方后端也可自行实现同一接口。

> **适用范围与验证说明**：以下说明与 JVM 测试、框架替身测试覆盖 API 合同本身，不等同于真实 ART 运行时、类初始化竞态时序或特定设备框架的完整验证。

## 能力探测

`availability(feature)` 返回各后端当前提供的能力证据。API 101/102 适配器不会仅凭 API 版本号宣称已在设备上通过验证，因此返回 `UNKNOWN`；调用方必须妥善处理框架实际抛出的不支持异常或运行时错误，不能把 `UNKNOWN` 当成可用保证。

## 支持的操作与语义边界

- **`deoptimizeCaller`**：针对包含目标调用的调用方方法或构造器执行去优化。它不自动搜索全工程所有调用方，也不保证一定能解除内联或命中后续 Hook。
- **`invoke` / `invokeSpecial` 与构造器调用**：必须显式选择 `ORIGINAL` 或 `FULL_CHAIN`。`FULL_CHAIN` 会发起一次新的完整拦截链调用，可能重新进入当前 Hook（不同于当前回调中的 `proceed()`）；`invokeOriginal` 仅调用原方法路径。异常按框架调用合同传播，不做反射回退或自动重试。
- **`newInstanceSpecial`**：可使用父类构造器创建子类实例，但子类构造器不会运行，子类字段可能仍为默认值，调用方必须自行保证对象状态完整。
- **`hookClassInitializer`**：显式的受管 `<clinit>` 拦截注册。回调中必须执行一次 `proceed()` 或 `skip()`（跳过会改变类初始化语义）。已经完成初始化的类无法补发事件，安装发布前并发发生的初始化也可能直接错过。关闭时先拒绝新回调再尝试一次卸载，不中断已在途的调用，卸载失败时不自动重试。

## 借用与异常策略边界

回调借用对象仅在当前回调线程且回调返回前有效。选择 `ErrorPolicy.FRAMEWORK_PROTECTIVE` 时，由宿主框架决定异常逃逸后的保护与恢复方式；框架在 SDK 回调退出后自行执行的回退下游，不计入 SDK 的在途调用计数。
