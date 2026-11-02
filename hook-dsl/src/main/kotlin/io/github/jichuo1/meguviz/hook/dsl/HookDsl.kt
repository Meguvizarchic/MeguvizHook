package io.github.jichuo1.meguviz.hook.dsl

import io.github.jichuo1.meguviz.hook.api.AfterCallback
import io.github.jichuo1.meguviz.hook.api.AroundCallback
import io.github.jichuo1.meguviz.hook.api.BeforeCallback
import io.github.jichuo1.meguviz.hook.api.Capability
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy
import io.github.jichuo1.meguviz.hook.api.HookId
import io.github.jichuo1.meguviz.hook.api.HookOwner
import io.github.jichuo1.meguviz.hook.api.HookSpec
import io.github.jichuo1.meguviz.hook.api.MatchPolicy
import io.github.jichuo1.meguviz.hook.api.ParameterAccess
import io.github.jichuo1.meguviz.hook.api.ProcessFilter
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt
import io.github.jichuo1.meguviz.hook.api.TargetKind
import io.github.jichuo1.meguviz.hook.api.TargetSpec
import io.github.jichuo1.meguviz.hook.core.HookEnvironment
import java.lang.reflect.Executable

@DslMarker
public annotation class HookDsl

public fun member(executable: Executable): TargetSpec.MemberTarget = TargetSpec.ofMember(executable)

public fun method(
    className: String,
    memberName: String,
    configure: DescriptorTargetDsl.() -> Unit = {},
): TargetSpec.DescriptorTarget = descriptor(TargetKind.METHOD, className, memberName, configure)

public fun constructor(
    className: String,
    configure: DescriptorTargetDsl.() -> Unit = {},
): TargetSpec.DescriptorTarget = descriptor(TargetKind.CONSTRUCTOR, className, "<init>", configure)

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

    public fun returning(typeName: String) {
        delegate.returning(typeName)
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

public fun HookEnvironment.install(
    id: String,
    owner: String,
    target: TargetSpec,
    configure: HookSpecDsl.() -> Unit,
): RegistrationReceipt = install(hook(id, owner, target, configure))

public fun HookEnvironment.replace(
    id: String,
    owner: String,
    target: TargetSpec,
    configure: HookSpecDsl.() -> Unit,
): RegistrationReceipt = replace(hook(id, owner, target, configure))

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

    public fun after(callback: AfterCallback) {
        delegate.after(callback)
    }

    public fun around(callback: AroundCallback) {
        delegate.around(callback)
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
