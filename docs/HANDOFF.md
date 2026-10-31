# MeguvizHook 本轮移交（最小可构建切片）

日期：2026-10-31。范围：仅 MeguvizHook 本地仓库；未修改 BIL 或其他 Meguviz 仓库。
任务状态以 `work/progress.json` 为准；task done ≠ release qualified（本切片 `release_qualified=false`）。

## 1. 实际完成的任务及代码

| 任务 | 状态 | 交付 |
|---|---|---|
| INIT-001/002/003 | done | 新仓库骨架、Gradle 9.8.2 wrapper（SHA-256 锁定）、版本目录、LICENSE/NOTICE |
| HOOK-001 | done | 基线盘点、能力矩阵、102 上游行为盘点（基于真实 AAR/sources） |
| CONTRACT-001 | done | `docs/adr/ADR-0001` + 独立反例 oracle |
| HOOK-002 | done | `hook-api` 25 个公开类型、API dump、消费合同文档 |
| HOOK-004 | done | `hook-core` dispatcher、参数形状、proceed/重入、fatal 处理 |
| HOOK-005 | partial | 安装期裁剪/绑定/epoch/冲突；`MatchPolicy.ALL` 明确拒绝；无解析缓存 |
| HOOK-008 | partial | 默认注册关闭、在途计数、代次退休（无热重载） |
| HOOK-003 | partial | `backend-libxposed-102` + sample；101 未开始 |
| INT-001/002 | done | 工具链矩阵、装配 ADR、Java 消费边界报告 |
| INT-003 | partial | 手工 Java 入口 + `META-INF/xposed` 三件套，R8 release 构建 |
| INT-005 | partial | 纯 JVM 最小 consumer 样例 |
| VAL-001/INT-009 | partial | 59 项 JVM/stub 测试；compileOnly/R8 构建审计；无设备 cell |
| GATE-001 | done | 草案 profile + B01–B10 + raw schema（无阈值、无数据） |

## 2. 源码/制品/工具链身份

- 工具链：Gradle 9.8.2（发行版 SHA-256 与 BIL 一致）、AGP 9.4.1、libxposed api 102.0.0
  （AAR `423484a6…7914d0`）、compileSdk 37 / minSdk 27、JDK 20（BIL CI 为 27，未对齐）。
- 制品：`backend-libxposed-102-release.aar`（`dcb63246…f13a8fb`）、
  `sample-module-102-release-unsigned.apk`（`12203dfe…f2b435c`，R8，未签名）。
- commit：以仓库 `git log` 为准；证据文件不写入机器相关路径之外的敏感信息。

## 3. 实际运行命令、返回码与原始证据

见 `work/integration/toolchain_matrix.md` 第 2 节与 `work/evidence/logs/`：
`gradle-tasks-all.log`、`full-build.log`（59 tests/0 failure + SAMPLE_OK）、`android-release.log`、
`sample-release-runtime-deps.log`、`profile-draft-check.log`、`profile-frozen-check-expected-reject.log`。
测试证据由真实 JUnit XML 生成：`work/hook/dispatch_conformance_results.json`。

## 4. 验证层级与覆盖

- **源码/合同级**：CONTRACT-001 冻结，独立 oracle 反例清单。
- **JVM/fake 级**：55 项测试（状态机、参数形状、重入/生命周期、安装合同、有界诊断）。
- **adapter 映射级**：4 项 stub 框架测试（PASSTHROUGH 强制、结果/异常/passthrough 映射、unhook）。
- **构建级**：JVM jar、AAR、R8 release APK；APK 无框架类/无 `.so`/入口 metadata 完整；
  releaseRuntimeClasspath 无 libxposed、无 kotlin-stdlib、无第二 backend。
- **设备级**：无任何证据，全部 not_run / unavailable。

## 5. 失败、负结果与未执行项目

- 过程中修复：wrapper 下载超时（改镜像 + 校验）、损坏的全局 init.gradle（备份停用）、
  Kotlin DSL 扩展写法、ThreadDeath removal lint、三处测试夹具缺陷（并发计数、child-first loader、
  校验异常误捕获）。无“为通过而改断言语义”的修改：类型校验用例改为让异常传播给 dispatcher。
- 未执行：真实框架装载、instrumented test、lint、签名、101 adapter、service、热重载、native、
  性能测量、JDK 27 构建。
- 远端：GitHub 连接器授权 对 `POST /user/repos` 返回 `Resource not accessible by integration`，
  **公开远端仓库未能创建**；本地仓库已提交，远端创建与推送待授权后执行（见第 7 节回退/后续）。

## 6. 当前 gate 与回退

- GATE-001 完成（草案）；GATE-002 阻塞（需真实 A/A）；VAL-002 阻塞（需真实 cell）。
- 回退方法：本切片全部为新增文件，可整目录移除或 `git reset --hard <previous>` 回到空仓库；
  功能级回退用 `HookRegistration.close()`（拒绝新回调、原方法恰好一次、报告退休层级），
  不在部分安装后切换 backend 重放。预置 init.gradle 的备份为 `init.gradle.bak.meguvizhook`，可恢复。
- 出现语义回归/原方法重复执行/loader 泄漏时，停止启用对应能力并保持 fake/基线路径可用。

## 7. 已合并合同与未解决冲突

- 已冻结：CONTRACT-001、ADR-0002 装配、版本跟随 BIL 的偏差清单（JDK、内置 Kotlin、未用插件）。
- 未解决：JDK 27 一致性；`MatchPolicy.ALL` 的逐项回执设计；classic backend（101）dispatch 入口；
  远端仓库创建权限。

## 8. 下一批 ready 任务（按 DAG）

1. **HOOK-003 剩余**：`backend-libxposed-101` + `sample-module-101`（classic dispatch 需先定 SPI 形状，
   不得用 chain 模型伪装），制品互斥复查。
2. **HOOK-005 剩余**：`MatchPolicy.ALL` 逐项回执；如需缓存，先实现 epoch 感知的负缓存合同。
3. **INT-004**：薄 Kotlin DSL，证明与 Java 合同等价（可引入 BIL 的 Kotlin 2.4.21 对齐）。
4. **HOOK-007**：手工 Java/Kotlin 入口与 core 联调；**INT-005 剩余**：独立 Android consumer。
5. **INT-009 剩余/VAL-001 剩余**：真实 102 cell（装载、R8 可达性、类加载、优先级交错）——需设备。
6. **INT-011**：BIL 消费夹具（lazy args/常量快路/配置 snapshot 基线），A0/A1/A2 对照设计。
7. **GATE-002**：A/A 后冻结阈值；之后才允许 A/B 与任何性能结论。
8. 远端：获得仓库创建授权后 `git remote add origin <repo>` 并推送 main 与标签。
