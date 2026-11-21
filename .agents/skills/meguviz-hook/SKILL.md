---
name: meguviz-hook
description: 指导在 Android libxposed（API 101/102）模块中接入与迁移 MeguvizHook（io.github.jichuo1.meguviz.hook:*:1.2.0），替代手写 XposedInterface.hook、XC_MethodHook 与易漏卸载的全局 Hook 集合。遇到“HookEnvironment”“HookSpec”“TargetSpec”“HookOwnerScope”“LibXposed101Backend”“LibXposed102Backend”“DexRefHookBridge”“热重载接管”“异常诊断批量投递”时触发。若仅做通用 JVM/Android 反射查找而不安装 Hook，转交 meguviz-ref；若只需离线扫描未加载 DEX，转交 dexmeguviz。
---

# MeguvizHook 接入与迁移

用声明式 `HookSpec`、按开销分层的回调形状、可核验的 `RegistrationReceipt` 与显式 `CloseReport`，替代裸 `XposedInterface.hook`、经典回调样板代码和易漏卸载的全局注册表。

## 第 1 阶段：盘点工程现状

改代码前先只读核对工程基线并输出简短评估：

1. **确认发布口径与源码边界**：
   - **Maven Central `1.2.0`（8 个制品，支持 `meguviz-bom:1.2.0`）**：`hook-api`、`hook-core`、`hook-dsl`、`hook-testing`、`hook-ref-bridge`、`hook-dex-bridge`（含 `DexRefHookBridge.queryAndBindBatch`）、`backend-libxposed-101`、`backend-libxposed-102`（含 `BinderTrace102.slicedReceiver` / `slicedReceiverForFrameSizes`）。
   - **仓库 `main` 源码树（7 个模块）**：包含除 `hook-dex-bridge` 外的其余 7 个模块；`hook-dex-bridge`、`BinderTrace102.slicedReceiver*`、`BatchTraceTransport.newSession` / `slicedReceiver`、`HookEnvironment.installRequired` 与 `ResolutionMode.RAW_IF_AVAILABLE` 位于 `1.2.0` 发布分支，尚未并回 `main`。
2. **核对工具链与框架版本**：检查 `gradle/libs.versions.toml` 与 `build.gradle.kts`，确认运行框架为 libxposed API `101.0.0` 还是 `102.0.0`，Android 模块编译要求 `compileSdk 37.2`、`minSdk 27`、`buildToolsVersion 37.0.0`、`Java 17`（`hook-api` / `hook-core` / `hook-dsl` / `hook-testing` 为 Java 8 字节码）。
3. **盘点现有拦截与生命周期**：检索 `XposedInterface.hook`、`XC_MethodHook`、`HookHandle.unhook()`、日志/IPC 上报及 API 102 `onHotReloading` / `onHotReloaded`。

盘点输出只需写明：选定后端代次、是否需要 Kotlin DSL / Ref 桥 / Dex 桥 / 诊断模块，以及宿主代次（`codeEpoch`）与关闭触发点；确认后再改代码。

## 第 2 阶段：选择模块与接入依赖

下表制品使用 groupId `io.github.jichuo1.meguviz.hook`，建议通过 `meguviz-bom:1.2.0` 统一版本：

| 场景 | 依赖坐标 | 关键边界 |
| --- | --- | --- |
| 核心安装与分发（必选） | `hook-core:1.2.0`（传递 `hook-api`） | 纯 JVM（Java 8 字节码） |
| API 101 或 102 后端（二选一） | `backend-libxposed-101:1.2.0` 或 `backend-libxposed-102:1.2.0` | **严禁混装两代**；必须配 `compileOnly("io.github.libxposed:api:101.0.0\|102.0.0")` |
| Kotlin DSL（可选） | `hook-dsl:1.2.0` | 提供 `forOwner`、`hook`、`method` 等写法 |
| 反射描述符定位桥（可选） | `hook-ref-bridge:1.2.0` | 依赖 `meguviz-ref-core:1.2.0`（Java 17） |
| DEX 扫描 + Ref 定位桥（可选） | `hook-dex-bridge:1.2.0` | **仅 Central `1.2.0` 可直接依赖**（`main` 源码未合入） |
| JVM 契约单测（可选） | `testImplementation("...:hook-testing:1.2.0")` | 提供 `FakeBackend`，仅验证 SDK 契约 |

```kotlin
dependencies {
    implementation(platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0"))
    implementation("io.github.jichuo1.meguviz.hook:hook-core")
    implementation("io.github.jichuo1.meguviz.hook:backend-libxposed-102")
    compileOnly("io.github.libxposed:api:102.0.0")
    // 按需追加：hook-dsl / hook-ref-bridge / hook-dex-bridge
}
```

- 严禁把 `io.github.libxposed:api` 声明为 `implementation` 打入 APK，也不得在同一构建中同时引入 101 与 102 后端。
- 仅持有已验证 `Method` / `Constructor` 时直接用 `HookSpec.forMember`，不引入 `hook-ref-bridge` 或 `hook-dex-bridge`。

## 第 3 阶段：执行最小迁移与生命周期对齐

### 最小 Java / Kotlin 接入骨架

```java
LibXposed102Backend backend = new LibXposed102Backend(
        xposedModule, new LibXposed102Context(processName, hostLoader, codeEpoch));
HookEnvironment environment = HookEnvironment.create(backend);

RegistrationReceipt receipt = environment.install(
        HookSpec.forMember("feature.title", "my-module", titleMethod)
                .replaceConstant("safe-title")
                .build());
if (receipt.isInstalled()) {
    HookRegistration reg = receipt.registration(); // 保存句柄，退出时调用 reg.close()
} else if (receipt.isSatisfied()) {
    // ALREADY_INSTALLED：不生成新句柄，继续持有此前注册的句柄
}
```

```kotlin
val hooks: HookOwnerScope = environment.forOwner("my-module")
val receipts = hooks.installAll {
    hook("feature.title", titleMethod) { replaceConstant("safe-title") }
    hook("feature.log", logMethod) { beforeArgs { inv -> log(inv.getArgument(0)) } }
}
```

### 回调形状性能梯度与铁律

1. **按从低开销到高开销依次选型**：
   `replaceConstant` → `replaceResultOnSuccess` → `replaceIfStringArgumentEquals` → `before` / `after`（`NO_ARGS`） → `beforeArgs` / `afterArgs`（`READ_ARGS_VIEW`） → `beforeMutableArgs`（`MUTABLE_ARGS`） → `around`（`AROUND`） → `access(ENTRY_SNAPSHOT)`。
2. **借用对象与异常策略边界**：`HookInvocation` 与参数视图仅在回调所在线程且回调返回前有效；`AroundInvocation.proceed()` 每次调用至多执行一次。默认 `ErrorPolicy.PROPAGATE` 保持原方法异常身份且绝不重放；`FRAMEWORK_PROTECTIVE` 需后端显式支持能力。
3. **幂等与强制换代**：重复安装要返回 `ALREADY_INSTALLED`，必须复用同一个回调实例与替换值引用；单目标需要强制重建代次时用 `forceReplace`（或 `replace`）。
4. **生命周期与关闭规则**：`HookEnvironment.close()` 与 `HookRegistration.close()` 幂等且不等待宿主回调返回；在锁外回调需避免等待环境锁时先调 `environment.requestClose()`，再由后台协调线程执行 `close()`。若安装或替换期间抛出非声明异常或返回空句柄，该 ID 会在当前环境被隔离，严禁盲目重装或卸载未知旧句柄。
5. **模块路由与后台异常投递**：多包入口按 `(包名, ClassLoader 对象身份)` 使用 `ModuleEntryRouter` 结合 `ModuleLifecycle` 管理租约与停机清理；`CallbackExceptionTracker` 配合 `TraceBatchPoller`、`BatchTraceTransport` 或 `BinderTrace102` 必须在调用方后台线程运行，严禁放在 Hook 回调或 UI 线程。
6. **必须满足的 Hook 核对**：消费 Maven Central `1.2.0` 时可用 `environment.installRequired(spec)`（DSL 为 `hooks.installRequired`），未满足时抛出携带原回执的 `HookInstallException`；基于 `main` 源码构建时直接检查 `receipt.isSatisfied()`。

> **按需深入阅读**：完整 8 制品坐标、`1.2.0` vs `main` API 差异与核心签名清单见 [API 与模块速查](references/api-and-modules.md)；经典回调迁移对照、API 102 热重载、`FakeBackend` 测试与排障见 [迁移对照与排障](references/migration-patterns.md)。

## 第 4 阶段：验证与交付清单

1. **消费方验证**：
   - 运行 `:app:dependencies --configuration releaseRuntimeClasspath`，确认无 `io.github.libxposed:api` 打包泄漏且不存在 `101` / `102` 后端共存。
   - 使用 `FakeBackend` 编写 JVM 单测覆盖回调替换、幂等安装、`close()` 后透传及异常回退。
   - 打 R8 Release 包并检查 `consumer-rules.pro` 生效情况（如 `CoreAfterBridge`、`CoreChainBridge` 与 Ref 桥规则）。
2. **本库源码自检命令**（在仓库根目录执行，Windows 用 `gradlew.bat`）：
   - JVM 测试、样例与后端单测：
     `./gradlew :hook-core:test :hook-ref-bridge:test :hook-dsl:test :hook-testing:test :sample-java-contracts:run :sample-kotlin-contracts:run :backend-libxposed-101:testDebugUnitTest :backend-libxposed-102:testDebugUnitTest --no-daemon`
   - 公开 API 基线与全量 Android lint：
     `./gradlew apiCheck lint --no-daemon`
3. **失败与回退核对**：检查 `RegistrationReceipt.Status`（`PARTIALLY_INSTALLED`、`CONFLICT`、`STALE_IDENTITY`、`UNSUPPORTED_CAPABILITY`）及 `CloseReport.entryRestoration()`，确认部分成功项已释放且不重试未知结果句柄。
4. **交付摘要**：写明选用的 `backend` 版本、依赖坐标口径（Central `1.2.0` 还是 `main` 源码）、回调形状选择、`codeEpoch` 推进策略与实测命令结果。
