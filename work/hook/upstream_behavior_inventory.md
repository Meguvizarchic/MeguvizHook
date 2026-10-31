# 上游行为盘点（HOOK-001 / HOOK-003，源码级事实）

证据来源：实际下载的 `io.github.libxposed:api:101.0.0` 与 `102.0.0` AAR/sources jar（SHA-256 见
`baseline_inventory.json`），不是凭记忆或研究快照。运行时/设备行为一律标记未验证。

## 1. 101 与 102 的共同链模型

| 事实（真实 `XposedInterface` 源码） | 对本 SDK 的约束 |
|---|---|
| 两代均有 `hook(Executable)`、`HookBuilder.intercept(Hooker)`、`Hooker.intercept(Chain)` | 101 与 102 都是 FRAMEWORK_CHAIN；**101 不是 classic before/after 模型** |
| `Chain.proceed()/proceed(args)/proceedWith(...)` | core 的 proceed 直接映射框架 continuation |
| `Chain.getArgs()` 返回不可变 List，改参需 `proceed(newArgs)` | adapter 用 `toArray()`；MUTABLE_ARGS 通过 proceed 写回 |
| `Chain` 不可跨线程共享，`intercept` 结束后不可重用 | 与 core 的借用/有效期检查一致 |
| `ExceptionMode.DEFAULT` 回落到 `module.prop`，未指定为 `PROTECTIVE`；hooker 失败可能被吞 | 两个 adapter 都对 PROPAGATE 显式设置 `PASSTHROUGH` |
| `HookHandle.unhook()` 幂等 | close 映射 unhook；设备上的实际恢复仍未验证 |
| `HookFailedError` 是 framework-internal Error，上游禁止捕获用于 fallback | adapter 不把它伪装成可恢复 receipt |

## 2. 实际代际差异

| 能力 | 101.0.0 | 102.0.0 | 适配决策 |
|---|---|---|---|
| `LIB_API` / 版本常量 | `LIB_API=101`，无 `API_102` | `API_101/API_102`，`LIB_API=102` | 101 adapter 不引用 102 常量 |
| `HookBuilder.setId` | 不存在 | SinceApi 102 | 101 不调用；102 用 logicalId 设置 hook id |
| `HookHandle.replaceHook` | 不存在 | SinceApi 102，支持原子替换 | 101 ATOMIC_REPLACE=UNSUPPORTED；102 仅声明源码能力，尚未实现替换流程 |
| 热重载入口 | 无 `HotReloadingParam/HotReloadedParam` | 有完整热重载生命周期 | 101 HOT_RELOAD=UNSUPPORTED；102 本切片 UNKNOWN，sample 关闭 |
| 模块实例 | 每进程一次 | 每模块代次一次 | 101 sample 无 hot-reload metadata；102 明确 `autoHotReload=false` |
| hook chain snapshot | 102 源码明确说明替换/新增不影响在途调用 | 同左 | core 自身不可变 plan 提供本 SDK 内的代次保证；不把 101 设备行为推断为已验证 |

两代 `XposedModule`/`XposedModuleInterface` 的 `onPackageReady` 与实际 package loader 都可用于手工入口。
`getApiVersion()` 默认返回库静态版本，不等于运行时能力探测；能力 cell 均标 `device_cell=not_verified`。

## 3. 入口 metadata 与 BIL 对齐

- BIL 的 102 消费布局只读核对为 `META-INF/xposed/module.prop`、`java_init.list`、`scope.list`。
- `sample-module-102` 使用 `minApiVersion/targetApiVersion=102`、`exceptionMode=passthrough`、
  `autoHotReload=false`；`sample-module-101` 使用 101 对应值且不写 102 专有 hot-reload 配置。
- 两个 sample 都是独立 APK；没有单 APK 双代 bootstrap，也没有 `native_init.list` 或 `.so`。
- 跟随 BIL：Gradle 9.8.2（含 wrapper SHA-256）、AGP 9.4.1、102.0.0、compileSdk 37 /
  minSdk 27 / targetSdk 37、Android Java 17、JUnit 4.13.2。101 是额外独立 backend 版本空间，
  不改变 BIL 的 102 默认对齐。
- BIL 的 lazy args、常量快路、配置 snapshot、native 池等属于其既有基线，不记为本 SDK 收益；
  本项目不复制其业务代码、不创建其开发任务。

## 4. 未验证范围

真实 101/102 运行时装载、R8 后设备入口可达性、真实类加载/优先级交错、unhook 的实际入口恢复、
service、热重载和性能行为均 not_run / unavailable。DEX 定义审计与 stub 测试只证明构建和映射，
不能替代上述设备证据。
