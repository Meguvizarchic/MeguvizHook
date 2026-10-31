# MeguvizHook

独立的 Android Hook SDK：自有 Java API、JVM 执行核心、互斥的 libxposed 后端适配制品与手工入口样例。
真实的方法拦截始终由所选运行框架提供——本项目**不**替代管理器、注入器或 ART。

当前状态：**最小可构建切片（0.1.0-SNAPSHOT，未发布）**。JVM 合同与构建已验证；
真实框架/设备资格全部未执行（见下方“未验证范围”）。

## 模块

| 模块 | 说明 |
|---|---|
| `hook-api` | 公开合同类型：身份、能力、参数形状、回执、生命周期（Java 8，纯 JDK） |
| `hook-core` | `HookEnvironment`、安装期 planner、热路径 dispatcher、backend SPI |
| `backend-libxposed-102` | libxposed API 102 适配（Android library，框架 API 为 `compileOnly`） |
| `hook-testing` | fake backend、独立语义 oracle、反射 fixtures（非真实后端） |
| `sample-java-contracts` | 纯 JVM 的 Java 消费样例 |
| `sample-module-102` | 手工 Java 入口的 102 模块 APK（含 R8 release 构建） |

刻意未创建空模块：`hook-dsl`、`backend-libxposed-101`、service、codegen、doctor、native/Rust
均属后续任务，见 `docs/HANDOFF.md` 的下一批清单。

## 工具链（跟随 BIL）

Gradle **9.8.2**（wrapper 含 SHA-256 校验）· AGP **9.4.1** · libxposed api **102.0.0** ·
compileSdk **37** / minSdk **27** / targetSdk **37** · Android 模块 Java **17** · api/core Java **8**。
本切片为纯 Java：`android.builtInKotlin=false`，发布 variant 不含 `kotlin-stdlib`。
本环境实际使用 JDK 20 构建；BIL CI 使用 JDK 27，二者一致性尚未验证。

## 构建与测试

```bash
# 环境：JDK 17+（本切片实际用 JDK 20）、Android SDK platform android-37.0 + build-tools 37.0.0
./gradlew :hook-testing:test                          # JVM 语义测试（fake backend + 独立 oracle）
./gradlew :backend-libxposed-102:testDebugUnitTest    # 102 adapter 的 stub 框架映射测试
./gradlew :sample-java-contracts:run                  # 纯 Java 样例，输出 SAMPLE_OK
./gradlew :backend-libxposed-102:assembleRelease :sample-module-102:assembleRelease
./gradlew tasks --all                                 # 先发现真实任务，再选择构建任务
```

## 语义要点（CONTRACT-001 冻结）

- 参数形状 `NO_ARGS / READ_ARGS / ENTRY_SNAPSHOT / MUTABLE_ARGS / AROUND` 各自独立；
  快照是**浅槽快照**，不深度复制对象。
- 默认 `ErrorPolicy.PROPAGATE`：before 失败则原方法 0 次；原方法异常保持同一实例；
  after 失败绝不重放原方法；around 每次调用至多一次 proceed；致命 VM 错误不被吞。
- 注册/关闭：`logicalId` 冲突不静默覆盖；close 拒绝新回调、报告在途与恢复层级，
  不承诺物理卸载，不撤销 raw Member。
- 普通回调路径本 SDK 新增 JNI=0、IPC=0；未选 native 新增 so=0；无默认 service/线程池/热重载。

详见 `docs/adr/ADR-0001-runtime-contract.md` 与 `docs/contracts/callback_contract.md`。

## 证据与未验证范围

- 59 个 JVM/适配器测试通过（`work/hook/dispatch_conformance_results.json`，由真实 JUnit 报告生成）。
- release APK 审计：不含框架类、不含 `.so`、入口 metadata 完整
  （`work/hook/release_artifact_audit.json`）。
- **未验证**：真实 102 运行时装载与执行、R8 后设备可达性、真实类加载与跨模块优先级、
  101 adapter、service、热重载、native、任何性能阈值、JDK 27 一致性。
  这些项保持 not_run / unavailable，不由单测或构建成功推断。

文档导航：`docs/HANDOFF.md`（本轮移交与下一批任务）、`work/`（机器可读证据与原始日志）。
