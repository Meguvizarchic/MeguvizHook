# 上游行为盘点（HOOK-001，源码级事实）

证据来源：实际下载的 `io.github.libxposed:api:102.0.0` AAR 与 sources jar（SHA-256 见
`baseline_inventory.json`），不是凭记忆或研究快照。运行时/设备行为一律标记未验证。

## 1. libxposed API 102.0.0（`XposedInterface`）

| 事实（源码位置） | 对本 SDK 的约束 |
|---|---|
| `hook(Executable)` 返回 `HookBuilder`，`intercept(Hooker)` 安装并返回 `HookHandle` | backend 的安装入口；真实拦截由框架完成 |
| `Hooker.intercept(Chain)` 返回 Object 并可 `throws Throwable`；`Chain.proceed()/proceed(args)/proceedWith(...)` | 属 FRAMEWORK_CHAIN；core 的 proceed 直接映射 continuation |
| `Chain.getArgs()` 返回**不可变** List，改参需 `proceed(newArgs)` | `arguments()` 用 `toArray()`；MUTABLE_ARGS 通过 proceed 写回 |
| `Chain` 不可跨线程共享，`intercept` 结束后不可重用 | 与借用/有效期合同一致，core 强制检查 |
| `ExceptionMode` 默认 `DEFAULT`，回落到 `module.prop`，未指定为 `PROTECTIVE`：hooker 异常被记录并“当作无 hook 继续”；proceed 抛出的异常总是传播 | **PROPAGATE 必须显式 `PASSTHROUGH`**，不能依赖 module.prop；否则 before/after 失败会被吞 |
| `HookBuilder.setId`（SinceApi 102）：同模块同 target 同 id 原子替换；hook id 模块间隔离 | 与 logicalId 冲突语义兼容；本切片未实现 replace，能力仅声明到“102 提供” |
| `HookHandle.unhook()` 幂等；`replaceHook`（102）保留 executable/priority/异常模式/id | close 映射 unhook；replacement 留待后续任务 |
| 102 hook chain 是 snapshot based：替换/新增不影响在途调用 | 与 core 的不可变 plan 代次语义一致 |
| `HookFailedError extends XposedFrameworkError extends Error`，上游明确“不应捕获用于 fallback” | adapter 不把它转成 receipt，不伪装可恢复安装失败 |
| `getApiVersion()` 默认返回库静态 `LIB_API=102`，注释要求框架实现不得覆盖 | 该值是**库版本**，不是运行时能力证据；能力声明标注 device_cell=not_verified |
| `getFrameworkName/Version/VersionCode/Properties` 提供实现身份 | 仅用于能力 cell 描述，不用作能力推断 |
| `hookClassInitializer`、`deoptimize`、`getInvoker`（Origin/Chain 调用）、`log`、remote preferences/files | 本切片未使用；不是默认路径依赖 |

## 2. 模块入口 ABI（102）

- 入口类继承 `XposedModule`（实现 `XposedModuleInterface`），每个模块代次实例化一次。
- 生命周期：`onModuleLoaded` / `onPackageLoaded`(Q+) / `onPackageReady` / `onSystemServerStarting`；
  `PackageReadyParam.getClassLoader()` 是实际 loader（可不同于 default loader）。
- 热重载（102 可选）：`onHotReloading` 默认 false；`onHotReloaded` 默认 unhook 全部旧 handle；
  框架不做 `UnregisterNatives`/`JNI_OnUnload`/`dlclose`，旧代 classloader 回收不保证。
  本切片 `autoHotReload=false`，不实现热重载，能力报 UNKNOWN。
- 入口 metadata（BIL 实际 102 消费布局，只读核对）：
  `src/main/resources/META-INF/xposed/module.prop`（`minApiVersion/targetApiVersion/staticScope/
  exceptionMode/autoHotReload`）、`java_init.list`（入口 FQCN）、`scope.list`（目标包名）。
  sample 采用相同布局；`native_init.list` 仅在显式选择 native 时才应出现，本切片没有。

## 3. 与 BIL 的对齐边界

- 跟随版本：Gradle 9.8.2（含 wrapper SHA-256）、AGP 9.4.1、libxposed 102.0.0、compileSdk 37 /
  minSdk 27 / targetSdk 37、Java 17（Android 模块）、JUnit 4.13.2。
- BIL `module.prop` 用 `exceptionMode=protective`，属其自身模块选择；本 sample 用 `passthrough`，
  因为本 SDK 的默认错误策略是 PROPAGATE，且 adapter 在代码中显式设置，不读模块元数据。
- BIL 的 lazy args、常量快路、配置 snapshot、native 池等属于其既有基线，不记为本 SDK 收益；
  本切片不复制其业务代码、不创建其开发任务。

## 4. 未验证范围

真实 102 运行时装载、R8 后入口可达性、真实类加载/优先级交错、unhook 的实际入口恢复、
API101 行为、service 行为：均 not_run / unavailable，不由上述源码事实推断通过。
