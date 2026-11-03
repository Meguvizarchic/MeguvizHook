# 可选高级 Hook 操作

`AdvancedHookOperations` 是独立于普通 `HookEnvironment` 的扩展接口，核心不依赖
Android 或指定框架。API101/102 分别提供 `LibXposed101Advanced` 和
`LibXposed102Advanced` 适配器；调用方显式提供已附加的框架接口并负责其生命周期。
对其他后端可自行实现同一接口。

`availability(feature)` 返回各后端当前可提供的证据。API101/102 适配器不会通过
版本号把操作认定为已在设备上验证，因此返回 `UNKNOWN`；调用者必须处理框架抛出的
不支持和运行时错误，不应把 `UNKNOWN` 视为能力已确认。

- `deoptimizeCaller` 接受包含目标调用的调用方方法或构造器。它不查找所有调用方，
  也不保证解除内联或后续 Hook 一定命中。
- `invoke` / `invokeSpecial` 和构造器调用必须显式选择 `ORIGINAL` 或 `FULL_CHAIN`。
  后者是新的完整链调用，可能重新进入当前 Hook；它不是当前回调的 `proceed()`。
  `invokeOriginal` 只选择原方法路径。异常按框架调用合同传播，不自动重试或反射回退。
- `newInstanceSpecial` 可使用父类构造器创建子类，子类构造器不会运行，字段可能尚未
  初始化。使用者必须自行确认对象不变量。
- `hookClassInitializer` 是显式的受管 `<clinit>` 注册：回调须调用一次 `proceed()`
  或明确 `skip()`；跳过会改变初始化语义。已经初始化的类无法补回初始化事件，安装
  发布前并发发生的初始化可能直接通过并永久错过。关闭先拒绝新回调，再尝试一次卸载；
  旧在途调用继续完成，卸载失败的状态可能未知，不自动重试。

回调借用对象仅在本次回调线程和生命周期内有效。`FRAMEWORK_PROTECTIVE` 由框架决定
异常逃逸后的保护和恢复行为；框架在 SDK Hook 退出后自行执行的工作不在 SDK 的在途
计数里。相关 JVM 测试和框架代理测试不等于真实 ART、初始化时序或特定框架验证。
