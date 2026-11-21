# MeguvizHook API 与模块速查

本文整理 MeguvizHook 的制品坐标、Maven Central `1.2.0` 与仓库 `main` 源码树的真实边界、回调形状性能梯度以及核心 API 签名。

## 1. 制品表与版本口径边界（Central `1.2.0` vs GitHub `main`）

Maven Central 已发布 `io.github.jichuo1.meguviz.hook:*:1.2.0` 共 8 个制品，统一受 `io.github.jichuo1.meguviz:meguviz-bom:1.2.0` 管理。若直接从 GitHub `main` 源码构建，`main` 当前包含前 7 个模块，其余新增能力位于 `1.2.0` 发布分支：

| 制品 `artifactId` | 形态与字节码 | `main` 源码包含 | Central `1.2.0` 已发布 | 职责与边界 |
| --- | --- | --- | --- | --- |
| `hook-api` | JAR（Java 8） | 是 | 是 | 公开接口、回调形状、`HookSpec`、`RegistrationReceipt` |
| `hook-core` | JAR（Java 8） | 是 | 是 | `HookEnvironment`、分发器、路由、生命周期、跟踪与批量传输 |
| `hook-dsl` | JAR（Java 8 / Kotlin） | 是 | 是 | Kotlin DSL（`forOwner`、`HookOwnerScope`、`method`、`hook`） |
| `hook-testing` | JAR（Java 8） | 是 | 是 | JVM `FakeBackend`、Oracle 校验与测试夹具 |
| `hook-ref-bridge` | JAR（Java 17） | 是 | 是 | 依赖 `meguviz-ref-core:1.2.0`，按描述符定位成员 |
| `hook-dex-bridge` | AAR（Android `minSdk 27` / Java 17） | **否**（在 `1.2.0` 发布分支） | **是** | 依赖 `dexmeguviz-android:1.2.0` 与 `ref-core:1.2.0`，含 `DexRefHookBridge.queryAndBindBatch` |
| `backend-libxposed-101` | AAR（Android `minSdk 27` / Java 17） | 是 | 是 | `LibXposed101Backend`（配 `compileOnly libxposed:api:101.0.0`） |
| `backend-libxposed-102` | AAR（Android `minSdk 27` / Java 17） | 是 | 是 | `LibXposed102Backend`、`ReloadSession`、`BinderTrace102` |

### `1.2.0` 发布分支相对 `main` 的增量 API 清单

消费 Central `1.2.0` 时可用、但 `main` 源码树尚未合入的符号：

- `hook-dex-bridge` 全模块：`DexRefHookBridge.queryAndBindBatch`、`bind`、`bindBatch`、`QueryBatch`、`Batch`、`QueryBindings`、`CandidateBinding`。
- `backend-libxposed-102`：`BinderTrace102.slicedReceiver(...)`、`BinderTrace102.slicedReceiverForFrameSizes(...)`、`ModuleRemoteTraceWriter102.slicedBatchReceiver(...)`。
- `hook-core`：`HookEnvironment.installRequired(HookSpec)`、`HookInstallException`、`BatchTraceTransport.newSession(...)`、`BatchTraceTransport.slicedReceiver(...)`、`BatchTraceTransport.slicedBatchReceiver(...)`、`JsonLineTraceTransport.write(...)`。
- `hook-dsl`：`HookOwnerScope.installRequired(...)`、`HookOwnerScope.installAll(onReceipt, ...)`。
- `hook-ref-bridge`：`MeguvizRefResolverSession.ResolutionMode.RAW_IF_AVAILABLE`（搭配 Ref `1.2.0` 的 `resolveRaw` / `isCurrent`）及 `MeguvizRefMemberResolver` 实现 `BatchMemberResolver`。

### 编译与 SDK 基线

- Android 后端与 `hook-dex-bridge`：`compileSdk 37.2`、`buildToolsVersion "37.0.0"`、`minSdk 27`、`JavaVersion.VERSION_17`。
- `compileOnly` 要求：使用 `101` 后端时声明 `compileOnly("io.github.libxposed:api:101.0.0")`；使用 `102` 时声明 `compileOnly("io.github.libxposed:api:102.0.0")`。

## 2. 回调形状性能梯度（按热路径开销从低到高）

每次目标方法被调用都会经过所选回调形状；能用靠前形状时，不要退化为后方形状：

| 优先级 | `HookSpec.Builder` / DSL 入口 | 对应 `ParameterAccess` | 热路径说明 |
| --- | --- | --- | --- |
| 1 | `replaceConstant(value)` | `NO_ARGS` | 不建调用帧、不执行原方法；101/102 支持融合常量通道 |
| 2 | `replaceResultOnSuccess(value)` | `NO_ARGS` | 不建调用帧；原参数执行下游一次，正常返回时替换结果 |
| 3 | `replaceIfStringArgumentEquals(idx, expected, result[, observer])` | `READ_ARGS_VIEW` | 不建完整调用帧；按索引直读单字符串槽匹配 |
| 4 | `before(...)` / `after(...)` | `NO_ARGS` | 建调用帧，不读取任何参数；访问参数抛 `HookContractException` |
| 5 | `beforeArgs(...)` / `afterArgs(...)` | `READ_ARGS_VIEW` | 建调用帧；101/102 按槽直读参数，不复制数组下传 |
| 6 | `beforeMutableArgs(...)` | `MUTABLE_ARGS` | 首次读写参数槽时获取后端参数数组并写回下游 |
| 7 | `around(...)` | `AROUND` | 完全包裹下游调用；`proceed()` 至多调用一次，互斥 before/after |
| 8 | `access(ParameterAccess.ENTRY_SNAPSHOT)` | `ENTRY_SNAPSHOT` | 入口立即复制参数浅槽快照（`args.clone()`） |

## 3. 核心 API 签名速查

### 3.1 后端与上下文：`backend-libxposed-101` / `102`

- `LibXposed101Backend(XposedInterface xposed, BackendContext context)`、`LibXposed101Context(String processName, ClassLoader loader, long codeEpoch)`、`LibXposed101Advanced(XposedInterface xposed)`
- `LibXposed102Backend(XposedInterface xposed, BackendContext context)`、`LibXposed102Backend.withFrameworkId(xposed, context, frameworkId)`
- `LibXposed102Context(String processName, ClassLoader loader, long codeEpoch)`、`LibXposed102Advanced(XposedInterface xposed)`
- `LibXposed102ReloadSession(LibXposed102Backend backend, HotReloadedParam param)`：`oldHandles()`、`adoption(HookHandle)`、`adoption(HookHandle, ErrorPolicy)`、`requestClose()`、`isCloseRequested()`、`close()`

### 3.2 核心环境与规范：`hook-core` / `hook-api`

- `HookEnvironment`
  - `static HookEnvironment create(HookBackend)` / `create(HookBackend, MemberResolver)` / `create(HookBackend, MemberResolver, DiagnosticsSink)`
  - `RegistrationReceipt install(HookSpec)`、`List<RegistrationReceipt> installAll(Iterable<HookSpec>)`、`void installAll(Iterable<HookSpec>, Consumer<? super RegistrationReceipt>)`
  - `InstallResolutionResult installWithResolutionDiagnostics(HookSpec)`
  - `Preparation prepareConcurrently(HookSpec, int maxCandidates)`（返回携带一次性 `PreparedInstall.commit()` 的准备结果）
  - `RegistrationReceipt adopt(HookSpec, BackendAdoption)`
  - `RegistrationReceipt replace(HookSpec)`、`RegistrationReceipt forceReplace(HookSpec)`
  - `void requestClose()`、`boolean isCloseRequested()`、`void close()`、`int activeRegistrationCount()`
  - （仅 Central `1.2.0`）：`RegistrationReceipt installRequired(HookSpec)`
- `HookSpec`
  - `static Builder builder(String id, String owner, TargetSpec target)`、`static Builder forMember(String id, String owner, Executable target)`
  - `Builder`：`access(ParameterAccess)`、`before` / `beforeArgs` / `beforeMutableArgs` / `after` / `afterArgs` / `around`、`replaceConstant` / `replaceResultOnSuccess` / `replaceIfStringArgumentEquals`、`errorPolicy(ErrorPolicy)`（`PROPAGATE` / `FRAMEWORK_PROTECTIVE`）、`require(Capability)`、`processes(ProcessFilter)`、`priority(int)`（默认 `50`）
- `TargetSpec`：`ofMember(Executable)`、`descriptor(TargetKind, className, memberName)`（支持 `parameters(...)`、`returning(...)`、`initializeClass(boolean)`、`match(MatchPolicy)`）
- `RegistrationReceipt`：`status()`（11 种状态）、`isInstalled()`（仅 `INSTALLED`）、`isSatisfied()`（`INSTALLED || ALREADY_INSTALLED`）、`isPartiallyInstalled()`、`registration()`、`targets()`
- `HookRegistration`：`id()`、`generation()`、`state()`、`CloseReport close()`（不实现 `AutoCloseable`，因为需返回结构化 `CloseReport`）

### 3.3 Kotlin DSL：`io.github.jichuo1.meguviz.hook.dsl`

- `HookOwnerScope`（通过 `environment.forOwner(owner)` 获取）：`spec(...)`、`install(...)`、`installAll { hook(...) }`、`replace(...)`、`forceReplace(...)`（Central `1.2.0` 另含 `installRequired` 与 `installAll(onReceipt) { ... }`）
- 顶层入口：`hook(...)`、`member(Executable)`、`method(...)`、`constructor(...)`、`ReadArgsInvocation.argumentOrNull(index)`

### 3.4 入口路由、生命周期与诊断传输：`hook-core` / `backend-libxposed-102`

- `ModuleEntryRouter(processName, epoch, maxRoutes, lifecycle, filter, factory, handler)`：`onPackageReady(packageName, loader)`、`fromBackend(...)`、`snapshotContexts()`、`detachContexts()`
- `ModuleLifecycle(maxResources)`：`own(name, AutoCloseable)`、`tryEnter()`（返回 `Lease`）、`requestQuiescence()`、`resumeAdmissions()`、`tryCleanup()`
- `CallbackExceptionTracker(source, capacity[, CaptureSignal])`：`before` / `after` / `around` 包装器、`deliver`、`deliverBatch`、`tryDeliverBatch`、`peekNext`、`discardNext`、`discardIfNext`
- `TraceBatchPoller`：`start(...)`、`startDraining(..., WaitExpiry)`、`requestPoll()`、`state()`、`progress()`、`scheduleObservation()`、`resume()`、`close()`
- `BatchTraceTransport`：`sender(...)`、`receiver(...)`、`compressedReceiver(...)`、`compressedSessionReceiver(...)`、`compressedSerializedReceiver(...)`（Central `1.2.0` 另含 `newSession`、`slicedReceiver`、`slicedBatchReceiver`）
- `BinderTrace102`：`sender(IBinder, name, maxRecordBytes)`、`receiver(name, maxRecordBytes, UidAuthorizer, RecordWriter)`（Central `1.2.0` 另含 `slicedReceiver` 与 `slicedReceiverForFrameSizes`）

### 3.5 可选定位桥与测试：`hook-ref-bridge` / `hook-dex-bridge` / `hook-testing`

- `MeguvizRefMemberResolver`：默认构造、`withHierarchy(Hierarchy)`、`withScope(ScopeFactory, Hierarchy)`、`withQueries(...)`
- `MeguvizRefResolverSession`：`open`、`withHierarchy`、`withScope`、`withQueries`、`withQueriesAndWaiterPolicy`、`requestClose()`、`report()`、`close()`
- `DexRefHookBridge`（仅 Central `1.2.0`）：`queryAndBindBatch(Snapshot | SourceSetSnapshot, byte[][] rules, ClassLoader, int maxCandidates[, long cacheBytes, long maxCacheWork])`、`bind`、`bindBatch`
- `FakeBackend`：`create()`、`onTarget`、`call`、`bumpCodeEpoch`、`setProcessName`、`setClassLoader`、`withCapability`、`withFailingNextInstall`、`argumentMaterializationCount`
