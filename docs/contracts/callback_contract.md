# MeguvizHook 公开调用合同（HOOK-002）

版本：0.1.0-SNAPSHOT（未发布）。语义冻结见 `docs/adr/ADR-0001-runtime-contract.md`。
本文面向 consumer，描述实际源码中的类型与调用方式，不是拟议名称清单。

## 1. 模块与坐标

| 模块 | 角色 | 依赖 |
|---|---|---|
| `hook-api` | 公开合同类型 | 仅 JDK（Java 8 字节码） |
| `hook-core` | `HookEnvironment`、安装期 planner、dispatcher、SPI | `hook-api` |
| `backend-libxposed-101` | libxposed 101 适配（Android library） | api/core + `compileOnly io.github.libxposed:api:101.0.0` |
| `backend-libxposed-102` | libxposed 102 适配（Android library） | api/core + `compileOnly io.github.libxposed:api:102.0.0` |
| `hook-testing` | fake backend、独立 oracle、反射 fixtures | api/core |
| `sample-java-contracts` | 纯 JVM Java 消费样例 | api/core/testing |
| `sample-module-101` | 手工 Java 入口的 101 模块 APK | api/core/backend-101 |
| `sample-module-102` | 手工 Java 入口的 102 模块 APK | api/core/backend-102 |

公开包名：`io.github.jichuo1.meguviz.hook.api`（+`.identity`）、`...core`（含`.spi`）、
`...backend.libxposed101`、`...backend.libxposed102`、`...testing`。公开签名不出现 libxposed、Binder、JNI、Rust 或其他
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
if (receipt.isInstalled()) {
    HookRegistration owned = receipt.registration(); // 保存本次注册，之后检查 close 报告
} else if (receipt.isSatisfied()) {
    // 相同注册已存在；继续持有之前获得的 handle，不会在此回执中取得新 handle。
} else {
    // 按 status 处理；部分安装仍可能携带需要关闭的 registration。
}
```

- 普通非 AROUND 形状至少一个 before 或 after；`AROUND` 与 before/after 互斥。显式
  `replaceConstant(value)` 与所有回调互斥，并且只允许 NO_ARGS；常量在安装前按目标返回类型校验。
- `replaceResultOnSuccess(value)` 仅在原参数调用下游一次并正常返回后替换为所给引用；原异常保持身份，
  在途租约覆盖整个下游调用。它要求 NO_ARGS 且与其他回调／替换互斥；安装前校验所有目标的返回类型。
  旧后端通过带 invocation 的普通分发回退；框架自身异常保护可能有另外的行为。
- 支持融合常量通道的 101/102 后端将准入、取值和退出合为一次调用，但仍保留状态双检及在途计数；
  常量通道缓存所属注册的共享计数器，保持原有两次状态读取和原子加减次序。
  关闭后未准入时透传。未准入标记只按引用比较，常量与标记为同一对象、旧通道或需要调用帧时回退原路径。
  异常保护策略不因此改变；SDK core 和后端需共享该接口与标记的类加载身份。
- `READ_ARGS_VIEW` 为显式的只读借用视图，生命周期与普通回调一致；101/102 可直接使用框架不可变
  参数列表，下游保持原始参数。它不改变现有 `READ_ARGS` 或 `ENTRY_SNAPSHOT`，也不保证框架零分配。
- `replaceIfStringArgumentEquals(index, expected, result, observer)` 显式声明字符串槽匹配和常量返回，
  与普通回调/其他替换互斥；匹配按 actual 在前比较，缺槽为 null。命中通知不能访问或修改调用帧，
  未命中执行原方法一次；通知和原方法异常按原身份交给框架。安装前校验全部目标的参数与返回类型。
- 描述符目标：`TargetSpec.descriptor(TargetKind.METHOD, "a.b.C", "run")
  .parameters("int").returning("void").match(MatchPolicy.UNIQUE).build()`。
  `parameters(null)` 表示任意重载，UNIQUE 下多匹配返回 `AMBIGUOUS_TARGET`。
  构造器的 JVM 返回类型按 `void` 匹配：不指定返回类型或指定 `void` 均可匹配；
  `java.lang.Void` 和其他类型不会匹配构造器。
- 当前开发源码在构造器回调中可显式调用 `invocation.constructorReceiver()` 获取后端提供的
  接收对象；原有 `thisObject()` 对构造器仍返回 `null`。接收对象可能不可用，也可能尚未或只
  部分初始化；即使在 after 中非空，也不能推断原构造器成功执行。提前返回或异常时同样如此。
  借用调用对象仍只能在本次回调及所属线程使用，调用方负责管理自行保留的接收对象引用。
  在新版 API 下，未覆盖该方法的旧实现使用默认值 `null`；若调用方运行时仍使用不包含此方法
  的旧 API，会发生链接错误。存在相互冲突的接口默认方法时，具体实现仍需显式解决冲突。
  应配套使用包含此方法的 API 和 core，不能假设它已在既有 1.0.0 发布包中。101/102 后端依赖
  框架提供的接收对象，实际框架和 ART 行为仍需验证。

  ```java
  .before(invocation -> {
      Object receiver = invocation.constructorReceiver();
      if (receiver != null) {
          // 仅按目标框架和业务约定处理，不能假设构造器已经执行。
      }
  })
  ```

- `HookSpec` 默认：`NO_ARGS`、`ErrorPolicy.PROPAGATE`、优先级 50、进程过滤 all、无必需能力。
- 显式 `ErrorPolicy.FRAMEWORK_PROTECTIVE` 需要 backend 声明 `FRAMEWORK_PROTECTIVE` 能力；101/102
  将错误交由框架原生 PROTECTIVE 模式处理，SDK 不重放原方法。SDK 的多回调状态机及 fatal
  交由框架后的结果不应被笼统视为与 BIL 所有 DSL 场景等价；缺失/未知能力会拒绝安装。
  框架在 SDK hooker 退出后自行执行的回退下游不计入已结束的 SDK 在途租约。

## 3. 结果与回执

`RegistrationReceipt.Status`：`INSTALLED / PARTIALLY_INSTALLED / ALREADY_INSTALLED / CONFLICT /
UNSUPPORTED_CAPABILITY / AMBIGUOUS_TARGET / TARGET_MISSING / STALE_IDENTITY / FILTERED /
TEMPORARILY_UNAVAILABLE / FAILED`。`INSTALLED` 与 `PARTIALLY_INSTALLED` 携带 `HookRegistration`；
`isInstalled()` 仅对本次 `INSTALLED` 结果返回 true；已存在与部分成功均为 false。
`FILTERED` 是进程裁剪的正常结果。

`isSatisfied()` 对 `INSTALLED` 与 `ALREADY_INSTALLED` 返回 true，表示此次检查时完整配置已满足；
回执不是实时存活状态。`ALREADY_INSTALLED` 不创建新 generation、不调用框架替换，也不返回
registration：调用方须保留原 handle，且不能将其当成已强制换代或新句柄已获得所有权。
`PARTIALLY_INSTALLED` 仍返回 false，但可能携带需要管理的 registration。
该辅助方法属于当前开发源码，已发布的 1.0.0 尚无此方法；使用 1.0.0 时可显式比较上述两个
状态，并遵循同样的 handle 所有权规则。

当前开发源码还提供显式 `installWithResolutionDiagnostics(spec)`：`receipt()` 给出与普通安装
同合同的回执，`resolutionFailure()` 仅返回本次 `MemberResolver.resolve` 抛出的原始受检
`BackendInstallException`。解析后身份复核可能使回执为 `STALE_IDENTITY`，同时保留发生在身份
变化前的解析异常；空异常不意味着成功，也不覆盖后端安装或其他阶段的失败。unchecked 异常和
fatal 事件仍按普通安装规则传播。此入口不隐式记录异常，且不执行 replace 或 adopt。
结果和异常可能保留完整 Throwable 图、敏感信息及 ClassLoader；调用方应控制保留期并在检查后
释放引用，不能据此保证立即回收。桥接示例及使用边界见
[`hook-ref-bridge` 说明](../../hook-ref-bridge/README.md#显式查看解析失败)。

`MatchPolicy.ALL` 的 `targets()` 按稳定解析顺序给出每个 `TargetInstallReceipt`：`INSTALLED / FAILED /
ROLLED_BACK`。普通单项失败保留已成功项并返回 `PARTIALLY_INSTALLED`，不承诺跨目标原子性；全部失败
返回 `FAILED` 且不发布注册；发布前 loader 或 epoch 改变会关闭本次已安装项并返回 `STALE_IDENTITY`。
`ROLLED_BACK` 表示已尝试关闭，并不保证入口恢复；回执包含聚合 close 结果。部分注册再次用同 ID
安装会冲突，须先 close；同一环境中入口恢复为 `NOT_RESTORED` 或 `UNKNOWN` 时拒绝同 ID 重装，
恢复为 `RESTORED` 则允许重试，即使物理制品仍驻留。没有已安装项的失败不会因回滚被隔离。
隔离仅覆盖当前环境和 ID，不提供卸载重试或跨环境保护；close 保持幂等。

单目标 replacement 的 `BackendInstallException` 表示后端保证未提交且旧 handle 有效；其他 Throwable
（包括 Kotlin 等 JVM 实现抛出的未声明受检异常）或 null handle 表示结果未知；异常会原样传播，
null 会引发异常，同时停止新旧 SDK 回调并在同一环境隔离该 ID，
不会尝试卸载可能已失效的旧 handle。此状态不证明框架入口恢复或物理释放。
backend 的 `BackendInstallException` 必须保证该次调用未安装入口，才可形成逐项失败回执。若 backend
安装或接管抛出其他 Throwable 时，core 对已取得的句柄按安装或接管合同清理或保留并传播原异常；
抛错调用未返回的句柄及其副作用无法确认，因此该 ID 在当前环境中隔离，即使已取得句柄的入口
报告恢复也不会自动解除。已成功安装后身份复核抛出异常，同样不伪装为普通失败或成功回执。
声明的 `BackendInstallException` 子类若在生成失败回执时抛异常，当前目标仍视为未提交，已知的
先前句柄按保守隔离规则处理，诊断异常原样传播。
安装完成后若环境发布或回执构造中断，core 通过独立 guard 保留注册、关闭已取得的句柄并隔离该 ID；
这不保证隔离自身遭异步致命事件中断或无法分配必要状态时一定成功。

`CloseReport`：状态（`CLOSED` 或 `RETIRED_RESIDENT`）、是否拒绝新回调、入口恢复、在途观察数、
物理释放（默认 `NOT_CLAIMED`）。`close()` 幂等，不等待宿主调用，不撤销 raw Member。
卸载抛出运行时异常时无法判断入口是否已恢复，报告 `UNKNOWN` 并记录失败。环境关闭时，注册抛出的
非致命 `Error` 或未处理的运行时异常不会跳过后续注册：所有已知注册均尝试关闭，未确认入口仍保留
引用，然后重抛首个 `Error`（若无 `Error` 则重抛首个未处理的运行时异常）。
后端卸载若通过 Kotlin 等方式抛出 Java 签名未声明的受检异常，同样按恢复状态未知处理并继续
关闭其余句柄；捕获到 `InterruptedException` 时会在该注册关闭结束时恢复中断位。环境关闭还会
恢复进入时已有或各注册关闭后观察到的中断，无法恢复后端在单次调用内部自行产生并清除的信号。
目标泛型签名或异常文本无法格式化时，报告使用简化描述，不将格式化故障当作卸载失败。
致命 VM 事件立即传播，后续注册不宣称已关闭。环境和注册关闭均不自动重试未知卸载。

`HookEnvironment.requestClose()` 只标记关闭请求并拒绝后续准入，不执行卸载或等待已领取
事务；现有回调仍可能运行。调用方须另行协调并调用同步 `close()` 完成清理，详见
[显式请求关闭环境](../close-request.md)。

## 4. 生命周期与借用规则

- `HookInvocation` 仅在执行回调的线程、回调返回前有效；跨线程或过期访问抛
  `HookContractException`。重入获得独立 frame。
- 单 after 且无 before 时在绑定期选用专用 channel 并复用该调用的 frame；独立结果槽与 invocation 均在回调退出时失效。
  这不改变原方法、异常与诊断顺序，也不改变多回调各自的借用寿命。
- 有多个回调时，after 回调收到的 `invocation` 与 `result` 可能是同一借用对象；不要依赖二者的对象身份。
- `AroundInvocation.proceed()` 每次 invocation 至多一次；第二次、跨线程、回调后调用均为合同异常。
- `entrySnapshot()` 是浅槽快照：数组复制、对象共享。
- 在 `around` 中需要保存某个时刻的参数槽时，可显式调用
  `Object[] saved = InvocationArguments.copyOf(invocation);`。它按槽顺序读取当时的实时参数，
  并非自动入口快照或跨槽原子读取；数组独立、对象共享，调用时仍须遵守回调线程与生命周期规则。
- `HookRegistration.close()` 后到达框架入口的调用走 `passthrough`：原方法恰好一次，无 SDK 回调。

## 5. 身份模型

`ProcessIdentity`、`CodeScopeIdentity`（实际 loader 引用）、`CodeEpoch`、`HookGeneration`、
`ServiceEpoch`、`ConfigRevision`、`NativeArtifactIdentity` 分别建模。后三者在本切片仅为身份类型，
不创建 service、配置会话或 native 制品，也不会被默认路径实例化。

## 6. 本切片明确拒绝的形状

- `ChainSemantics` 非 `FRAMEWORK_CHAIN` 的 backend（classic/local-only）→ `UNSUPPORTED_CAPABILITY`。
- 必需能力为 `UNSUPPORTED` 或 `UNKNOWN`（含未声明）→ `UNSUPPORTED_CAPABILITY`，UNKNOWN 不视为支持。
- 101 backend 支持同一框架入口内的 SDK 单目标 channel 原子切换；模块级 `HOT_RELOAD` 仍为
  UNSUPPORTED，101 不引用 102 专有 API。API102 模块代际接入及调用方责任见 `docs/hot-reload-102.md`。

## 7. API dump

运行 `./gradlew apiCheck` 对照公开 API 清单；变更接口时应审查兼容性并更新对应清单。
