# MeguvizRef 可选目标定位桥

`hook-ref-bridge` 将 MeguvizRef 的成员定位能力接入 MeguvizHook 的 `MemberResolver` 接口。它是独立的 Java 17 模块，不修改 `hook-core` 或后端的默认依赖；解析只在安装期执行，不参与每次 Hook 回调，也不直接承诺性能提升。

> **版本与适用范围说明**：Maven Central 已发布 `io.github.jichuo1.meguviz.hook:hook-ref-bridge:1.2.0`（传递依赖 `meguviz-ref-core:1.2.0`，支持 `BatchMemberResolver` 批共享、`ResolutionMode.RAW_IF_AVAILABLE` 与 `CodeScope.isCurrent`）。仓库 `main` 源码树中该模块仍兼容 `meguviz-ref-core:1.0.0` 基线（`1.2.0` 增量已随发布分支上线 Central）。相关测试仅覆盖 JVM 契约与受控夹具，不代表真实框架安装或 ART 运行时行为。

## 依赖接入与最小示例

在 Maven Central 消费 `1.2.0`（或通过 `meguviz-bom:1.2.0`）：

```kotlin
dependencies {
    implementation(platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0"))
    implementation("io.github.jichuo1.meguviz.hook:hook-ref-bridge")
}
```

若在包含本仓库全部模块的 Gradle 源码构建中使用，可声明 `implementation(project(":hook-ref-bridge"))`。接入手册仍需按实际运行框架选择一代后端与对应的 `compileOnly` 框架 API。

下面示例展示完整接入方式；`backend` 由模块创建，`targetClassName` 应替换为已确认宿主版本与 `ClassLoader` 的真实类名：

```java
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.ref.MeguvizRefMemberResolver;

public final class RefHookExample {
    public static HookEnvironment environment(HookBackend backend) {
        return HookEnvironment.create(backend, new MeguvizRefMemberResolver());
    }

    public static RegistrationReceipt install(HookEnvironment environment, String targetClassName) {
        TargetSpec target = TargetSpec.descriptor(TargetKind.METHOD, targetClassName, "title")
                .parameters("java.lang.String")
                .returning("java.lang.String")
                .match(MatchPolicy.UNIQUE)
                .build();
        HookSpec spec = HookSpec.builder("feature.title", "my-module", target)
                .replaceConstant("replacement")
                .build();
        return environment.install(spec);
    }
}
```

检查安装回执时：`isInstalled()` 仅对 `INSTALLED` 返回 `true`；`isSatisfied()` 在 `INSTALLED` 与 `ALREADY_INSTALLED` 均返回 `true`（`ALREADY_INSTALLED` 不返还新句柄，请继续管理此前持有的句柄）。部分安装（`PARTIALLY_INSTALLED`）也可能携带需要关闭的 `registration()`。会话作为解析器传入 `HookEnvironment.create(backend, session)` 时，environment 不会替调用方关闭该会话。

### 显式查看解析失败

普通 `environment.install(spec)` 不暴露底层受检异常；需要诊断定位失败原因时，可使用 `installWithResolutionDiagnostics(spec)` 保留本次 `MemberResolver.resolve` 抛出的 `BackendInstallException`：

```java
HookEnvironment.InstallResolutionResult result = environment.installWithResolutionDiagnostics(spec);
RegistrationReceipt receipt = result.receipt();
BackendInstallException failure = result.resolutionFailure();

// 是否安装成功及是否需要管理 registration 仍以 receipt 为准。
// 若 failure 非 null，可在受控诊断逻辑中检查其原因与 suppressed 异常。
```

若 Ref 查询本身返回不完整状态，异常类型为 `MeguvizRefResolutionException`，可进一步读取结构化回执：

```java
if (failure instanceof MeguvizRefResolutionException) {
    ResolutionReceipt refReceipt = ((MeguvizRefResolutionException) failure).receipt();
    if (refReceipt != null) {
        // 按需检查 refReceipt.status()、reason()、causeType() 和 coverage()。
    }
}
```

- 上例需导入 `BackendInstallException`、`MeguvizRefResolutionException` 与 `ResolutionReceipt`。
- 诊断结果与异常可能持有 `Throwable` 对象图与 `ClassLoader`，检查后请及时释放引用。`ResolutionReceipt` 不支持 Java 序列化，异常反序列化后 `receipt()` 返回 `null`。
- 默认路径只查询目标类自身声明成员，按名称、参数及返回类型筛选方法或构造器，自动排除 `bridge` / `synthetic` 方法并稳定排序；`FIRST` / `UNIQUE` / `ALL` 判定仍由 Hook 核心负责。在 `TRY_SUPPRESS_JAVA_CHECKS` 策略下，导出前会先按 Ref 描述符预筛参数类型与返回类型，避免无关重载设置可访问性失败导致整次解析失败。

### 已有 `Method` 时直接安装

如果宿主适配层已验证并持有具体 `Method`，请直接使用 `HookSpec.forMember(...)`，无需经 Ref 桥重新枚举：

```java
HookSpec spec = HookSpec.forMember("feature.title", "my-module", resolved)
        .replaceConstant("replacement")
        .build();
RegistrationReceipt receipt = environment.install(spec);
```

需要查找继承方法时，可使用 `MeguvizRefMemberResolver.withHierarchy(Hierarchy.SUPERCLASSES)` 或 `Hierarchy.JAVA_PUBLIC`（注意：继承方法会 Hook 在其实际声明的父类上，可能影响其他子类；构造器始终仅查找声明类）。

### 自定义 scope 与访问策略

需要调整作用域容量或显式启用 `AccessPolicy.TRY_SUPPRESS_JAVA_CHECKS` 时，可通过 `ScopeFactory` 传入：

```java
MeguvizRefMemberResolver.ScopeFactory scopes = loader -> CodeScope.open(
        loader, AccessPolicy.TRY_SUPPRESS_JAVA_CHECKS, 256);
MemberResolver resolver = MeguvizRefMemberResolver.withScope(scopes, Hierarchy.DECLARED);

// 在同一加载器与代码代次内复用同一作用域时，改用会话：
try (MeguvizRefResolverSession session = MeguvizRefResolverSession.withScope(
        backend.context(), scopes, Hierarchy.DECLARED)) {
    // 将 session 传给 HookEnvironment.create(backend, session)
}
```

`TRY_SUPPRESS_JAVA_CHECKS` 仅调用公开 `setAccessible(true)`，不保证绕过 Java 模块边界或 Android hidden API；且不得将已使用过的旧作用域交给桥或会话，也不要在外部修改已移交的作用域。

## 显式结构查询与扩展

需要借助 `QuerySpec` 自定义过滤条件或注册 Provider 时，使用 `withQueries(QueryFactory)` 或 `withQueries(ScopeFactory, QueryFactory)`：

```java
MemberResolver resolver = MeguvizRefMemberResolver.withQueries(
    (target, owner) -> QuerySpec.builder(owner, MemberKind.METHOD)
        .name("resolvedMethod")
        .selection(Selection.ALL)
        .resultLimit(128)
        .build()
);
```

- 自定义查询仍须绑定描述符指定的运行时 `owner` 类与成员种类，使用 `Selection.ALL` 且 `resultLimit <= 4096`；不完整、被取消、过期或超出预算的结果一律失败拒绝，不伪装成“未找到”或“唯一匹配”。
- 需要按运行时 `Class` 对象身份精确限定参数或返回类型时，同样在 `withQueries` 中直接传入已核实的 `Class<?>` 构造 `QuerySpec`（描述符字符串本身无法区分不同加载器中的同名类）。
- 导出的 `Method` / `Constructor` 脱离 `CodeScope` 管理，关闭注册或作用域不会撤销调用方已保存的反射对象。

## 显式批次会话

同一加载器与单调 `codeEpoch` 下需频繁解析时，可创建 `MeguvizRefResolverSession` 复用一个专属 `CodeScope`：

```java
MeguvizRefResolverSession session = MeguvizRefResolverSession.open(backend.context());
HookEnvironment environment = HookEnvironment.create(backend, session);

// 生命周期结束时发起关闭并检查报告
MeguvizRefResolverSession.CloseReport report = session.requestClose();
if (report.state() == MeguvizRefResolverSession.State.DRAINING) {
    // 已拒绝新解析；稍后通过 session.report() 检查清理结果
}
```

- Central `1.2.0` 新增 `ResolutionMode.RAW_IF_AVAILABLE`（搭配 Ref `1.2.0` 的 `resolveRaw` 避免长会话触及 4096 `binding_capacity` 上限，但不缓存查询结果），默认 `ResolutionMode.CACHED` 保留正向缓存复用。
- 若发现 `ClassLoader` 变更、`codeEpoch` 推进、作用域外部变更、查询失败或同线程递归，默认会话将永久退役；`requestClose()` 不阻塞等待已在途的查询，而是在所有已进入查询退出后关闭内部作用域（无在途查询时同步清理，Provider 关闭仍可能阻塞）。
- 成功关闭仅代表会话内部清理完毕，不证明反射对象、Hook、宿主线程或 `ClassLoader` 已从内存中卸载。会话关闭不会自动关闭已安装的 Hook，二者需分别管理。

### 可选的等待者中断隔离

默认 `WaiterInterruptionPolicy.RETIRE_SESSION` 遇到任何解析取消均退役会话；若希望在单飞查询中隔离**已确认的等待者线程中断**（基于 Ref `1.2.0` 的 `ResolutionReceipt.interruptionOrigin() == SINGLE_FLIGHT_WAITER`），可显式指定 `ISOLATE_VERIFIED_WAITER`：

```java
MeguvizRefResolverSession session = MeguvizRefResolverSession.open(
        backend.context(),
        MeguvizRefResolverSession.WaiterInterruptionPolicy.ISOLATE_VERIFIED_WAITER);
boolean evidenceApiDetected = session.isWaiterInterruptionEvidenceAvailable();
```

桥 JAR 已随附对应 ProGuard / R8 保留规则。若底层运行时缺少该能力（如 Ref `1.0.0`）或来源为 `UNKNOWN`，会话仍保守退役并保留线程中断标志。

### 如何选择解析方式

- **各次查询需独立取消互不影响**：使用无状态的 `MeguvizRefMemberResolver`（配合 `installAll` 在同一调用内共享批表）。
- **同一加载器与稳定代次下高频复用正向解析结果**：显式使用 `MeguvizRefResolverSession`（目标极多、担心触及 4096 绑定上限时可测量对比 Central `1.2.0` 的 `ResolutionMode.RAW_IF_AVAILABLE`）。
- **大量并发查询**：不要预设共享会话一定更快，应结合实际命中率、未命中率及 [并行预解析](../docs/prepared-install.md) 在目标环境实测（设计细节见[性能研究记录](../docs/performance-research.md)）。
