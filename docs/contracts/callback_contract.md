# MeguvizHook 公开调用合同（HOOK-002）

版本：0.1.0-SNAPSHOT（未发布）。语义冻结见 `docs/adr/ADR-0001-runtime-contract.md`。
本文面向 consumer，描述实际源码中的类型与调用方式，不是拟议名称清单。

## 1. 模块与坐标

| 模块 | 角色 | 依赖 |
|---|---|---|
| `hook-api` | 公开合同类型 | 仅 JDK（Java 8 字节码） |
| `hook-core` | `HookEnvironment`、安装期 planner、dispatcher、SPI | `hook-api` |
| `backend-libxposed-102` | libxposed 102 适配（Android library） | api/core + `compileOnly io.github.libxposed:api:102.0.0` |
| `hook-testing` | fake backend、独立 oracle、反射 fixtures | api/core |
| `sample-java-contracts` | 纯 JVM Java 消费样例 | api/core/testing |
| `sample-module-102` | 手工入口的 102 模块 APK | api/core/backend-102 |

公开包名：`io.github.jichuo1.meguviz.hook.api`（+`.identity`）、`...core`（含`.spi`）、
`...backend.libxposed102`、`...testing`。公开签名不出现 libxposed、Binder、JNI、Rust 或其他
Meguviz 家族类型；框架类型仅存在于 backend 实现内部。

## 2. 最小 Java 用法

```java
HookBackend backend = new LibXposed102Backend(xposedModule,
        new LibXposed102Context(processName, packageClassLoader, 1L));
HookEnvironment environment = HookEnvironment.create(backend);

Method target = SomeClass.class.getMethod("someMethod", int.class);
RegistrationReceipt receipt = environment.install(new HookSpec.Builder(
                HookId.of("feature.some-method"), HookOwner.of("my-module"), TargetSpec.ofMember(target))
        .access(ParameterAccess.MUTABLE_ARGS)
        .before(invocation -> ((MutableArgsInvocation) invocation).setArgument(0, 10))
        .after((invocation, result) -> { /* 可读取或显式替换结果 */ })
        .build());
if (!receipt.isInstalled()) {
    // 按 receipt.status() 分别处理：能力拒绝/歧义/缺失/冲突/过期/裁剪/失败。
}
// 退役：CloseReport report = receipt.registration().close();
```

- 非 AROUND 形状至少一个 before 或 after；`AROUND` 与 before/after 互斥（本版）。
- 描述符目标：`TargetSpec.descriptor(TargetKind.METHOD, "a.b.C", "run")
  .parameters("int").returning("void").match(MatchPolicy.UNIQUE).build()`。
  `parameters(null)` 表示任意重载，UNIQUE 下多匹配返回 `AMBIGUOUS_TARGET`。
- `HookSpec` 默认：`NO_ARGS`、`ErrorPolicy.PROPAGATE`、优先级 50、进程过滤 all、无必需能力。

## 3. 结果与回执

`RegistrationReceipt.Status`：`INSTALLED / ALREADY_INSTALLED / CONFLICT / UNSUPPORTED_CAPABILITY /
AMBIGUOUS_TARGET / TARGET_MISSING / STALE_IDENTITY / FILTERED / TEMPORARILY_UNAVAILABLE / FAILED`。
只有 `INSTALLED` 携带 `HookRegistration`。`FILTERED` 是进程裁剪的正常结果，不是失败。

`CloseReport`：状态（`CLOSED` 或 `RETIRED_RESIDENT`）、是否拒绝新回调、入口恢复、在途观察数、
物理释放（默认 `NOT_CLAIMED`）。`close()` 幂等，不等待宿主调用，不撤销 raw Member。

## 4. 生命周期与借用规则

- `HookInvocation` 仅在执行回调的线程、回调返回前有效；跨线程或过期访问抛
  `HookContractException`。重入获得独立 frame。
- `AroundInvocation.proceed()` 每次 invocation 至多一次；第二次、跨线程、回调后调用均为合同异常。
- `entrySnapshot()` 是浅槽快照：数组复制、对象共享。
- `HookRegistration.close()` 后到达框架入口的调用走 `passthrough`：原方法恰好一次，无 SDK 回调。

## 5. 身份模型

`ProcessIdentity`、`CodeScopeIdentity`（实际 loader 引用）、`CodeEpoch`、`HookGeneration`、
`ServiceEpoch`、`ConfigRevision`、`NativeArtifactIdentity` 分别建模。后三者在本切片仅为身份类型，
不创建 service、配置会话或 native 制品，也不会被默认路径实例化。

## 6. 本切片明确拒绝的形状

- `ChainSemantics` 非 `FRAMEWORK_CHAIN` 的 backend（classic/local-only）→ `UNSUPPORTED_CAPABILITY`。
- `MatchPolicy.ALL` → `UNSUPPORTED_CAPABILITY`（后续任务实现逐项回执）。
- 必需能力为 `UNSUPPORTED` 或 `UNKNOWN`（含未声明）→ `UNSUPPORTED_CAPABILITY`，UNKNOWN 不视为支持。

## 7. API dump

`work/hook/api_dump.txt` 由 `javap` 对实际编译产物生成（见移交命令）。签名审计报告：
`work/integration/java_consumption_boundary.md`。
