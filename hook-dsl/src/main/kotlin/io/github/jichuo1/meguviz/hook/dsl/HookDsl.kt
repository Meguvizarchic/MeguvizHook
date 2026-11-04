package io.github.jichuo1.meguviz.hook.dsl

import io.github.jichuo1.meguviz.hook.api.AfterCallback
import io.github.jichuo1.meguviz.hook.api.ArgsAfterCallback
import io.github.jichuo1.meguviz.hook.api.ArgsBeforeCallback
import io.github.jichuo1.meguviz.hook.api.AroundCallback
import io.github.jichuo1.meguviz.hook.api.BeforeCallback
import io.github.jichuo1.meguviz.hook.api.MutableArgsBeforeCallback
import io.github.jichuo1.meguviz.hook.api.Capability
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy
import io.github.jichuo1.meguviz.hook.api.HookId
import io.github.jichuo1.meguviz.hook.api.HookOwner
import io.github.jichuo1.meguviz.hook.api.HookSpec
import io.github.jichuo1.meguviz.hook.api.MatchPolicy
import io.github.jichuo1.meguviz.hook.api.MatchObserver
import io.github.jichuo1.meguviz.hook.api.ParameterAccess
import io.github.jichuo1.meguviz.hook.api.ProcessFilter
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt
import io.github.jichuo1.meguviz.hook.api.TargetKind
import io.github.jichuo1.meguviz.hook.api.TargetSpec
import io.github.jichuo1.meguviz.hook.core.HookEnvironment
import java.lang.reflect.Executable
import kotlin.reflect.KClass

@DslMarker
public annotation class HookDsl

public fun member(executable: Executable): TargetSpec.MemberTarget = TargetSpec.ofMember(executable)

/**
 * Read a declared argument slot, or return null when [index] is outside the declared parameter
 * count. The borrowed invocation is checked even for an invalid index. An existing null argument
 * is indistinguishable from a missing slot, and errors reading a valid slot propagate unchanged.
 */
public fun ReadArgsInvocation.argumentOrNull(index: Int): Any? {
    val count = argumentCount()
    return if (index < 0 || index >= count) null else getArgument(index)
}

public fun method(
    className: String,
    memberName: String,
    configure: DescriptorTargetDsl.() -> Unit = {},
): TargetSpec.DescriptorTarget = descriptor(TargetKind.METHOD, className, memberName, configure)

/**
 * Descriptor convenience: [owner] supplies a class name, not a fixed class identity. Installation
 * resolves that name using the backend's current class loader. Use [member] with a resolved Method
 * when an exact reflective member is required.
 */
public fun method(
    owner: Class<*>,
    memberName: String,
    configure: DescriptorTargetDsl.() -> Unit = {},
): TargetSpec.DescriptorTarget = method(owner.name, memberName, configure)

/** Uses the class name only; actual class identity is resolved against the backend loader. */
public fun method(
    owner: KClass<*>,
    memberName: String,
    configure: DescriptorTargetDsl.() -> Unit = {},
): TargetSpec.DescriptorTarget = method(owner.java, memberName, configure)

public fun constructor(
    className: String,
    configure: DescriptorTargetDsl.() -> Unit = {},
): TargetSpec.DescriptorTarget = descriptor(TargetKind.CONSTRUCTOR, className, "<init>", configure)

/** Uses [owner]'s name; the backend loader resolves the actual constructor owner. */
public fun constructor(
    owner: Class<*>,
    configure: DescriptorTargetDsl.() -> Unit = {},
): TargetSpec.DescriptorTarget = constructor(owner.name, configure)

/** Uses the class name only; actual class identity is resolved against the backend loader. */
public fun constructor(
    owner: KClass<*>,
    configure: DescriptorTargetDsl.() -> Unit = {},
): TargetSpec.DescriptorTarget = constructor(owner.java, configure)

private fun descriptor(
    kind: TargetKind,
    className: String,
    memberName: String,
    configure: DescriptorTargetDsl.() -> Unit,
): TargetSpec.DescriptorTarget = DescriptorTargetDsl(TargetSpec.descriptor(kind, className, memberName))
    .apply(configure)
    .build()

@HookDsl
public class DescriptorTargetDsl internal constructor(
    private val delegate: TargetSpec.DescriptorTarget.Builder,
) {
    /** Calling this with no arguments selects exactly zero parameters; omitting it keeps any overload. */
    public fun parameters(vararg typeNames: String) {
        delegate.parameters(*typeNames)
    }

    /** Match parameter type names from classes; calling without arguments matches zero parameters. */
    public fun parameterTypes(vararg types: Class<*>) {
        delegate.parameters(*types.map { it.name }.toTypedArray())
    }

    /**
     * Kotlin class tokens use their JVM class names: Int::class is primitive int. Use
     * Int::class.javaObjectType with the Class overload to select java.lang.Integer. The required
     * first argument keeps parameterTypes() as the unambiguous zero-argument selector.
     */
    public fun parameterTypes(first: KClass<*>, vararg rest: KClass<*>) {
        delegate.parameters(first.java.name, *rest.map { it.java.name }.toTypedArray())
    }

    public fun returning(typeName: String) {
        delegate.returning(typeName)
    }

    /** Match the type's name, not a Class identity; primitive class tokens remain primitive. */
    public fun returning(type: Class<*>) {
        delegate.returning(type.name)
    }

    /** Uses the Kotlin token's JVM class name; Unit::class means kotlin.Unit, not JVM void. */
    public fun returning(type: KClass<*>) {
        delegate.returning(type.java.name)
    }

    public fun initializeClass(value: Boolean) {
        delegate.initializeClass(value)
    }

    public fun match(policy: MatchPolicy) {
        delegate.match(policy)
    }

    internal fun build(): TargetSpec.DescriptorTarget = delegate.build()
}

public fun hook(
    id: String,
    owner: String,
    target: TargetSpec,
    configure: HookSpecDsl.() -> Unit,
): HookSpec = HookSpecDsl(HookSpec.Builder(HookId.of(id), HookOwner.of(owner), target))
    .apply(configure)
    .build()

/** Build a spec from already validated identity objects and an explicit target. */
public fun hook(
    id: HookId,
    owner: HookOwner,
    target: TargetSpec,
    configure: HookSpecDsl.() -> Unit,
): HookSpec = HookSpecDsl(HookSpec.Builder(id, owner, target))
    .apply(configure)
    .build()

/** Build a spec from already validated identity objects and a resolved member. */
public fun hook(
    id: HookId,
    owner: HookOwner,
    target: Executable,
    configure: HookSpecDsl.() -> Unit,
): HookSpec = hook(id, owner, member(target), configure)

public fun HookEnvironment.install(
    id: String,
    owner: String,
    target: TargetSpec,
    configure: HookSpecDsl.() -> Unit,
): RegistrationReceipt = install(hook(id, owner, target, configure))

/** Install an already resolved method or constructor without wrapping it at the call site. */
public fun HookEnvironment.install(
    id: String,
    owner: String,
    target: Executable,
    configure: HookSpecDsl.() -> Unit,
): RegistrationReceipt = install(id, owner, member(target), configure)

public fun HookEnvironment.replace(
    id: String,
    owner: String,
    target: TargetSpec,
    configure: HookSpecDsl.() -> Unit,
): RegistrationReceipt = replace(hook(id, owner, target, configure))

/** Replace a registration for an already resolved method or constructor. */
public fun HookEnvironment.replace(
    id: String,
    owner: String,
    target: Executable,
    configure: HookSpecDsl.() -> Unit,
): RegistrationReceipt = replace(id, owner, member(target), configure)

/** Explicitly replace even an equivalent single-target spec, returning the original receipt. */
public fun HookEnvironment.forceReplace(
    id: String,
    owner: String,
    target: TargetSpec,
    configure: HookSpecDsl.() -> Unit,
): RegistrationReceipt = forceReplace(hook(id, owner, target, configure))

/** Force a new generation for an already resolved method or constructor. */
public fun HookEnvironment.forceReplace(
    id: String,
    owner: String,
    target: Executable,
    configure: HookSpecDsl.() -> Unit,
): RegistrationReceipt = forceReplace(id, owner, member(target), configure)

/**
 * Reuse one explicit owner across installs. This object is a construction convenience, not a
 * lifetime owner: it creates no threads, never closes the environment, and returns normal receipts.
 * It retains a strong reference to the environment while the scope remains reachable.
 */
public fun HookEnvironment.forOwner(owner: String): HookOwnerScope = HookOwnerScope(this, HookOwner.of(owner))

@HookDsl
public class HookOwnerScope internal constructor(
    private val environment: HookEnvironment,
    private val owner: HookOwner,
) {
    /** Build a normal spec without installing it or adapting the supplied callbacks. */
    public fun spec(id: String, target: TargetSpec, configure: HookSpecDsl.() -> Unit): HookSpec =
        HookSpecDsl(HookSpec.Builder(HookId.of(id), owner, target)).apply(configure).build()

    /** Build a normal spec for an already resolved method or constructor. */
    public fun spec(id: String, target: Executable, configure: HookSpecDsl.() -> Unit): HookSpec =
        spec(id, member(target), configure)

    /** Returns the unchanged install receipt, including partial, conflict and failure outcomes. */
    public fun install(id: String, target: TargetSpec, configure: HookSpecDsl.() -> Unit): RegistrationReceipt =
        environment.install(spec(id, target, configure))

    public fun install(id: String, target: Executable, configure: HookSpecDsl.() -> Unit): RegistrationReceipt =
        install(id, member(target), configure)

    /**
     * Build several specs with this owner, then install them in order with
     * [HookEnvironment.installAll]: one receipt per spec, same checks as [install], and the default
     * resolver copies each class's declared members once for the whole call. Every spec is built
     * before anything is installed, so a configuration error installs nothing.
     *
     * ```kotlin
     * val receipts = hooks.installAll {
     *     hook("feature.title", titleMethod) { replaceConstant("") }
     *     hook("feature.log", method("com.example.Foo", "log")) { beforeArgs { trace(it.getArgument(0)) } }
     * }
     * ```
     */
    public fun installAll(configure: HookBatchDsl.() -> Unit): List<RegistrationReceipt> =
        environment.installAll(HookBatchDsl(this).apply(configure).specs())

    /** Explicit single-target replacement; no automatic retry or lifecycle ownership is added. */
    public fun replace(id: String, target: TargetSpec, configure: HookSpecDsl.() -> Unit): RegistrationReceipt =
        environment.replace(spec(id, target, configure))

    public fun replace(id: String, target: Executable, configure: HookSpecDsl.() -> Unit): RegistrationReceipt =
        replace(id, member(target), configure)

    /** Force a new single-target generation even when its configuration is equivalent. */
    public fun forceReplace(id: String, target: TargetSpec, configure: HookSpecDsl.() -> Unit): RegistrationReceipt =
        environment.forceReplace(spec(id, target, configure))

    public fun forceReplace(id: String, target: Executable, configure: HookSpecDsl.() -> Unit): RegistrationReceipt =
        forceReplace(id, member(target), configure)
}

/** Collects specs for [HookOwnerScope.installAll]; nothing is installed until the block returns. */
@HookDsl
public class HookBatchDsl internal constructor(
    private val scope: HookOwnerScope,
) {
    private val specs = ArrayList<HookSpec>()

    /** Add a spec for a descriptor or member target; it is installed in the order added. */
    public fun hook(id: String, target: TargetSpec, configure: HookSpecDsl.() -> Unit) {
        specs += scope.spec(id, target, configure)
    }

    /** Add a spec for an already resolved method or constructor. */
    public fun hook(id: String, target: Executable, configure: HookSpecDsl.() -> Unit) {
        specs += scope.spec(id, target, configure)
    }

    internal fun specs(): List<HookSpec> = ArrayList(specs)
}

@HookDsl
public class HookSpecDsl internal constructor(
    private val delegate: HookSpec.Builder,
) {
    public fun access(value: ParameterAccess) {
        delegate.access(value)
    }

    public fun before(callback: BeforeCallback) {
        delegate.before(callback)
    }

    /**
     * Read arguments without a cast: `beforeArgs { log(it.getArgument(0)) }`. Selects
     * READ_ARGS_VIEW unless [access] was called; an explicit shape must be readable.
     */
    public fun beforeArgs(callback: ArgsBeforeCallback) {
        delegate.beforeArgs(callback)
    }

    /**
     * Rewrite arguments without a cast: `beforeMutableArgs { it.setArgument(0, "x") }`. Selects
     * MUTABLE_ARGS unless [access] was called; an explicit shape must be MUTABLE_ARGS.
     */
    public fun beforeMutableArgs(callback: MutableArgsBeforeCallback) {
        delegate.beforeMutableArgs(callback)
    }

    public fun after(callback: AfterCallback) {
        delegate.after(callback)
    }

    /** Read arguments and the result without a cast; shape selection follows [beforeArgs]. */
    public fun afterArgs(callback: ArgsAfterCallback) {
        delegate.afterArgs(callback)
    }

    public fun around(callback: AroundCallback) {
        delegate.around(callback)
    }

    /** Return this exact value without running the downstream call. */
    public fun replaceConstant(value: Any?) {
        delegate.replaceConstant(value)
    }

    /** Replace only when a String argument matches; unmatched calls proceed unchanged. */
    public fun replaceIfStringArgumentEquals(index: Int, expected: String?, result: Any?, observer: MatchObserver? = null) {
        delegate.replaceIfStringArgumentEquals(index, expected, result, observer)
    }

    /** Proceed with the original arguments and replace only a successful result. */
    public fun replaceResultOnSuccess(value: Any?) {
        delegate.replaceResultOnSuccess(value)
    }

    public fun errorPolicy(value: ErrorPolicy) {
        delegate.errorPolicy(value)
    }

    public fun require(capability: Capability) {
        delegate.require(capability)
    }

    public fun processes(filter: ProcessFilter) {
        delegate.processes(filter)
    }

    public fun priority(value: Int) {
        delegate.priority(value)
    }

    internal fun build(): HookSpec = delegate.build()
}
