# MeguvizHook 移交（第七轮：BIL 只读对照设计）

日期：2026-10-31。范围仅 MeguvizHook 仓库；BIL 仅作只读上游参考，未修改其源码。
任务状态以 `work/progress.json` 为准；task done ≠ release qualified（当前仍为 false）。

## 第七轮结果（INT-011）

- 新增 `work/integration/bil_readonly_consumer_fixture.json`：固定 BIL `dba9ed9` 和 5 个源码 SHA-256，
  明确 internal facade 边界、基线快路、API101/102 分 cell 及 `PROTECT_HOST`/`PROPAGATE` 差异。
- B01/B02 已形成 A0 直接 backend、A1 BIL facade、A2 MeguvizHook 的同语义输入、执行约束和
  correctness 判据；BIL lazy/no-copy、常量快路、配置 snapshot 和成员缓存只算基线，不算 A2 新收益。
- 仅无异常 B01/B02 可比较；B03–B10 不自动获得 A1 对照。A1 执行仍需 BIL 自有只读 adapter/cell，
  本任务未修改 BIL、未执行性能测量、未冻结阈值，GATE-002 保持 blocked。
- 校验：`work/evidence/logs/round7-int011-fixture-check.log` 全部通过；`work/performance/workloads.md`
  已同步。回退：对第七轮提交执行 `git revert`，上一基线为 `2973006`；禁止强推。

## 第六轮结果（INT-005）

- 新增 `sample-module-kotlin-102`，与现有 Java API101/102 APK 相互独立；Kotlin102 使用 AGP 内建 Kotlin、
  DSL/core/backend102 与手工 `XposedModule` 入口，不包含另一代 API/backend。
- Java101/102 release 继续无 Kotlin stdlib/DSL/metadata；Kotlin102 明确选择 stdlib/DSL 和 Kotlin builtins
  metadata。R8 可内联移除 DSL 类，因此 Kotlin 选择装配以依赖图和 metadata 判定，入口另由 DEX 核实。
- 三条 release 均通过依赖、metadata、DEX 定义/引用与默认关闭项审计：无框架实现、testing、service、
  native、codegen/doctor、另一代 backend 或热重载引用。全量 80 tests / 0 failures，所有构建成功。
- 构建修正：外部 KGP Android 与 AGP 9 new DSL 不兼容，改用 BIL 同款内建 Kotlin；stdlib 排除曾影响 lint，
  已收窄并延迟到 releaseRuntimeClasspath；未退回旧 DSL，也未跳过失败任务。
- 证据：`work/integration/android_consumer_audit.json`、`work/evidence/logs/round6-int005.log`、
  `round6-*-release-runtime-deps.log`、`round6-android-consumer-dex-audit.log`。真实设备装载仍未验证。
- 回退：对第六轮提交执行 `git revert`，上一基线为 `eef285c`；禁止强推。

## 第五轮结果（HOOK-007）

- 新增纯 JVM `sample-kotlin-contracts`，只直接依赖 `hook-dsl` 与 `hook-testing`，没有直接 api/core
  依赖，实证 DSL 对 api/core 的公开传递暴露；复用同一 `HookEnvironment`、reflection resolver 和 fixture。
- Java/Kotlin 入口均完成 descriptor/member 安装、MUTABLE_ARGS、early return 和 close：Java 输出
  `SAMPLE_OK`，Kotlin 输出 `KOTLIN_SAMPLE_OK`，两者 original 次数、返回值和 CLOSED 状态一致。
- 全量回归：80 tests / 0 failures、双 JVM consumer、双 backend/双 sample release BUILD SUCCESSFUL。
  证据：`work/integration/consumer_contract_results.json`、`work/evidence/logs/round5-hook007.log`。
- 该结果仅证明 JVM/fake backend 合同，不是真实框架或设备装载资格；独立 Android consumer 属于 INT-005。
- 回退：对第五轮提交执行 `git revert`，上一基线为 `ba034b3`；禁止强推。

## 第四轮结果（INIT-002 增量）

- 上游固定为 `jichuo1/Bilibili_Innocent_Lab@dba9ed9a205171640a07779073d923b5b07a75b0`。
  AGP 9.4.1、Kotlin 2.4.21、libxposed 102.0.0、JUnit 4.13.2、Gradle 9.8.2 及 wrapper SHA、
  minSdk/targetSdk 27、Android Java 17 均逐项一致。
- 4 个 Android 模块已对齐 `compileSdk 37 minor 2`（platform `android-37.2`）与 build-tools 37.0.0。
- 移除源码未使用、BIL 未直接声明且 libxposed POM 未要求的 `androidx.annotation` 及全部 6 处引用；
  101 backend 保持项目独立 `101.0.0`，未引入 BIL 的 UI/service/native 等无关依赖。
- 验证：80 tests / 0 failures、`SAMPLE_OK`、双 backend/双 sample release BUILD SUCCESSFUL，
  backend release 类路径 annotation=0。证据：`work/integration/bil_dependency_alignment.json`、
  `work/evidence/logs/round4-bil-alignment.log`。本机 JDK 17 与 BIL CI JDK 27 parity 仍未验证。
- 回退：对第四轮对齐提交执行 `git revert`，上一基线为 `df5bc0d`；禁止强推。

## 第三轮结果（INT-004）

- `INT-004` 已完成：新增独立可选 `hook-dsl`，Kotlin 2.4.21 / JVM 1.8；所有构建器与安装扩展只委托
  既有 `TargetSpec`、`HookSpec.Builder`、`HookEnvironment`，无第二套 resolver、dispatcher 或反射 cache。
- 12 项 DSL 测试与 Java 共用 `Fixtures`/`FakeBackend`，覆盖同 fixture 对照、描述符边界、回执/关闭、
  before/after/around、无 outcome、二次 proceed 和回调身份；全量回归为 **80 tests / 0 failures**。
- `work/hook/api_dump_hook-dsl.txt` 来自实际 JAR 的 `javap -public`；无 public inline/PublishedApi，
  字节码 major=52，JAR 无 testing 类。独立 Kotlin consumer 尚未建立，属于下一项 HOOK-007。
- 第三轮在 JDK 17 完成双 backend/双 sample release 回归。`work/hook/round3_artifact_recheck.json`
  使用 build-tools 37.0.0 `dexdump` 复核：Java-only release 无 DSL/Kotlin 依赖、框架类定义或另一代 backend。
  初版检查曾把类型引用误作类定义，已按 `Class descriptor` 行修正并保留引用审计，未放宽检查。
- 证据：`work/hook/dsl_contract_results.json`、`work/evidence/logs/round3-final-verification.log`、
  `round3-sample-*-release-runtime-deps.log`、`round3-sample-*-dex-*.log`。
- 回退：对第三轮提交执行 `git revert`，上一基线为 `d434212`；禁止覆盖历史的强推。

### 下一批 ready 任务（按 DAG）

1. **INT-009/VAL-001/VAL-002**：真实 101/102 设备 cell（需要设备）；性能 A1 还需 BIL 自有只读 adapter。
2. **GATE-002**：先完成真实 A/A，再冻结阈值并执行 A/B；当前保持 blocked。
3. 其余 planned 任务没有在本轮已验证 DAG 中解锁，不擅自启动；远端仅发布源码、文档和脱敏证据。

## 第二轮历史记录

## 1. 本轮实际完成

| 任务 | 状态 | 实际代码/证据 |
|---|---|---|
| HOOK-003 | done（构建级） | 新增 `backend-libxposed-101`、`sample-module-101`；保留独立 102；双 release/R8/DEX/依赖审计 |
| HOOK-005 | done（当前合同） | `MatchPolicy.ALL` 多 BoundPlan、逐项 `TargetInstallReceipt`、部分/全失败、epoch 回滚、close 后重试 |
| HOOK-004 | done | 原有 dispatcher 语义不变，68 项回归通过 |
| HOOK-008 | partial | 多目标注册共享关闭状态与在途计数、逐项 unhook；热重载仍未实现 |
| INT-003 | partial | 101/102 手工 Java 入口与 metadata；R8 DEX 入口定义静态核实，未真实装载 |
| INT-009 | partial | 双 compileOnly、依赖图、AAR/APK/DEX 静态隔离通过；无设备 cell |
| VAL-001 | partial | 68 JVM/stub 测试通过；真实 101/102 cell 未执行 |
| GATE-001 | done | 草案性能 profile 不变；无阈值、无测量，GATE-002 仍阻塞 |

新增公开 API：`RegistrationReceipt.Status.PARTIALLY_INSTALLED`、`RegistrationReceipt.targets()`、
`TargetInstallReceipt`（`INSTALLED/FAILED/ROLLED_BACK`）。`isInstalled()` 只对全部目标成功为 true；
部分成功仍携带可关闭的 registration。

## 2. 关键源码事实与修正

- 真实 `io.github.libxposed:api:101.0.0` 源码证明 **101 已是 Hooker/Chain**，不是 classic callback。
  因而未新增伪 classic SPI；101/102 都映射到同一个 FRAMEWORK_CHAIN core。
- 101 无 `HookBuilder.setId`、`HookHandle.replaceHook` 和热重载入口：backend-101 不引用它们，
  `ATOMIC_REPLACE/HOT_RELOAD` 均明确 UNSUPPORTED。102 的 replacement 流程仍未实现。
- 两代默认异常模式都可能吞 hooker 失败；两个 adapter 都显式 `PASSTHROUGH` 以落实 PROPAGATE。
- `ALL` 普通部分失败不跨 target 回滚；发布前 epoch 改变则关闭本次已装项，不发布过期计划。
- 部分注册相同 ID 重试返回 CONFLICT；close 后允许新 generation，避免漏装目标被 ALREADY_INSTALLED 掩盖。

## 3. 实际执行命令与结果

```text
gradle :backend-libxposed-101:testDebugUnitTest :backend-libxposed-101:assembleRelease \
       :sample-module-101:assembleRelease --no-daemon --console=plain
  -> BUILD SUCCESSFUL（round2-101-initial.log）

gradle :hook-testing:test :sample-java-contracts:run \
       :backend-libxposed-101:testDebugUnitTest :backend-libxposed-102:testDebugUnitTest \
       --no-daemon --console=plain
  -> BUILD SUCCESSFUL；最终全量复跑 68 tests / 0 failures，SAMPLE_OK
     （round2-final-verification.log，含双代 release 任务）

gradle :backend-libxposed-101:assembleRelease :backend-libxposed-102:assembleRelease \
       :sample-module-101:assembleRelease :sample-module-102:assembleRelease \
       --no-daemon --console=plain
  -> BUILD SUCCESSFUL（round2-android-release.log）

gradle :sample-module-101:dependencies --configuration releaseRuntimeClasspath
gradle :sample-module-102:dependencies --configuration releaseRuntimeClasspath
  -> 各代仅含所选 backend，无 libxposed/kotlin-stdlib（两个独立日志）

apkanalyzer dex packages [--defined-only] <release APK>
  -> 入口定义、框架类缺席、101 无 102 API 引用（round2-sample-*-dex-*.log）
```

测试 JSON 由真实 JUnit XML 生成：`work/hook/dispatch_conformance_results.json`。
安装/epoch 证据：`work/hook/install_epoch_fixtures.json`。公开 API dump 已重新生成且无框架类型泄漏。

## 4. 制品与静态审计

| 制品 | SHA-256 | 大小 |
|---|---|---|
| `backend-libxposed-101-release.aar` | `77db265b45d84c91922be72e98cd8861cd884f1c703806181a17cceefa8999bb` | 7541 B |
| `sample-module-101-release-unsigned.apk` | `575036673a2edb26926acffda08d662048951107f1175366d7820715d76b3205` | 23409 B |
| `backend-libxposed-102-release.aar` | `dcb63246b5a0bf0a925124f820600d5238423bd95050382d2f438a132f13a8fb` | 7584 B |
| `sample-module-102-release-unsigned.apk` | `39672b91e2cf503939482ab52cfe7e4c755e69a5332e52d4a80276861dbd2b51` | 23681 B |

`work/hook/release_artifact_audit.json` 的 `static_checks_passed=true` 表示：

- 两个入口类均在 R8 DEX 中定义，`java_init.list` 与之一致，module.prop 指向正确代际；
- APK DEX 和 backend AAR 均无框架类定义；无 testing 类、`.so`、Kotlin metadata；
- releaseRuntimeClasspath 仅有所选 backend，无 libxposed、无 kotlin-stdlib、无另一代 backend；
- 101 DEX 方法/类引用表无 setId/replaceHook、热重载、API_102 或 libxposed102。

一次原始字节扫描把能力诊断文案 setId/replaceHook 误报为 API 引用；最终以 apkanalyzer 的
方法/类引用行复核，未放宽任何实际隔离要求。

## 5. 中间失败与修复

- 新测试把 `TestSupport.method()` 返回的 `Executable` 错写为 `Method`，编译失败后修正。
- 旧 FIRST 测试从总体 message 查目标名；改为直接断言结构化回执中的精确 Executable，断言更严格。
- 依赖报告一次对两个 task 共用一个 `--configuration`，其中一个输出全配置；拆分命令重新取证。
- 上轮 wrapper、init.gradle、ThreadDeath lint 与三处测试夹具问题见首轮日志/文档；无跳过失败测试。

## 6. 未验证范围与 gate

- 真实 101/102 框架装载、回调执行、R8 后设备可达性、真实 loader/跨模块优先级、unhook 实际恢复：
  全部 not_run。DEX 静态入口存在不等于设备加载成功。
- service、热重载、native、Kotlin DSL、KSP、doctor、签名、instrumented/lint、性能阈值：未执行或未实现。
- 本环境 JDK 20；BIL CI JDK 27 一致性仍未验证。
- GATE-002 需真实 A/A；VAL-002 需真实 101/102 cells，均保持 blocked。

## 7. 回退方法

- 代码回退：本轮是相对上一 commit 的新增/修改，可用 `git diff` 审查并 `git revert <round2-commit>`；
  不使用覆盖历史的强推。公开远端为 [jichuo1/MeguvizHook](https://github.com/jichuo1/MeguvizHook)；
  本轮上一基线为 `a761207`。
- 功能回退：保留单目标 FIRST/UNIQUE 和既有 102 路径；`ALL` 部分安装时调用 receipt.registration().close()
  逐项卸载。epoch 异常路径已由 core 自动回滚未发布项。禁止切换 backend 重放已发生副作用。
- 101 产品问题可移除 sample 对 backend-101 的依赖并回到 102 sample；不在同一 APK 混放两代。

## 8. 下一批 ready 任务（按 DAG）

1. **INT-004**：`hook-dsl` 薄 Kotlin DSL，与 Java 同 fixture 对照；版本跟随 BIL Kotlin 2.4.21，
   public inline/PublishedApi 符号纳入 ABI 清单。
2. **HOOK-007**：INT-004 后联调 Java/Kotlin 入口，复用同一 core，不建第二套反射 cache。
3. **INT-005 剩余**：独立 Android consumer、Java API101 与 Kotlin API102 最小 module 的默认关闭项核查。
4. **INT-011**：BIL 只读消费夹具与 A0/A1/A2 对照设计；不创建 BIL 开发任务。
5. **INT-009/VAL-001/VAL-002**：真实 101/102 设备 cell（需要设备）。
6. **GATE-002**：A/A 后才允许冻结阈值和 A/B。
7. 远端：仅发布源码、文档和证据；本机 SDK 配置、构建缓存、签名材料与开发包不上传。
