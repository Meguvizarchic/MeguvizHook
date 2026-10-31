# ADR-0001：冻结 Hook 运行时语义与所有权合同（CONTRACT-001）

状态：Accepted（2026-10-31，本切片冻结；修改需新 ADR 与同步 oracle）
实现负责人：HOOK-002（公开 API）/ HOOK-004（JVM dispatcher）/ HOOK-005（安装期计划）
验证层级：JVM fake + 独立 oracle；真实框架 cell 未执行（见 `work/hook/release_artifact_audit.json`）

## 1. 身份与 owner

- 七类身份分别建模，互不共用整数：`ProcessIdentity`、`CodeScopeIdentity`、`CodeEpoch`、
  `HookGeneration`、`ServiceEpoch`、`ConfigRevision`、`NativeArtifactIdentity`
  （`hook-api/.../identity/`）。框架 API 号、服务协议号、C ABI 号不进入同一个身份。
- 安装绑定**实际** loader / Member / owner 与本代不可变计划 `BoundPlan`。`ClassLoader.toString()`
  不作键，pid 不单独作长期进程身份。
- `BoundPlan` 含运行时引用，不跨进程、不跨启动复用；可持久化的是 `DescriptorTarget`。
- 接受 raw `Member`（`TargetSpec.ofMember`）是显式 owner escape：`close()` 不撤销反射对象，
  不承诺 defining loader 可回收。
- 本切片**不实现任何解析缓存**，因此不存在跨 epoch 复用的负缓存；未来引入缓存时键必须覆盖
  loader、成员种类、参数、返回类型、静态性、查找策略与初始化要求。

## 2. 参数形状

| 形状 | 本切片冻结合同 | 实现点 |
|---|---|---|
| NO_ARGS | 不调用 backend 的 `arguments()`；参数读取抛 `HookContractException` | `InvocationFrame` 构造分支 |
| READ_ARGS | 仅当前线程、当前回调内读取；逃逸/跨线程抛合同异常 | `InvocationFrame.check()` |
| ENTRY_SNAPSHOT | 进入注册时的**浅槽快照**：复制数组、共享对象；不深拷贝、不回滚对象内部 | `args.clone()` |
| MUTABLE_ARGS | 写入经 primitive/引用/null 校验，对下游 continuation 生效 | `TypeChecks` |
| AROUND | 与 before/after 互斥（本版）；continuation at-most-once | `Dispatcher.dispatchAround` |

同一 invocation 不可跨线程重用；回调返回后立即失效。重入（含递归与同步回入）每层新建
frame，不共享 ThreadLocal 可变数组。

## 3. proceed / 结果 / 异常状态机

状态：`NOT_CALLED / RUNNING / RETURNED / THREW`（`CallOutcome.State`）。结果与异常互斥。
SDK 自有包装默认**每次 invocation 至多一次下游执行**；更宽语义需要显式 backend 能力与独立
注册策略，本版不提供。

默认策略 `ErrorPolicy.PROPAGATE`（唯一默认，兼容 preset 需另名另测）：

| 事件 | 冻结行为 | 反例（必须失败的情况） |
|---|---|---|
| before 抛异常，original 未开始 | 传播该异常，original=0 次，after 不执行 | 保护模式意外执行原方法 |
| before 设置显式 early 结果 | original=0 次，after 仍观察结果 | 早退后仍执行原方法 |
| original 正常返回 | 结果进入 after，可显式替换 | 未经校验替换返回类型 |
| original 抛异常 | 原 throwable 进入 after，未替换则**同一实例**抛出 | 吞异常、自动重新 proceed |
| after 抛异常，original 已成功 | 传播 after 异常，original 保持 1 次 | “恢复”导致第二次副作用 |
| after 抛异常，original 也已抛 | 保留原 throwable；after 失败记入**有界**诊断 | 替换原异常、额外再执行 |
| around proceed 后失败 | 传播 around 异常，proceed 次数不增加 | catch 后第二次 proceed |
| around 第二次 proceed | `HookContractException`，original 不因此再执行 | 隐式多次 continuation |
| around 未 proceed 且无显式结果 | `HookContractException`，**不隐式调用原方法** | 静默放行原方法 |
| 同设返回与异常 | 结果槽后写覆盖先写，二者恒互斥 | 两个有效结果并存 |

Fatal VM 事件（`VirtualMachineError`、`ThreadDeath`）在任何阶段直接重抛，不进入 after、不进入
诊断、不视为可恢复回调失败。原方法业务 throwable、backend 安装失败、取消、native panic 与普通
callback 失败分别分类；本切片无 native 路径。

保护只涉及返回槽/异常槽/回调控制状态，**不回滚**宿主对象、参数对象内部或外部 I/O 副作用。

## 4. 阶段与顺序

- 安装期冻结 before/around/after 列表与静态返回检查；更新产生新 generation。
- 同一注册内 before 正序、after 逆序；after 在 early return 与 downstream throw 时执行，
  在 before 失败时不执行。
- 不跨其他模块回调重排序；融合只允许同 owner、同外部注册位置、同优先级（本切片未做融合优化）。
- `ChainSemantics` 区分 `FRAMEWORK_CHAIN / MODULE_LOCAL_CHAIN / CLASSIC_CALLBACKS / UNAVAILABLE`。
  本切片只 dispatch `FRAMEWORK_CHAIN`；其他 backend 形状在安装时返回 `UNSUPPORTED_CAPABILITY`，
  不模拟等价。
- `MatchPolicy.ALL` 在同一 logical registration 下为每个 target 建立独立 BoundPlan 与回执。
  普通单项失败不撤销其他成功项，总体返回 `PARTIALLY_INSTALLED`，不承诺跨 target 原子性；
  全部失败不发布注册。发布前 code epoch 改变则关闭本次已装项，逐项记 `ROLLED_BACK`。

## 5. 注册 / 移除 / 替换

- 重复 logicalId + 相同 target：完整语义一致（含回调实例、优先级、进程过滤、能力、形状、策略）
  返回 `ALREADY_INSTALLED`，否则 `CONFLICT`，**不静默覆盖**。部分安装不得伪装成幂等成功；
  须先 close 旧代，后续 install 才可发布新 generation。
- `close()` 不持锁等待宿主调用：竞态调用要么沿旧不可变 plan 完成，要么收到
  `DispatchResult.passthrough()` 由原方法恰好执行一次。
- `CloseReport` 分别声明：是否拒绝新回调、入口恢复（`RESTORED/NOT_RESTORED/UNKNOWN`）、
  close 时观察到的在途数、物理释放（默认 `NOT_CLAIMED`）、`CLOSED` 或 `RETIRED_RESIDENT`。
  SDK lease 归零不证明机器码返回地址已离开；本切片无 native，故不引入 dlclose 议题。
- `HookRegistration` 不实现 `AutoCloseable`：`close()` 需要返回报告，与 void 签名不兼容。

## 6. 线程与热路径预算

热路径只读 `BoundPlan` 冻结字段、执行业务回调与必要类型/寿命检查。不做：声明重解析、manager
发现、磁盘/网络配置读取、同步 shell、类路径扫描、无界日志、service 连接、线程池、JNI、IPC。
诊断默认关闭，容量有界（`BoundedDiagnosticsSink`）。未选择 native 时本切片新增 so=0（无 native 模块）。

## 7. 独立 oracle 与反例清单

`hook-testing/.../ContractOracle.java` 由合同文本独立编写期望（original 次数、异常身份、after 是否
执行、是否记诊断），不引用 dispatcher 实现。测试覆盖：无参/多参/primitive/null/void/构造器、
before 早退与失败、original 返回/抛出、after 恢复/失败/双失败、around 成功/失败/proceed 后失败/
二次 proceed/未决、重入、跨线程误用、回调后访问、并发调用与 close、logicalId 冲突与幂等、
epoch 失效、loader 同名不同身份、桥接方法排除、静态返回检查、FIRST/UNIQUE/ALL、进程裁剪、
有界诊断溢出。

未覆盖（保持 not_run/unavailable，不记为通过）：真实框架 cell（装载、R8 后入口、真实类加载与
优先级交错）、classic backend dispatch、热替换、service、native、性能阈值。
