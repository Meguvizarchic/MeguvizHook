# Kotlin Hook DSL

`hook-dsl` 是基于 `HookSpec` 与 `HookEnvironment` 的 Kotlin 声明式封装。它不代为创建 backend、选择框架、吞掉安装失败或接管 `HookRegistration` 的关闭生命周期。

## 1. 基础用法与所有权复用

同一模块的多个 Hook 建议通过 `environment.forOwner(...)` 复用 owner；持有已解析 `Method` 时直接传入成员：

```kotlin
val hooks = environment.forOwner("my-module")
val receipt = hooks.install("feature.title", resolvedMethod) {
    replaceConstant("")
}
when {
    receipt.isInstalled -> {
        val owned = requireNotNull(receipt.registration())
        // 保存 owned，在适当时机调用 owned.close()
    }
    receipt.isSatisfied -> {
        // ALREADY_INSTALLED：等价注册已存在，继续管理初次安装时保存的句柄
    }
    else -> {
        // 按 status() 处理；部分安装仍可能携带需要关闭的 registration
    }
}
```

- **`isSatisfied`**：在 `INSTALLED` 或 `ALREADY_INSTALLED` 时返回 `true`，仅反映本次检查时刻的状态，不代表实时存活。`ALREADY_INSTALLED` 不会新建代次、不替换框架钩子、也不返回 `registration()`；需要确保必须安装成功时，Maven Central `1.2.0` 提供了 `hooks.installRequired(id, target) { ... }`（失败抛出携带原回执的 `HookInstallException`）。
- **单目标替换与强制换代**：使用 `hooks.replace(id, target) { ... }` 更新单目标回调；即使语义完全相同也需要新建代次时，使用 `hooks.forceReplace(id, resolvedMethod) { ... }`，它保留原有替换检查且绝不会回退成非原子的“先关闭再重装”。
- **幂等安装须复用实例**：回调引用身份（含 `MatchObserver`）及常量/条件返回值按对象引用比对；需要幂等返回 `ALREADY_INSTALLED` 时，请缓存并复用同一回调和返回值对象。`HookOwnerScope` 强引用所属 `environment`，不再使用时请及时释放。

## 2. 复用已校验身份与批量安装

若已构造 `HookId` 与 `HookOwner`，可直接调用重载避免重复转换：

```kotlin
val spec = hook(hookId, hookOwner, resolvedMethod) {
    replaceConstant("")
}
val receipt = environment.install(spec)
```

批量安装多个同 owner 的 Hook 时使用 `hooks.installAll { ... }`：块内先收集构建全部 `HookSpec` 再依次安装（遇到配置错误则一个都不装），并且默认解析器在本次调用内对同一个类仅加载一次、复制一次成员表。Central `1.2.0` 另提供带回调的重载 `hooks.installAll(onReceipt = { ... }) { ... }` 边装边收每项回执。

## 3. 参数形状与描述符语法

仅在 `after` 中读取结果时，保持默认 `NO_ARGS` 即可（SDK 不向后端请求参数）：

```kotlin
val receipt = hooks.install("feature.label", resolvedStringMethod) {
    after { _, result ->
        if (result.hasReturned() && result.getResult() == "old") result.setResult("new")
    }
}
```

需要按描述符名称查找时可用 `Class` / `KClass` 令牌辅助写法：

```kotlin
val target = method(ExampleTarget::class, "compute") {
    parameterTypes(Int::class, String::class)
    returning(Int::class)
}
val receipt = hooks.install("feature.compute", target) {
    replaceResultOnSuccess(42)
}
```

- `Class` / `KClass` 仅提供**类型名**，实际类身份在安装时对照后端当前 `ClassLoader` 解析；需要锁定精确反射身份时请使用 `member(resolvedMethod)`。
- 省略 `parameterTypes` 匹配任意重载；`parameterTypes()` 显式匹配零参数。
- Kotlin `Int::class` 对应基本类型 `int`；要匹配装箱类型 `java.lang.Integer`，请传 `Int::class.javaObjectType` 或字符串 `"java.lang.Integer"`。
- `returning(Unit::class)` 表示 `kotlin.Unit`，不是 JVM `void`；匹配无返回值或构造器请使用 `returning(Void.TYPE)` 或 `"void"`。

## 4. 类型化参数回调与安全取值

`beforeArgs`、`beforeMutableArgs` 与 `afterArgs` 直接暴露窄化接口，无需强转或另行调用 `access(...)`：

```kotlin
hooks.install("feature.title", resolvedMethod) {
    beforeArgs { log(it.getArgument(0)) }                 // 自动选用 READ_ARGS_VIEW
    beforeMutableArgs { it.setArgument(0, "rewritten") }  // 自动升级为 MUTABLE_ARGS
    afterArgs { invocation, result -> audit(invocation.getArgument(0), result.result) }
}
```

若显式写了冲突的 `access(...)`（例如 `access(NO_ARGS)` 配 `beforeArgs`），`build()` 会抛出异常。读取可选参数槽时可导入 `io.github.jichuo1.meguviz.hook.dsl.argumentOrNull`：

```kotlin
import io.github.jichuo1.meguviz.hook.dsl.argumentOrNull

val receipt = hooks.install("feature.key", resolvedMethod) {
    beforeArgs { invocation ->
        val key = invocation.argumentOrNull(0) as? String
        // 仅在本次回调内使用，不要保存 invocation 或其视图
    }
}
```

`argumentOrNull` 在索引越界或槽位值为 `null` 时均返回 `null`，但仍会核验借用对象有效性并传播有效槽位读取异常。
