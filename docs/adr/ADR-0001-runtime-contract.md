# ADR-0001：冻结 Hook 运行时语义与所有权合同（CONTRACT-001）

状态：Accepted（2026-10-31，本切片冻结；身份作用域与 API102 安装决策由 ADR-0003 补充）
实现负责人：HOOK-002（公开 API）/ HOOK-004（JVM dispatcher）/ HOOK-005（安装期计划）
验证层级包括 JVM fake 和独立 oracle，部分框架路径曾在隔离环境做功能检查；这不等于全设备兼容或性能资格。

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
| READ_ARGS_VIEW | 显式借用入口只读参数视图，回调结束即不可访问；保留原参数下传，不承诺深拷贝或框架零分配 | `BackendInvocation.readOnlyArguments()` |
| ENTRY_SNAPSHOT | 进入注册时的**浅槽快照**：复制数组、共享对象；不深拷贝、不回滚对象内部 | `args.clone()` |
| MUTABLE_ARGS | 写入经 primitive/引用/null 校验，对下游 continuation 生效；首次访问参数槽时获取后端参数数组，下游前已获取则传给下游 | `InvocationFrame`、`TypeChecks` |
| AROUND | 与 before/after 互斥（本版）；continuation at-most-once | `Dispatcher.dispatchAround` |

同一 invocation 不可跨线程重用；每个回调收到独立借用视图，其 invocation 和结果槽在该回调返回后
立即失效，即使其他回调正在处理同一次调用也不能复用旧视图。重入（含递归与同步回入）每层新建
frame，不共享 ThreadLocal 可变数组。独立视图引入的分配成本尚未测量。

恰好一个 before 且没有 after/around 时，当前调用帧自身可作为该回调唯一借用对象；回调结束后、
执行下游前即失效。该优化不复用跨调用对象，也不放宽重入、跨线程或回调结束后的访问限制。

`READ_ARGS_VIEW` 是新增的显式选择，不改变 `READ_ARGS` 和 `ENTRY_SNAPSHOT` 的现有行为。
101/102 使用按下标读取框架参数槽（`Chain.getArg`）的只读视图，不复制列表；旧后端默认复制数组回退；对象本身仍共享。视图不是可持久化快照，
也不承诺底层框架没有分配。框架继续使用原参数执行下游，而不是替换为 SDK 的数组。

`MUTABLE_ARGS` 与 `AROUND` 在回调首次读写参数槽时向后端获取一次参数数组
（`argumentCount()`/`argumentType()` 不触发获取），同一 frame 之后各阶段读写该数组。下游执行前
已获取时，下游收到同一数组，即使回调没有写入；下游执行前没有回调访问参数槽时，以 `null` 保留
后端原参数下传，之后的读取再向后端获取。后端未承诺此数组独占，回调在各阶段读取与下游一致的
参数槽；参数对象自身不会被深拷贝。获取失败的普通异常由触发访问的回调观察，致命事件仍直接重抛。
`READ_ARGS` 与 `ENTRY_SNAPSHOT` 仍在入口获取（后者再复制快照），下游收到该实时数组。

`HookSpec.Builder.replaceConstant(value)` 显式声明独占的常量替换，不能与回调或非 NO_ARGS 形状
组合。解析后在安装任何目标前校验返回类型，使用调用方提供的同一对象引用；执行仍受注册状态、
在途计数与关闭合同约束。该路径不创建普通回调帧，关闭后的框架入口仍按 PASSTHROUGH 处理。

`replaceResultOnSuccess(value)` 是独立且显式的结果形状：下游先携原参数执行一次，只有正常返回才
覆盖结果；异常保持原身份并按所选框架策略处理。101/102 的专用通道在下游期间持有租约，旧后端使用
带 invocation 的普通分发；它与直接跳过下游的常量替换不等价。

可选的 `FusedConstantDispatchChannel` 将准入、取值和退出合并为一次通道调用，仍保留状态双检和
在途计数。常量通道缓存所属注册的共享计数器并保留所属注册，保持原有状态读、原子加、
第二次状态读及退出原子减的次序。101/102 仅对明确实现该接口的通道启用。
未准入标记按引用识别，合法常量与标记是同一对象时
回退原通道；旧通道不因探测此优化而额外调用取值方法。core 与 backend 必须共享同一接口及标记的类加载身份。
此优化不改变框架异常保护策略、关闭或在途合同。

`replaceIfStringArgumentEquals(index, expected, result, observer)` 是另一种显式且独占的形状：
安装前检查全部目标的指定参数为 `String` 并检查返回值，比较使用 `Objects.equals(actual, expected)`，
缺槽按 null 处理。命中后先固定返回值、执行可选无参通知，再返回该值；未命中携原始参数执行下游一次。
通知不能读取或修改借用 invocation，通知或下游异常按原身份交给框架。匹配、通知及整个下游执行均计入
在途调用；101/102 可按绑定期确认的条件通道直接读取单槽，旧后端仍使用普通 dispatch 回退。
该形状不识别或改写任意 before 闭包；异常模式由显式选择的 `ErrorPolicy` 与后端能力决定。

## 3. proceed / 结果 / 异常状态机

状态：`NOT_CALLED / RUNNING / RETURNED / THREW`（`CallOutcome.State`）。结果与异常互斥。
SDK 自有包装默认**每次 invocation 至多一次下游执行**；更宽语义需要显式 backend 能力与独立
注册策略，本版不提供。

默认策略 `ErrorPolicy.PROPAGATE`（以下表格描述 SDK 对框架交出的行为）：

| 事件 | 冻结行为 | 反例（必须失败的情况） |
|---|---|---|
| before 抛异常，original 未开始 | 传播该异常，original=0 次，after 不执行 | 保护模式意外执行原方法 |
| before 设置显式 early 结果 | original=0 次，after 仍观察结果 | 早退后仍执行原方法 |
| original 正常返回 | 结果进入 after，可显式替换 | 未经校验替换返回类型 |
| original 抛异常 | 原 throwable 进入 after，未替换则**同一实例**抛出 | 吞异常、自动重新 proceed |
| after 抛异常，original 已成功 | 传播 after 异常，original 保持 1 次 | “恢复”导致第二次副作用 |
| after 抛异常，original 也已抛 | 保留原 throwable；after 失败记入**有界**诊断 | 替换原异常、额外再执行 |
| around proceed 后失败 | 传播 around 异常，proceed 次数不增加 | catch 后第二次 proceed |
| around 先设置 early outcome 再 proceed | 抛 `HookContractException`，original=0 次 | 已早退仍执行原方法 |
| around 第二次 proceed | `HookContractException`，original 不因此再执行 | 隐式多次 continuation |
| around 未 proceed 且无显式结果 | `HookContractException`，**不隐式调用原方法** | 静默放行原方法 |
| 同设返回与异常 | 结果槽后写覆盖先写，二者恒互斥 | 两个有效结果并存 |

Fatal VM 事件（`VirtualMachineError`、`ThreadDeath`）在 SDK 内任何阶段直接重抛给框架，不进入
after、不进入诊断、不视为 SDK 可恢复回调失败。原方法业务 throwable、backend 安装失败、取消、native panic 与普通
callback 失败分别分类；本切片无 native 路径。

显式选择 `ErrorPolicy.FRAMEWORK_PROTECTIVE` 时，只有声明 `Capability.FRAMEWORK_PROTECTIVE`
的 backend 才能安装；101/102 将它映射到框架 `ExceptionMode.PROTECTIVE`，SDK 不自行重试下游。
依上游合同，hooker 在 proceed 前失败由框架继续下游，在 proceed 后失败由框架返回已完成的下游
结果或异常，下游本身的异常继续传播。框架最终是否处理 fatal 由其实现决定，SDK 不能承诺向宿主
穿透；多 after/around 的 SDK 状态机不因此等同于 BIL DSL。当前为 API 合同与 JVM 代理模型证据，
不等于真实框架验证或对象副作用回滚。
若框架在 SDK hooker 抛错退出后自行继续下游，该框架回退不属于已结束的 SDK 在途租约；
`CloseReport` 的 SDK 在途数不能证明该框架下游已经排空。

多个 after 仍按逆序执行；一旦 after 失败，最终异常固定为首次 after 失败，若原方法已失败则固定为
原异常。后续 after 不能通过修改结果槽覆盖该失败。双失败时诊断 sink 的普通异常不覆盖原异常，
致命事件仍直接重抛。

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

- 单个 `HookEnvironment` 内重复 logicalId + 相同 target：完整语义一致（含回调实例、优先级、进程过滤、能力、形状、策略）
  返回 `ALREADY_INSTALLED`，否则 `CONFLICT`，**不静默覆盖**。部分安装不得伪装成幂等成功；
  须先 close 旧代，后续 install 才可发布新 generation。不同环境的 ID 作用域见 ADR-0003。
- 重复安装前核对已安装 plan 的 loader 与 code epoch；身份不一致返回 `STALE_IDENTITY`，保留旧代供
  调用方 close。安装使用固定的 loader 与 epoch，并在解析和发布前检查身份；安装期间发现变化则回滚。
  这些是采样检查，backend 未提供跨组件原子身份快照时，不保证最后一次检查后身份不会再变化。
- 同一线程在 `install`、`adopt` 或 `replace` 的外部回调中再次修改同一 ID 时，入口在调用
  backend 前抛出 `IllegalStateException`；不同 ID 的嵌套仍可进行。上述事务中同步重入
  `environment.close()` 也在改变关闭状态前抛出该异常；其他线程的 close 仍等待事务释放环境锁。
  后端执行替换期间，同步重入旧 `HookRegistration.close()` 会在卸载旧入口前被拒绝。如果
  拒绝异常从后端外逸，沿用未检查异常可能已产生副作用的隔离合同，不宣称回滚成功。
  后端正在卸载同一注册时同步重入该注册的 `close()` 同样明确拒绝，不返回尚未生成的空报告；
  若首次卸载已被致命事件中断，后续一次性 close 仍不重试或伪造完成报告。普通注册关闭在
  注册监视器内认领句柄，在锁外执行卸载并在锁内发布最终报告；其他线程的重复关闭等待报告。
  后端不得同步等待另一线程完成同一注册的关闭，否则仍会互等。
  现有安装、替换、回滚和环境关闭也可能在持环境锁时调用 resolver 或 backend；这些外部
  实现不得同步等待另一线程调用同一 environment，否则可能形成跨线程锁等待环。
- `close()` 不持锁等待宿主调用：竞态调用要么沿旧不可变 plan 完成，要么收到
  `DispatchResult.passthrough()` 由原方法恰好执行一次。
- `CloseReport` 分别声明：是否拒绝新回调、入口恢复（`RESTORED/NOT_RESTORED/UNKNOWN`）、
  close 时观察到的在途数、物理释放（默认 `NOT_CLAIMED`）、`CLOSED` 或 `RETIRED_RESIDENT`。
  SDK lease 归零不证明机器码返回地址已离开；本切片无 native，故不引入 dlclose 议题。
- 关闭或未发布安装的回滚若入口恢复为 `NOT_RESTORED/UNKNOWN`，当前环境保留句柄并拒绝同 ID
  重装；`RESTORED` 可重试，即使物理制品仍驻留。回滚回执包含聚合关闭结果，`ROLLED_BACK` 仅表示
  已尝试关闭。隔离不跨环境或 ID，幂等 close 不提供卸载重试。
- backend 卸载抛出运行时异常时，入口恢复状态为 `UNKNOWN`，因为它可能在实际卸载之前或之后抛出。
  环境关闭遇非致命 `Error` 或未处理的运行时异常时继续尝试其他已知注册，保留未确认恢复的注册
  和句柄后重抛首个 `Error`（若无 `Error` 则重抛首个未处理的运行时异常）；
  致命 VM 事件立即传播，后续注册不宣称已关闭，也不自动重试。
- backend 的 `BackendInstallException` 必须保证该次调用未安装入口，才能按逐项失败处理。安装抛出
  运行时异常或 `Error` 时尝试关闭已取得的句柄并传播异常；抛错调用未返回的句柄及副作用未知，
  该 ID 在当前环境内保持隔离，不能仅因其他句柄已恢复就安全重试。
- 已安装句柄的回执构造或环境发布中断时，独立于发布 map 的 guard 保留注册，关闭已知句柄并隔离
  同环境同 ID。该保证覆盖标准 map 与受控发布故障；资源耗尽或隔离自身被异步致命事件中断时，
  不承诺 guard 和清理一定完成。
- `HookRegistration` 不实现 `AutoCloseable`：`close()` 需要返回报告，与 void 签名不兼容。

### 单目标原子替换（HOOK-006）

- 替换调用可能已提交但句柄未知，或提交后环境发布失败时，在当前环境隔离同 ID，并令旧、新
  channel 都拒绝新回调；保留可取得的两代引用和 handle，不尝试卸载可能失效的 handle。旧 close
  与 backend 替换及环境发布串行化。该保证覆盖标准 map 和受控发布故障；隔离自身遭异步致命事件
  中断或无法分配报告时，不承诺一定完成状态记录，也不撤销已进入的回调。

- `HookEnvironment.replace(HookSpec)` 只替换当前同 `HookId` 的完整单目标注册；不隐式 install，
  不接受 partial、`MatchPolicy.ALL`、不同 owner/priority/errorPolicy/resolved executable，也不允许跨 loader/epoch。
- `forceReplace(HookSpec)` 显式要求生成新代：即使计划语义完全相同也执行同一原子替换流程，
  返回新注册，旧注册关闭不会卸载新代。它不放宽上述任何校验，也不将失败改成 close+install；
  普通 `replace` 仍对相同语义返回 `ALREADY_INSTALLED`，不会无故创建新代。
- 后端必须声明 `ATOMIC_REPLACE=SUPPORTED_IN_DECLARED_CELL`，UNKNOWN 不得视为支持。102 使用
  框架 `replaceHook`；101 保留一个框架入口，在 SDK 内原子切换单目标 channel，不提供框架原生替换。
- `BackendHandle.replace` 的返回点是提交点：返回非 null 新 handle 后旧 handle 失效，卸载所有权转移
  到新 generation；`BackendInstallException` 必须保证未提交且旧 handle 仍有效。unchecked 异常、Error
  或 null 返回意味着结果未知，不得转换成旧代仍有效的失败回执。禁止 close+install 或 install+close 模拟。
- replace 与同一 registration 的 close 在注册监视器内竞争认领；一旦 close 开始卸载，replace
  会观察到 `RETIRING` 而不会接管句柄。替换本身仍以环境→注册的锁序执行。
  普通环境关闭先在环境监视器内标记并快照，再在锁外逐个关闭注册，不同注册可并行直接关闭。
  关闭尚在进行时，`activeRegistrationCount()` 可能观察到尚未从注册表移除的条目；它不能作为
  关闭完成或框架实际卸载完成的凭据。
  调用者已持有环境或注册监视器时，外层锁不会由 SDK 释放，仍可能形成锁环。后端不得同步等待
  需要当前持有的环境或注册监视器的其他线程。旧 close 幂等返回 superseded 报告，不得删除新 handle。
- 关闭进行中，其他线程的重复环境关闭等待首次关闭完成；若关闭过程中后端同步等待另一个线程的
  环境关闭调用返回，仍可能互等。直接卸载线程重入正在等待其注册的环境关闭时不会自我等待。
- 旧 in-flight snapshot 继续使用不可变旧 plan 完成，替换不等待业务回调；未进入旧 channel 的旧
  snapshot 按 PASSTHROUGH 处理。新 generation 使用新 plan/channel，不继承旧 in-flight 计数。
- 后端成功后不再执行可能返回普通失败的检查；epoch/loader 验证必须在提交前完成。回滚不假装能
  通过卸载新 hook 恢复旧 hook。真实 102 框架替换仍需要独立设备资格。
- 替换结果未知时，在旧注册监视器内令新旧 channel 拒绝新 SDK 回调，保留已取得的引用且不对可能
  已失效的旧 handle 执行 unhook；原 unchecked/Error 按 fatal 优先级传播，旧 close 返回入口 UNKNOWN。
  同一 environment 的该 ID 被隔离，不提供对未知 handle 的卸载或跨 environment 保护；已进入的调用
  可以继续完成，不证明物理卸载或框架入口恢复。

### API102 模块代际接手

- 新代在框架 `onHotReloaded` 内使用 `HookEnvironment.adopt`，通过与同一 backend 绑定的单次桥接
  显式接手框架提供的旧句柄；只支持一个目标，不猜测同目标多个句柄的归属，不保证跨目标原子性。
- 旧代由模块负责清理线程、外部回调和其他资源；待接手句柄不能先通过旧 environment 关闭。
  新代模块须处理未接手句柄并重建自身状态，不能跨 classloader 传递旧 SDK 对象。
- 旧句柄替换已提交后无法通过卸载新句柄恢复旧代；若提交后身份检查或发布失败，停止新 channel、
  保留已知句柄并隔离该 ID，不宣称回滚。实际框架热重载支持及模块资源清理由调用方确认。

## 6. 线程与热路径预算

热路径只读 `BoundPlan` 冻结字段、执行业务回调与必要类型/寿命检查。不做：声明重解析、manager
发现、磁盘/网络配置读取、同步 shell、类路径扫描、无界日志、service 连接、线程池、JNI、IPC。
无 before 且恰好一个 after 时，注册绑定时选用专用 channel，frame 直接作为该回调的唯一 invocation 借用，结果槽仍为独立对象；
回调结束即共同失效，原方法异常、after 异常及诊断优先级与通用路径相同。多个回调仍各用独立借用；
多回调计划中每个 after 回调的 invocation 与结果视图是同一借用对象并共同失效，before、around 与
frame 视图不实现 `InvocationResult`，因此强转不能扩大形状。
通用 plan channel（before、多回调、around）同样提供可选的直接值传输（`DirectAfterDispatchChannel`
经 `CoreAfterBridge` 绑定）：返回值或原样抛出 THREW 异常，未准入时返回标记并由后端恰好执行一次原方法，
与 `dispatch()` 共用同一状态机、准入与在途租约；未绑定的后端继续使用 `DispatchResult`。
诊断默认关闭，容量有界（`BoundedDiagnosticsSink`）。未选择 native 时本切片新增 so=0（无 native 模块）。

## 7. 独立 oracle 与反例清单

`hook-testing/.../ContractOracle.java` 由合同文本独立编写期望（original 次数、异常身份、after 是否
执行、是否记诊断），不引用 dispatcher 实现。测试覆盖：无参/多参/primitive/null/void/构造器、
before 早退与失败、original 返回/抛出、after 恢复/失败/双失败、around 成功/失败/proceed 后失败/
二次 proceed/未决、重入、跨线程误用、回调后访问、并发调用与 close、logicalId 冲突与幂等、
epoch 失效、loader 同名不同身份、桥接方法排除、静态返回检查、FIRST/UNIQUE/ALL、进程裁剪、
有界诊断溢出。

未覆盖（保持 not_run/unavailable，不记为通过）：其他框架或设备、完整真实类加载与
优先级交错、classic backend dispatch、热重载、service、native、性能阈值。单次 LSPatch 集成模式
及单次 Vector 系统注入已覆盖 R8 入口、API102 安装、替换与关闭后的受控调用；单次原生 API101
cell 覆盖安装、拒绝替换与关闭后调用。它们不代表全部框架和设备。
