# MeguvizHook

独立的 Android Hook SDK：自有 Java API、JVM 执行核心、互斥的 libxposed 后端适配制品与手工入口样例。
真实的方法拦截始终由所选运行框架提供——本项目**不**替代管理器、注入器或 ART。

当前状态：**第三轮可构建切片（0.1.0-SNAPSHOT，未发布）**。101/102 双 backend、`MatchPolicy.ALL`、
可选 Kotlin DSL、JVM 合同和静态 release 隔离已验证；真实框架/设备资格全部未执行。

## 模块

| 模块 | 说明 |
|---|---|
| `hook-api` | 公开合同类型：身份、能力、参数形状、逐项回执、生命周期（Java 8，纯 JDK） |
| `hook-core` | `HookEnvironment`、安装期 planner、热路径 dispatcher、backend SPI |
| `hook-dsl` | 独立可选的薄 Kotlin DSL；只委托 api/core（Kotlin 2.4.21，JVM 1.8） |
| `backend-libxposed-101` | libxposed API 101 适配（独立 AAR，框架 API 为 `compileOnly`） |
| `backend-libxposed-102` | libxposed API 102 适配（独立 AAR，框架 API 为 `compileOnly`） |
| `hook-testing` | fake backend、独立语义 oracle、反射 fixtures（非真实后端） |
| `sample-java-contracts` | 纯 JVM 的 Java 消费样例 |
| `sample-module-101` | 手工 Java 入口的 101 模块 APK（R8 release） |
| `sample-module-102` | 手工 Java 入口的 102 模块 APK（R8 release） |

刻意未创建空模块：service、codegen、doctor、native/Rust 均属后续任务。
101 与 102 都是真实 `Hooker/Chain` 模型；本项目不把 101 伪装成 classic callback backend。

## 工具链（默认版本跟随 BIL）

Gradle **9.8.2**（wrapper 含 SHA-256 校验）· AGP **9.4.1** · Kotlin **2.4.21**（仅可选 DSL）·
libxposed api **102.0.0**（BIL 默认）+ **101.0.0**（独立 backend）· compileSdk **37** / minSdk **27** /
targetSdk **37** · Android 模块 Java **17** · api/core Java **8**、dsl JVM **1.8**。
Android 切片仍为纯 Java：`android.builtInKotlin=false`，不依赖 `hook-dsl`。
第三轮实际使用 JDK 17（前两轮为 JDK 20）；BIL CI 使用 JDK 27，一致性尚未验证。

## 构建与测试

公开仓库：[github.com/jichuo1/MeguvizHook](https://github.com/jichuo1/MeguvizHook)。

```bash
git clone https://github.com/jichuo1/MeguvizHook.git
cd MeguvizHook
# 环境：JDK 17+（第三轮实际用 JDK 17）、Android SDK platform android-37.0 + build-tools 37.0.0
./gradlew :hook-dsl:test :hook-testing:test :sample-java-contracts:run
./gradlew :backend-libxposed-101:testDebugUnitTest :backend-libxposed-102:testDebugUnitTest
./gradlew :backend-libxposed-101:assembleRelease :backend-libxposed-102:assembleRelease
./gradlew :sample-module-101:assembleRelease :sample-module-102:assembleRelease
./gradlew :sample-module-101:dependencies --configuration releaseRuntimeClasspath
./gradlew :sample-module-102:dependencies --configuration releaseRuntimeClasspath
./gradlew tasks --all
```

当前真实结果：**80 tests / 0 failures**（59 fake/core + 12 DSL + 5 adapter-101 + 4 adapter-102），样例输出
`SAMPLE_OK`。证据由 JUnit XML 生成，原始日志在 `work/evidence/logs/`。

## 语义要点（CONTRACT-001）

- 参数形状 `NO_ARGS / READ_ARGS / ENTRY_SNAPSHOT / MUTABLE_ARGS / AROUND` 各自独立；
  快照是**浅槽快照**，不深度复制对象。
- 默认 `ErrorPolicy.PROPAGATE`：before 失败则原方法 0 次；原方法异常保持同一实例；
  after 失败绝不重放原方法；around 每次调用至多一次 proceed；致命 VM 错误不被吞。
- `MatchPolicy.ALL`：一个逻辑注册对应多个独立计划。逐项目标回执区分 `INSTALLED / FAILED /
  ROLLED_BACK`；部分成功返回 `PARTIALLY_INSTALLED`，不承诺跨目标原子性；epoch 变化会回滚未发布项。
- 注册/关闭：logicalId 冲突不静默覆盖；部分注册须 close 后重试；close 拒绝新回调并逐项卸载，
  不承诺物理卸载，不撤销 raw Member。
- 普通回调路径本 SDK 新增 JNI=0、IPC=0；未选 native 新增 so=0；无默认 service/线程池/热重载。

详见 `docs/adr/ADR-0001-runtime-contract.md` 与 `docs/contracts/callback_contract.md`。

## 制品审计与未验证范围

`work/hook/release_artifact_audit.json`（第二轮）与 `work/hook/round3_artifact_recheck.json`
（第三轮 dexdump）记录两个 APK/AAR 的 SHA-256 和逐项静态检查：

- R8 DEX 中入口类真实存在，`java_init.list` 与入口 FQCN 一致；
- DEX/AAR 无框架类定义，releaseRuntimeClasspath 无 libxposed、kotlin-stdlib、hook-dsl 或另一代 backend；
- 101 DEX 无 `setId/replaceHook`、热重载或 `libxposed102` 引用；无 testing 类、`.so`、Kotlin metadata。

**仍未验证**：独立 Kotlin consumer（HOOK-007）、真实 101/102 装载与执行、R8 后设备可达性、
真实类加载与跨模块优先级、service、热重载、native、任何性能阈值、JDK 27 一致性。
task done ≠ release qualified。

文档导航：`docs/HANDOFF.md`（本轮移交与下一批任务）、`work/`（机器可读证据与原始日志）。
