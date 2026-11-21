# MeguvizHook 公开调用合同（HOOK-002）

发布基线：Maven Central `1.2.0`（仓库 `main` 分支保留除 `hook-dex-bridge` 外的 7 个模块及核心合同）。语义冻结见 `docs/adr/ADR-0001-runtime-contract.md`。本文面向消费方描述源码中的真实类型与调用规则。

## 1. 模块与坐标

| 模块 | 角色 | 依赖与字节码 |
| --- | --- | --- |
| `hook-api` | 公开合同类型 | 仅 JDK（Java 8 字节码） |
| `hook-core` | `HookEnvironment`、安装规划、分发与 SPI | `hook-api`（Java 8 字节码） |
| `hook-dsl` | 可选 Kotlin DSL | `hook-api`、`hook-core` |
| `hook-ref-bridge` | 可选 MeguvizRef 目标定位桥 | `hook-core`、`meguviz-ref-core`（Java 17） |
| `hook-dex-bridge` | 可选 DEX 扫描 + Ref 定位桥（Central `1.2.0`） | `hook-core`、`meguviz-ref-core`、`dexmeguviz-android`（AAR / Java 17） |
| `backend-libxposed-101` | libxposed 101 适配（Android AAR） | `hook-api`、`hook-core` + `compileOnly io.github.libxposed:api:101.0.0` |
| `backend-libxposed-102` | libxposed 102 适配（Android AAR） | `hook-api`、`hook-core` + `compileOnly io.github.libxposed:api:102.0.0` |
| `hook-testing` | `FakeBackend`、独立 oracle、反射 fixtures | `hook-api`、`hook-core` |
| `sample-java-contracts` | 纯 JVM Java 消费样例 | `hook-api`、`hook-core`、`hook-testing` |
| `sample-module-101` / `102` | 手工 Java 入口的 101 / 102 模块 APK | `hook-api`、`hook-core` + 对应后端 |

公开包名：`io.github.jichuo1.meguviz.hook.api`（含 `.identity`）、`...core`（含 `.spi` / `.advanced`）、`...dsl`、`...ref`、`...dex`（Central `1.2.0`）、`...backend.libxposed101`、`...backend.libxposed102`、`...testing`。核心公共签名不包含 libxposed、Binder、JNI 或 Rust 类型，框架类型仅存在于后端适配模块内部。

## 2. 最小 Java 用法

```java
HookBackend backend = new LibXposed102Backend(xposedModule,
        new LibXposed102Context(processName, packageClassLoader, 1L));
HookEnvironment environment = HookEnvironment.create(backend);

Method target = SomeClass.class.getMethod("someMethod", int.class);
RegistrationReceipt receipt = environment.install(new HookSpec.Builder(
                HookId.of("feature.some-method"), HookOwner.of("my-module"), TargetSpec.ofMember(target))
        .beforeMutableArgs(invocation -> invocation.setArgument(0, 10))
        .after((invocation, result) -> { /* 可读取或显式替换结果 */ })
        .build());
if (receipt.isInstalled()) {
    HookRegistration owned = receipt.registration(); // 保存本次注册，退出时检查 close() 报告
} else if (receipt.isSatisfied()) {
    // ALREADY_INSTALLED：相同注册已存在，继续持有之前获得的句柄
} else {
    // 按 status() 处理；部分安装仍可能携带需要关闭的 registration
}
```

- **类型化回调**：`beforeArgs`、`beforeMutableArgs`、`afterArgs` 直接向回调传入 `ReadArgsInvocation` 或 `MutableArgsInvocation`，无需强转或手写 `access(...)`。未显式调用 `access(...)` 时，只读类型化回调默认选用 `READ_ARGS_VIEW`，包含 `beforeMutableArgs` 时默认选用 `MUTABLE_ARGS`；若显式 `access(...)` 与类型化回调不兼容，`build()` 会直接报错。复用同一回调对象时，幂等安装仍视为同一回调。
- **回调互斥约束**：普通非 `AROUND` 形状至少需要一个 before 或 after；`AROUND` 与 before / after 互斥。`replaceConstant(value)` 与所有回调互斥且仅允许 `NO_ARGS`，安装前按目标返回类型校验常量。
- **成功时替换结果**：`replaceResultOnSuccess(value)` 仅在用原参数调用下游一次并正常返回后替换为指定引用，下游异常保留原实例身份，在途租约覆盖整个下游调用；同样要求 `NO_ARGS` 并与其他回调互斥。
- **融合常量通道**：支持 `FusedConstantDispatchChannel` 的 101/102 后端将进入检查、取值与退出合为一次调用，同时保留状态双检与在途计数次序。关闭后未进入时透传；core 与后端须共享同一接口与标记的类加载身份。
- **只读参数视图**：`READ_ARGS_VIEW` 在回调期间借用框架参数槽（101/102 按下标直接读取，不复制参数列表），下游保留原参数；不承诺框架层零分配。
- **条件字符串替换**：`replaceIfStringArgumentEquals(index, expected, result, observer)` 显式声明字符串参数匹配与常量返回，按 `actual` 在前比较（缺槽按 `null`），命中通知无法访问或修改调用帧，未命中执行原方法一次；安装前检查所有目标的参数与返回类型。
- **描述符目标**：`TargetSpec.descriptor(TargetKind.METHOD, "a.b.C", "run").parameters("int").returning("void").match(MatchPolicy.UNIQUE).build()`。`parameters(null)` 表示匹配任意重载，`UNIQUE` 下多匹配返回 `AMBIGUOUS_TARGET`。构造器按 JVM 返回类型 `void` 匹配，`java.lang.Void` 不匹配构造器。
- **构造器接收对象**：在构造器回调中可调用 `invocation.constructorReceiver()` 获取后端提供的接收对象（`thisObject()` 对构造器仍返回 `null`）。接收对象可能尚未或只部分初始化，且即使在 after 中非空也不代表原构造器一定成功执行。调用方仍须遵守借用线程与回调期生命周期规则。

  ```java
  .before(invocation -> {
      Object receiver = invocation.constructorReceiver();
      if (receiver != null) {
          // 仅按目标框架与业务约定检查，不能假设构造器已经执行完成。
      }
  })
  ```

- **默认配置与框架保护**：`HookSpec` 默认 `NO_ARGS`、`ErrorPolicy.PROPAGATE`、优先级 `50`、`ProcessFilter.all()`、无必需能力。显式 `ErrorPolicy.FRAMEWORK_PROTECTIVE` 要求后端声明 `FRAMEWORK_PROTECTIVE` 能力；101/102 会将异常交由框架原生 `PROTECTIVE` 模式处理，SDK 不重放原方法，且框架在 SDK 回调退出后自行执行的回退下游不计入 SDK 在途计数。

## 3. 结果、批量安装与回执

- **`RegistrationReceipt.Status`**：`INSTALLED` / `PARTIALLY_INSTALLED` / `ALREADY_INSTALLED` / `CONFLICT` / `UNSUPPORTED_CAPABILITY` / `AMBIGUOUS_TARGET` / `TARGET_MISSING` / `STALE_IDENTITY` / `FILTERED` / `TEMPORARILY_UNAVAILABLE` / `FAILED`。
- **`isInstalled()` vs `isSatisfied()`**：`isInstalled()` 仅对本次 `INSTALLED` 返回 `true`；`isSatisfied()` 对 `INSTALLED` 与 `ALREADY_INSTALLED` 均返回 `true`（仅代表检查时刻配置已满足）。`ALREADY_INSTALLED` 不生成新代次、不替换框架钩子也不返回新 `registration`，调用方必须继续管理先前持有的句柄。`PARTIALLY_INSTALLED` 中 `isSatisfied()` 为 `false`，但仍可能携带需关闭的 `registration`。Central `1.2.0` 另提供 `environment.installRequired(spec)`，未满足时抛出携带原回执的 `HookInstallException`。
- **`installAll(specs)` / `installAll(specs, receipts)`**：在一次环境锁内依次安装所有 spec，各 spec 独立产出回执且不承诺跨 spec 原子性。若解析器实现 `BatchMemberResolver`（如默认 `ReflectiveMemberResolver` 及 `1.2.0` 的 `MeguvizRefMemberResolver`），观察到相同加载器与 `codeEpoch` 的描述符目标共享同一个批次（同一类只加载与复制声明成员表一次）。批次打开或关闭的非致命错误记入 `DiagnosticsSink`（`BACKEND_FAILURE`）并回退为逐个解析。某个 spec 抛出未检查异常时停止后续安装，此前已成功注册的仍归环境所有。Kotlin DSL 对应 `forOwner(owner).installAll { hook(id, target) { ... } }`。
- **`installWithResolutionDiagnostics(spec)`**：保留本次 `MemberResolver.resolve` 抛出的原始受检 `BackendInstallException`（`resolutionFailure()`），同时给出标准 `receipt()`。若解析期间身份变化，回执可为 `STALE_IDENTITY` 并仍保留原解析异常；结果可能保留异常图与加载器，检查后应及时释放引用。用法见 [`hook-ref-bridge` 说明](../../hook-ref-bridge/README.md#显式查看解析失败)。
- **`MatchPolicy.ALL` 与隔离合同**：`targets()` 按稳定顺序返回 `INSTALLED` / `FAILED` / `ROLLED_BACK`。普通单项失败保留已成功项并返回 `PARTIALLY_INSTALLED`；发布前 loader 或 epoch 改变则关闭已安装项并返回 `STALE_IDENTITY`。`ROLLED_BACK` 仅代表已尝试关闭，不保证入口恢复。同一环境中入口恢复为 `NOT_RESTORED` 或 `UNKNOWN` 时拒绝同 ID 重装，恢复为 `RESTORED` 则允许重试（即使底层代码资源仍驻留）。
- **单目标替换与故障隔离**：`replace` / `forceReplace` 抛出 `BackendInstallException` 表示后端保证未提交、旧句柄仍有效；抛出其他 `Throwable` 或返回空句柄则表示结果未知，原异常继续传播，同时停用新旧 SDK 通道并在当前环境隔离该 `HookId`，切勿盲目卸载可能已失效的旧句柄。安装发布或回执构造被中断时，SDK 通过独立保护表关闭已知句柄并隔离该 ID。
- **`CloseReport` 与关闭语义**：包含 `state()`（`CLOSED` / `RETIRED_RESIDENT`）、`newCallbacksRejected()`、`entryRestoration()`、`inFlightAtClose()` 与 `physicalRelease()`（默认 `NOT_CLAIMED`）。`close()` 幂等，不等待宿主调用返回，也不撤销 `TargetSpec.ofMember` 传入的原始 `Member`。环境关闭时遇到注册抛出的非致命 `Error` 或运行时异常不会跳过后续注册，所有已知注册均尝试关闭后重抛首个异常；后端抛出的未声明受检异常按恢复状态未知处理，已观察到的线程中断状态会在退出前恢复。
- **非等待请求关闭**：`HookEnvironment.requestClose()` 仅置位关闭标记以拒绝后续新操作，不获取环境锁或卸载，需配合后台协调线程调用 `close()` 完成清理，见[显式请求关闭环境](../close-request.md)。

## 4. 生命周期与借用规则

- `HookInvocation` 仅在回调线程且回调返回前有效；跨线程或超时访问抛 `HookContractException`，重入调用拥有独立调用帧。
- 单 after 且无 before 回调时复用专用通道并共享调用帧；多个回调时，after 回调中的 `invocation` 与 `result` 可能是同一借用对象，切勿依赖两者对象引用同一性。
- `AroundInvocation.proceed()` 每次调用至多执行一次；重复调用、跨线程调用或回调后调用均抛出合同异常。
- `entrySnapshot()` 返回浅槽快照（复制数组、共享元素引用）。在 `around` 中如需读取当时参数槽，可调用 `InvocationArguments.copyOf(invocation)`（同样须遵守回调期借用约束）。
- `HookRegistration.close()` 后到达框架入口的调用走透传（passthrough）：原方法执行一次，不触发 SDK 回调。

## 5. 身份模型

`ProcessIdentity`、`CodeScopeIdentity`、`CodeEpoch`、`HookGeneration`、`ServiceEpoch`、`ConfigRevision`、`NativeArtifactIdentity` 分别建模；后三者在当前版本中仅作为独立身份类型存在，默认路径不创建对应服务或 native 资源。

## 6. 明确拒绝的形状

- `ChainSemantics` 非 `FRAMEWORK_CHAIN` 的后端安装时直接返回 `UNSUPPORTED_CAPABILITY`。
- 必需能力为 `UNSUPPORTED` 或 `UNKNOWN` 时均返回 `UNSUPPORTED_CAPABILITY`。
- API 101 后端仅支持同一框架入口内的 SDK 单目标通道原子切换；模块级 `HOT_RELOAD` 明确声明为 `UNSUPPORTED`。API 102 热重载接入规范见 [API 102 模块热重载](../hot-reload-102.md)。

## 7. API 基线检查

修改公共接口后运行 `./gradlew apiCheck`（Windows 使用 `gradlew.bat`）对照基线核验兼容性。
