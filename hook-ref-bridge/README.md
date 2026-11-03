# MeguvizRef 可选目标定位桥

`hook-ref-bridge` 将 MeguvizRef 的成员定位结果转换为 MeguvizHook 的 `MemberResolver`。
它是独立 Java 17 模块，显式依赖 `meguviz-ref-core:1.0.0`；没有修改 `hook-core` 或其他
后端的默认依赖。桥接只在安装时解析，不参与每次 Hook 回调，也不保证性能提升。

## 源码接入和最小示例

源码构建配置已纳入 `hook-ref-bridge`；已发布的六组件 1.0.0 不含此桥，不能假定桥的
Maven 坐标已发布。
在包含本仓库各模块的 Gradle 构建中，仓库
`settings.gradle.kts` 已声明此模块，消费者可按需添加：

```kotlin
dependencies {
    implementation(project(":hook-ref-bridge"))
}
```

该模块的 `api` 依赖会带入同一构建中的 `hook-core` 和 `meguviz-ref-core:1.0.0`；
消费者仍须按实际框架选择后端和对应的 `compileOnly` 框架 API。桥以 Java 17 编译，
接入项目需核对工具链和 Android 打包兼容性；本机消费者构建不代表真实设备兼容。

下面的类展示完整 API 调用；`backend` 由模块根据实际框架创建，`targetClassName` 应
替换为经过宿主版本和 ClassLoader 验证的真实类名。

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

上面的二参数 `HookEnvironment.create(backend, resolver)` 使用默认的 `DiagnosticsSink.NONE`，
属于当前开发源码；既有 1.0.0 中应使用三参数形式，并显式传入该 sink。需要自己的诊断处理时，
仍使用三参数入口。会话作为 resolver 时同样适用，但 environment 不会代替调用方关闭会话。

调用方应检查 `receipt.status()`、`message()` 和需要时的 `targets()`。`isInstalled()`
只对本次状态为 `INSTALLED` 返回 true；重复安装相同配置可能返回 `ALREADY_INSTALLED`，
此时不会获得新的 `registration()`，调用方应继续管理此前持有的 handle。当前开发源码
提供 `isSatisfied()`，对这两个状态均返回 true，表示检查时配置已满足，但不是实时存活保证。
已发布的 1.0.0 没有 `isSatisfied()`；使用 1.0.0 时应直接判断上述两个状态。对于部分安装，
`registration()` 仍可能非空，必须由调用方管理。结束单个 Hook 时检查所持 handle 的
`close()` 报告；模块生命周期结束时关闭 environment。
示例的可编译构造与 fake backend 调用有 JVM 合同测试，不代表真实框架安装已验证。

### 显式查看解析失败

当前开发源码允许在普通安装时按需保留本次成员解析器抛出的原始受检异常；默认
`environment.install(spec)` 的回执和行为不变：

```java
HookEnvironment.InstallResolutionResult result = environment.installWithResolutionDiagnostics(spec);
RegistrationReceipt receipt = result.receipt();
BackendInstallException failure = result.resolutionFailure();

// 安装是否满足、是否需要管理 registration，仍以 receipt 为准。
// 如 failure 非 null，调用方可在自己的受控诊断位置检查其原因和 suppressed 异常。
```

上例还需导入 `io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException`。该入口只捕获
本次 `MemberResolver.resolve` 抛出的 `BackendInstallException`；如果解析期间 loader 或 epoch
变化，回执可能为 `STALE_IDENTITY`，同时仍保留变化前的解析异常。`resolutionFailure()` 为
`null` 不代表安装成功：它也可能在没有调用解析器、其他阶段失败或安装返回其他状态时出现。
unchecked 异常及 fatal 事件仍按普通安装合同传播。`TargetSpec.ofMember(...)` 绕过解析器，
因此不会通过此字段产生解析异常。

当 Ref 查询本身返回不完整结果时，异常是 `MeguvizRefResolutionException`，可从
`receipt()` 查看 Ref 的状态、原因、可见范围、已检查数量和可用的失败类型。例如：

```java
if (failure instanceof MeguvizRefResolutionException) {
    ResolutionReceipt refReceipt = ((MeguvizRefResolutionException) failure).receipt();
    if (refReceipt != null) {
        // 在受控诊断位置按需检查 refReceipt.status()、reason()、causeType() 和 coverage()。
    }
}
```

上例使用 `io.github.jichuo1.meguviz.hook.ref.MeguvizRefResolutionException` 和
`io.github.jichuo1.meguviz.ref.api.evidence.ResolutionReceipt`。普通安装的状态和消息不变；
直接调用 resolver 或预解析时，原有的 `BackendInstallException` 捕获仍有效，但异常的具体
类型及其 `toString()` 会变化，不应依赖具体类相等。若序列化整个异常链，其他 cause 或
suppressed 对象仍可能不可序列化，接收端也需要此异常类。
自定义查询工厂、scope 或其他阶段失败不一定产生此子类。Ref 1.0 对 provider 的受检异常只
记录类型字符串，不保留原始异常或堆栈；`causeType()` 也可能为 null。回执包含查询及成员
描述信息，应按敏感诊断数据管理。Ref 回执不能序列化，异常序列化不会包含它，反序列化后
`receipt()` 返回 null。这个异常类型及其回执访问同样属于当前开发源码，尚不在既有发布包中。

诊断结果可能持有完整异常链、suppressed 异常、敏感信息和 ClassLoader；调用方应控制保留期，
检查后释放结果及异常的引用，不要假定对象会立即回收。本入口不隐式记录或上传异常。它只提供
普通安装的解析诊断，不执行 replace 或 adopt；相同注册已存在也不会因此换代，替换及接手仍须
使用各自的显式入口。该方法属于当前开发源码，不能假定已包含在既有发布制品中。

默认只查目标类的声明成员，按描述符中的名称、参数及返回类型筛选方法或构造器，排除
bridge/synthetic 方法，并稳定排序；现有 FIRST、UNIQUE、ALL 判定由 Hook 核心负责。
`TargetSpec.ofMember(...)` 已经给出成员，会绕过此解析器。

### 已有 Method 时直接安装

如果调用方的宿主适配器已经解析并验证了具体 `Method`，可以直接传入该对象，省去安装时
再经 Ref 桥枚举同一目标。下面的方法沿用上面创建的 `environment`；`resolved` 由调用方
提供，不能为 `null`：

```java
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import java.lang.reflect.Method;

public final class ResolvedHookExample {
    public static RegistrationReceipt installResolved(HookEnvironment environment, Method resolved) {
        HookSpec spec = HookSpec.forMember("feature.title", "my-module", resolved)
                .replaceConstant("replacement")
                .build();
        return environment.install(spec);
    }
}
```

例如 BIL 的 `HookPointRegistry.resolveExact` 已返回具体 `Method`，调用方可以在其返回非空
且完成宿主版本、加载器、代码代次和可访问性检查后采用此方式。SDK 会保留传入的成员身份；
environment 对 backend 加载器和代次的检查不能独立证明该 `Method` 属于当前宿主代码。
此方式也不重新执行描述符筛选、候选去重或排序：如果原查找是“第一个匹配项”，必须由
调用方确认原有选择顺序符合预期，不能假设与桥的签名排序一致。

当前开发源码在导出成员前，先用 Ref 已持有的 JVM 描述符做一次保守预筛：只排除基本类型不同、
对象与基本类型错配、数组维数不同等与类元数据无关的确定不匹配；对象类型名仍在导出后按
`getName()`／`getCanonicalName()` 精确比较，预筛不会排除精确比较可能接受的候选。被排除的重载
不再导出，因此在 `TRY_SUPPRESS_JAVA_CHECKS` 下，某个无关重载拒绝设置可访问性时不再导致整次
解析失败。多个候选的稳定排序改为每个候选只计算一次排序键，顺序不变。自定义查询
（`withQueries`）不做预筛。

需要继承成员时，可显式调用 `MeguvizRefMemberResolver.withHierarchy(Hierarchy.SUPERCLASSES)`
或 `Hierarchy.JAVA_PUBLIC`。**被 Hook 的是成员实际声明所在的类**，可能影响其他子类；
构造器始终只在目标类中查找。桥接对候选数量、方法种类和 loader 有限制，且每次解析
使用并关闭独立 `CodeScope`，避免缓存跨代码代次或误持旧 loader。

### 自定义 scope 并保留描述符筛选

需要调整 Ref scope 的缓存容量或访问策略，但仍沿用 Hook 描述符的名称、参数和返回类型
筛选时，可只提供 scope 工厂，不必重写查询条件：

```java
MeguvizRefMemberResolver.ScopeFactory scopes = loader -> CodeScope.open(
        loader, AccessPolicy.PRESERVE_JAVA_ACCESS, 256);
MemberResolver resolver = MeguvizRefMemberResolver.withScope(scopes, Hierarchy.DECLARED);

// 若同一 backend 代次需要显式共享 scope，可改用会话：
try (MeguvizRefResolverSession session = MeguvizRefResolverSession.withScope(
        backend.context(), scopes, Hierarchy.DECLARED)) {
    // 将 session 用作 MemberResolver；按实际安装回执管理 Hook。
}
```

示例需导入 `CodeScope`、`AccessPolicy`、`Hierarchy`、`MemberResolver`、
`MeguvizRefMemberResolver` 和 `MeguvizRefResolverSession`。容量只是示例，不代表最佳值。
普通 resolver 每次解析都要求工厂创建并转交新的 scope，解析结束后由桥关闭；会话则接收
一个新的 scope 并在自身生命周期结束时关闭，仍要求可靠的 loader、单调 code epoch 和
原有身份检查。不得从工厂返回已使用的 scope 或继续在外部操作转交后的 scope；调用方若
注册 provider，仍须遵循 Ref 的所有权和身份合同。`withScope` 的默认查询不指定 provider，
不会自动调用 scope 中注册的 provider；需要 provider 查询时使用 `withQueries`。`withScope`
保留描述符筛选，`withQueries` 则有意使用自定义查询替代这些筛选，两者不能互换。

若调用方确实需要尝试取消 Java 反射访问检查，可在新 scope 工厂中显式选择：

```java
MeguvizRefMemberResolver.ScopeFactory scopes = loader -> CodeScope.open(
        loader, AccessPolicy.TRY_SUPPRESS_JAVA_CHECKS, 256);
MemberResolver resolver = MeguvizRefMemberResolver.withScope(scopes, Hierarchy.DECLARED);
```

该策略会由 Ref 对导出的反射成员尝试设置可访问性；它不保证运行时、模块边界或平台策略
允许这一操作，失败仍会传回解析或安装回执。`PRESERVE_JAVA_ACCESS` 则保留 Java 访问检查。
调用方仍须核实目标和 loader，不能由“查到成员”推断真实框架已成功安装 Hook。

## 显式结构查询与扩展

`withQueries(QueryFactory)` 使用默认 scope 并允许通过 MeguvizRef 的 `QuerySpec` 定义结构
条件和层级。需要注册 provider 或配置 scope 时，可改用
`withQueries(ScopeFactory, QueryFactory)`；scope 工厂每次必须创建与传入 loader 相同的
新 scope，桥接拥有并在解析结束后关闭它。若注册 MeguvizRef provider，调用方需要为每次
scope 提供新的 registry 和该库要求的强代码身份，不能重复转交已占用的 registry。

```java
MemberResolver resolver = MeguvizRefMemberResolver.withQueries(
    (target, owner) -> QuerySpec.builder(owner, MemberKind.METHOD)
        .name("resolvedMethod")
        .selection(Selection.ALL)
        .resultLimit(128)
        .build()
);
```

自定义查询显式替代描述符的名称和参数筛选，但仍必须绑定描述符指定的运行时类与成员
种类，使用 `Selection.ALL`，且结果数限制不超过 4096。桥接拒绝部分、取消、过期或
预算耗尽的结果，不把它们当作“未找到”或“唯一匹配”。完整性只针对 MeguvizRef 报告的
可见范围，不代表全 DEX 穷尽或真实宿主版本已验证；如需跨类 DEX 查询，应在外部定位并
验证实际目标，再将具体成员传入 `TargetSpec.ofMember(...)`。

使用已发布的 Ref 1.0.0 时，若目标类由父加载器定义并继承父加载器中的声明类，而 scope
所用的子加载器又以 child-first 方式加载了同名声明类，自定义 provider 的继承查询可能因
候选不在真实类型层级中而返回不完整结果。桥会拒绝该结果，不把它当作未找到或安装子加载器
的同名成员。受控 JVM 夹具中，默认 `withHierarchy(Hierarchy.SUPERCLASSES)` 结构查询可以
选中父加载器的真实方法；是否适用于实际宿主仍须独立验证。也可在外部确认成员身份后使用
`TargetSpec.ofMember(...)`；两种方式都不会自动替代有不同语义的 provider 查询。
包含相关修复的未发布 Ref 测试制品在同一 JVM 夹具中已让 provider 正确选中父方法；
这不是已发布版本或 Android 运行时证据，当前桥的正式 Ref 依赖仍为 1.0.0。

`DescriptorTarget.parameters(...)` 和 `returning(...)` 比较的是类型**名称**。即使调用方从
`Class.getName()` 得到字符串，不同加载器中同名的两个 `Class` 也无法由该字符串区分。
确实需要限定运行时类型身份时，可传入经调用方验证的 `Class` 构造结构查询：

```java
// parameterClass 来自已核实的目标加载器；本例方法的返回类型与参数类型相同。
MemberResolver resolver = MeguvizRefMemberResolver.withQueries((target, owner) ->
        QuerySpec.builder(owner, MemberKind.METHOD).name("inherited")
                .parameters(parameterClass).returns(parameterClass)
                .hierarchy(Hierarchy.SUPERCLASSES).selection(Selection.ALL)
                .resultLimit(16).build());
```

这里的自定义查询替代描述符的名称和参数筛选，因此应显式写出所需名称、参数、返回类型和
层级；若已有经验证的 `Method`，直接用 `TargetSpec.ofMember(...)` 更简洁。获取到的 `Class`
仍须与实际宿主和代码代次相符，不能只根据类名判断其身份。

原始 `Method` / `Constructor` 会按 Hook 核心现有合同离开 `CodeScope`，并可保持声明类
及 loader 可达；关闭注册不能撤销使用方保存的反射对象。自定义 provider 与目标 APK、
ClassLoader 和代码代次的绑定仍需由调用方确认。真实 Android / ART 行为需另行验证。

## 显式批次会话

当一次模块装载需要多次解析相同目标时，可显式创建 `MeguvizRefResolverSession` 复用
一个由会话拥有的 MeguvizRef `CodeScope`；默认 `MeguvizRefMemberResolver` 仍在每次
解析时创建并关闭独立 scope。会话只在安装期工作，不参与 Hook 回调，也不承诺加速。
完整结果只有一个候选时，桥会直接校验并收集该成员；多个候选仍去重、排序。两条路径
都执行成员导出及范围校验；默认描述符路径还会筛选描述符。返回的非空结果列表由调用方
独立持有。

```java
MeguvizRefResolverSession session = MeguvizRefResolverSession.open(backend.context());
HookEnvironment environment = HookEnvironment.create(backend, session);
// 在该 backend 同一 loader 和 codeEpoch 内安装一组 Hook，检查每个安装回执。

MeguvizRefResolverSession.CloseReport report = session.requestClose();
if (report.state() == MeguvizRefResolverSession.State.DRAINING) {
    // 已拒绝新解析；调用方稍后检查 report()，不可认为在途工作已经结束。
}
if (report.state() == MeguvizRefResolverSession.State.FAILED) {
    // report.failure() 暴露资源/provider 清理失败；不盲目重试。
}
```

调用方提供的 `BackendContext` 必须以线程安全方式报告真实 loader，且 code epoch 必须
可靠、单调变化并避免 ABA；会话不能独立认证这些事实。每次解析在加载类、调用查询工厂
或 provider 前先申请租约，并在外部步骤间检查状态。观察到 loader、epoch、scope 身份
变化，或查询失败、发生同线程递归时，默认会话永久退役，不返回部分结果或自动重试。
`HookEnvironment` 仍需在解析返回后自行核验安装前的 loader/epoch；会话不使解析返回和
安装之间的时隙变为原子操作。

JVM 调用方（例如 Kotlin 实现）也可能从身份读取或查询回调抛出 Java 签名未声明的受检异常。
会话仍会退役并释放已领取的租约；这类异常按原对象传播，不会自动转换成 `BackendInstallException`。
调用方应按自己的中断约定处理中断状态。若异常发生在 scope 工厂返回之前，工厂仍负责清理
尚未交给桥的资源；桥只负责已经取得的 scope。

需要由调用方并发准备多个描述符并按顺序提交时，可结合
[显式并行预解析](../docs/prepared-install.md) 使用；它不保证所有查询分布都能加速。

`requestClose()` 不等待已准入查询：它拒绝新租约，由最后一个退出的查询关闭 scope；
没有在途查询时，调用线程会同步清理 scope，provider 清理可能阻塞。已经获得租约的线程
若被暂停，实际外部操作可能在 close 返回后才开始。解析结果在会话锁内确认开放状态并
释放租约的那一刻生效；若关闭先取得该锁并退役，解析不会返回结果。若解析先完成该步骤，
关闭可以在解析方法返回或安装之前发生，安装方仍须自行核验。调用方必须检查 `report()`
直至 `CLOSED` 或 `FAILED`，并分别管理旧
Hook 的关闭或跨代接手。成功关闭仅表示 scope 清理完成，不证明反射对象、Hook、宿主线程
或 ClassLoader 已物理释放。成功清理后会话释放其持有的 context、loader、scope 和工厂
引用；清理失败时会保留相关引用和失败信息供审计，调用方不应假定这些对象已回收。
使用该会话创建的 environment 仍持有同一个 resolver；会话关闭后，不应再通过这个
environment 发起依赖解析的新安装。会话关闭不会自动关闭已有 Hook，二者需分别管理。

`withHierarchy` 显式允许继承范围；`withQueries(context, queryFactory)` 使用默认 scope，
也可通过 `withQueries(context, scopeFactory, queryFactory)` 为会话提供**新的** scope、
provider 快照和查询工厂，所有权一次性交给会话。不得在外部持有后继续关闭或推进该 scope；
若检测到这类状态变化，会话会失败并退役。该缓存只复用 MeguvizRef 自己确认完整的可见
范围结果，不会扩大为全 DEX 覆盖，也不会缓存“未找到”作为未来代码代次的结论。

### 显式复用同一个查询对象

当调用方会在同一会话中反复解析**同一个 `DescriptorTarget` 对象**，且已核实目标类、
loader 与可靠的单调 code epoch 时，可以显式复用不可变的 `QuerySpec`。例如，以下假设
`verifiedOwnerClass` 是已核实包含 `read(String): String` 的目标类，`verifiedLoader` 与
`backend.context()` 报告的 loader 一致：

```java
Class<?> ownerClass = verifiedOwnerClass;
TargetSpec.DescriptorTarget target = TargetSpec.descriptor(
        TargetKind.METHOD, ownerClass.getName(), "read")
        .parameters("java.lang.String").returning("java.lang.String").build();
QuerySpec query = QuerySpec.builder(ownerClass, MemberKind.METHOD).name("read")
        .parameters(String.class).returns(String.class)
        .selection(Selection.ALL).resultLimit(128).build();

try (MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(
        backend.context(), (requested, owner) -> {
            if (requested != target || owner != ownerClass) {
                throw new BackendInstallException("unexpected target or owner");
            }
            return query;
        })) {
    List<Executable> first = session.resolve(target, verifiedLoader);
    List<Executable> second = session.resolve(target, verifiedLoader);
    // 调用方检查每次结果，并负责管理取出的反射对象。
}
```

示例还需导入 `java.lang.reflect.Executable`、`java.util.List`、
`io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException`、
`io.github.jichuo1.meguviz.hook.ref.MeguvizRefResolverSession`、
`io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind`、
`io.github.jichuo1.meguviz.ref.api.query.QuerySpec` 和 `Selection`。

每次解析仍会执行查询工厂和上面的身份检查，桥不会隐式缓存或跳过工厂，也不会因此
新增负缓存。请求的目标对象或 owner 身份不符时，上例主动抛错，默认会话按原有失败合同
退役；不能仅凭类名相同就复用。目标 loader 或代码代次改变后，应重新验证身份、创建会话
和查询对象，不要跨代重用。

`query` 和工厂闭包会强引用 `Class`，返回的反射对象也可能保留类及 loader。调用方须协调
在途解析、关闭会话，并释放自己持有的目标、查询、闭包、结果及类引用；关闭不保证立即卸载。
是否值得复用应根据实际查询、会话与对象保留成本测量；它不改变使用已解析成员的路径。
性能设计依据与验证边界见[性能研究记录](../docs/performance-research.md)。

### 可选的等待者中断隔离

默认 `RETIRE_SESSION` 策略保持原有失败行为。需要在共享查询中隔离**已验证的等待者中断**时，
可显式选择另一策略：

```java
MeguvizRefResolverSession session = MeguvizRefResolverSession.open(
        backend.context(),
        MeguvizRefResolverSession.WaiterInterruptionPolicy.ISOLATE_VERIFIED_WAITER);
boolean evidenceApiDetected = session.isWaiterInterruptionEvidenceAvailable();
```

`withHierarchy`、`withQueriesAndWaiterPolicy` 和带 scope 工厂的四参数 `withQueries` 也可
选择策略。桥当前正式依赖的 Ref 1.0.0 不包含中断来源能力；在该版本上显式选择隔离也会
回退为默认的失败退役。支持来源标记的 Ref 仍是未发布开发制品，只有在调用方自行使用匹配且
可信的版本后才可能启用此路径，不能把本地开发结果当成已发布版本能力。

能力查询只表示创建会话时反射检测到预期的 API 形状；默认策略不探测并返回 false，结果
不能认证 Ref fork，也不保证后续调用或实际来源一定可信。桥仅识别本次 `scope.resolve`
直接返回且带有可信等待者标记的取消结果，不根据公开 receipt 或用户抛出的异常推断来源；
未知来源、探测失败、所有其他查询错误仍使会话退役。等待者仍收到失败并保留线程中断位，
不会因此取消执行者；loader、epoch、关闭和 provider 所有权检查继续生效。返回的异常不是
安装成功，调用方还须检查各自结果及会话关闭报告。

在容量已满且 provider 执行期间发生中断的受控 JVM 场景中，已发布 Ref 1.0 报告
`PARTIAL / binding_capacity`，未发布开发候选报告 `CANCELLED / interrupted`；桥对两者均
保守退役并保留中断位。不要仅凭状态或原因字符串将执行者识别为可隔离的等待者。这不是
对所有 provider 或 Android 运行时行为的证明，候选制品也尚未发布。

桥 JAR 包含相应的 R8 保留规则；如果调用方替换、裁剪或使用不受信的 Ref，必须自行检查最终
构建和版本。该路径的隔离 JVM 与受控混淆构建证据不代表 Android ART、真实框架或设备表现。

### 如何选择解析方式

- **各次查询需要独立取消**：共享会话中的一次解析失败，包括中断或超时，会使会话退役，
  其他在途查询也可能因此失败。可选择普通 `MeguvizRefMemberResolver`：每次解析分别创建并
  关闭 scope；并发时仍须确认查询工厂、scope 工厂和 provider 支持并发，并为各个 scope 提供
  独立、可安全关闭的 provider 资源。独立 scope 不共享会话缓存，也不能撤销已经发生的类加载
  或 provider 副作用；其成本和吞吐取决于实际负载，安装仍须检查 loader、代码代次和回执。
  Ref 1.0 的 `wait_interrupted` 原因本身不能证明当前线程只是等待者：provider 的未声明中断
  也可能得到相同回执。因此不能仅凭该原因推断没有执行 provider 或没有副作用。
- **重复查询同一已存在成员，且 loader 和 epoch 可可靠管理**：可以显式选择会话，
  让 MeguvizRef 在同一 scope 内复用已确认完整的正向结果。会话创建和关闭也有成本，
  只有一次查找时不应预先假定它更快。
- **存在大量并发查询时**：不要预设共享会话更快；在合同允许时将每次独立 scope 作为
  对照，并以实际的命中、未命中与目标分布测量。
  会话复用、独立 scope 和调用方线程协调各有成本，应以实际负载和目标运行时测量。
- 自定义 `ScopeFactory`／provider 可能拥有状态或副作用。不要为了分流自动复制、并发
  重放它们，也不能把某次“未找到”当成以后查找的结果；选择方式时须继续核对覆盖范围、
  错误、loader 和代码代次。

## 与 MeguvizRef 1.1 开发版

桥的正式依赖仍是已发布的 Ref `1.0.0`。在本地把依赖替换为 MeguvizRef `1.1.0-SNAPSHOT`
开发制品后，桥的测试除两项外全部通过；这两项（`RefWaiterPolicyOldCompatibilityTest`）专门断言
1.0.0 **没有**等待者中断来源 API，在新版本上失败是预期结果。这只是 JVM 测试证据，不是已发布版本
或 Android 运行时证据。

Ref 1.1 开发版降低了全新作用域的构造和结构查询成本，桥的默认解析器无需改代码即可受益。它还新增
单锁的 `CodeScope.isCurrent(...)` 和不创建 binding 的 `resolveRaw(...)`：前者可把会话每次解析中
多次分开的身份检查合并为一次加锁，后者可避免长会话累计导出 4096 个成员后触发 `binding_capacity`。
桥会在 Ref 1.1 正式发布并升级依赖后再采用这两个入口。
