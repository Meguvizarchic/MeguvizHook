package io.github.jichuo1.meguviz.hook.dsl

import io.github.jichuo1.meguviz.hook.api.AfterCallback
import io.github.jichuo1.meguviz.hook.api.AroundCallback
import io.github.jichuo1.meguviz.hook.api.BeforeCallback
import io.github.jichuo1.meguviz.hook.api.CallOutcome
import io.github.jichuo1.meguviz.hook.api.Capability
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy
import io.github.jichuo1.meguviz.hook.api.HookContractException
import io.github.jichuo1.meguviz.hook.api.HookId
import io.github.jichuo1.meguviz.hook.api.HookOwner
import io.github.jichuo1.meguviz.hook.api.HookSpec
import io.github.jichuo1.meguviz.hook.api.MatchPolicy
import io.github.jichuo1.meguviz.hook.api.MatchObserver
import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation
import io.github.jichuo1.meguviz.hook.api.ParameterAccess
import io.github.jichuo1.meguviz.hook.api.ProcessFilter
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt
import io.github.jichuo1.meguviz.hook.api.TargetKind
import io.github.jichuo1.meguviz.hook.api.TargetSpec
import io.github.jichuo1.meguviz.hook.core.HookEnvironment
import io.github.jichuo1.meguviz.hook.testing.FakeBackend
import io.github.jichuo1.meguviz.hook.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

class HookDslContractTest {
    private val add: Method = Fixtures::class.java.getMethod(
        "add",
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
    )

    @Test
    fun nullableArgumentReadKeepsBoundsBorrowAndFailureSemantics() {
        val concat = Fixtures::class.java.getMethod("concat", String::class.java, String::class.java)
        val backend = FakeBackend.create()
        backend.onTarget(concat) { _, _ -> "original" }
        val retained = AtomicReference<ReadArgsInvocation>()
        HookEnvironment.create(backend).use { environment ->
            val receipt = environment.install("nullable.args", "kotlin-test", concat) {
                access(ParameterAccess.READ_ARGS_VIEW)
                before { invocation ->
                    val args = invocation as ReadArgsInvocation
                    retained.set(args)
                    assertNull(args.argumentOrNull(-1))
                    assertEquals("left", args.argumentOrNull(0))
                    assertNull(args.argumentOrNull(1))
                    assertNull(args.argumentOrNull(2))
                    assertNull(args.argumentOrNull(Int.MAX_VALUE))

                    val failure = IllegalStateException("valid slot failed")
                    val broken = object : ReadArgsInvocation by args {
                        override fun getArgument(index: Int): Any? = throw failure
                    }
                    assertSame(failure, assertThrows(IllegalStateException::class.java) { broken.argumentOrNull(0) })

                    val crossThread = AtomicReference<Throwable>()
                    val worker = Thread {
                        try { args.argumentOrNull(-1) } catch (failure: Throwable) { crossThread.set(failure) }
                    }
                    worker.start()
                    worker.join(5000)
                    assertTrue("cross-thread check should finish", !worker.isAlive)
                    assertTrue(crossThread.get() is HookContractException)
                }
            }
            assertTrue(receipt.isInstalled)
            assertEquals("original", backend.call(concat, null, "left", null))
            assertThrows(HookContractException::class.java) { retained.get().argumentOrNull(-1) }
            assertThrows(HookContractException::class.java) { retained.get().argumentOrNull(0) }
        }

        val emptyTarget = Fixtures::class.java.getMethod("noArguments")
        val emptyBackend = FakeBackend.create()
        emptyBackend.onTarget(emptyTarget) { _, _ -> 42L }
        HookEnvironment.create(emptyBackend).use { environment ->
            val receipt = environment.install("nullable.empty", "kotlin-test", emptyTarget) {
                access(ParameterAccess.READ_ARGS_VIEW)
                before { invocation -> assertNull((invocation as ReadArgsInvocation).argumentOrNull(0)) }
            }
            assertTrue(receipt.isInstalled)
            assertEquals(42L, emptyBackend.call(emptyTarget, null))
        }
    }

    @Test
    fun ownerScopeReusesCallbacksAndReturnsOriginalReceipts() {
        val backend = backendWithAdd()
        val environment = HookEnvironment.create(backend)
        val owner = environment.forOwner("bil-hook-pilot")
        val callback = BeforeCallback { invocation -> invocation.setEarlyResult(77) }
        try {
            val spec = owner.spec("pilot.title", add) { before(callback) }
            assertEquals("bil-hook-pilot", spec.owner().value())
            assertSame(callback, spec.beforeCallbacks().single())
            val first = owner.install("pilot.title", add) { before(callback) }
            assertTrue(first.isInstalled)
            val repeated = owner.install("pilot.title", add) { before(callback) }
            assertEquals(RegistrationReceipt.Status.ALREADY_INSTALLED, repeated.status())
            assertEquals(1, backend.installCount())
            assertEquals(77, backend.call(add, null, 1, 2))
            assertEquals(0, backend.originalCallCount(add))
            assertEquals(0, backend.argumentMaterializationCount())
            val replacement = owner.replace("pilot.title", add) { replaceConstant(88) }
            assertTrue(replacement.isInstalled)
            assertEquals(88, backend.call(add, null, 1, 2))
            assertTrue(first.registration().close().newCallbacksRejected())
            assertEquals(88, backend.call(add, null, 1, 2))
            assertTrue(replacement.registration().close().newCallbacksRejected())
        } finally { environment.close() }
        assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
            owner.install("pilot.closed", add) { replaceConstant(1) }.status())
        assertThrows(IllegalArgumentException::class.java) { environment.forOwner("  ") }
    }

    @Test
    fun forcedReplacementConveniencesKeepGenerationOwnershipAndCapabilityChecks() {
        val backend = backendWithAdd()
        HookEnvironment.create(backend).use { environment ->
            val owner = environment.forOwner("module")
            val callback = BeforeCallback { it.setEarlyResult(77) }
            val first = owner.install("forced", add) { before(callback) }
            assertTrue(first.isInstalled)
            assertEquals(RegistrationReceipt.Status.ALREADY_INSTALLED,
                owner.replace("forced", add) { before(callback) }.status())

            val memberTarget = member(add)
            val firstReplacement = owner.forceReplace("forced", add) { before(callback) }
            val secondReplacement = owner.forceReplace("forced", memberTarget) { before(callback) }
            val thirdReplacement = environment.forceReplace("forced", "module", add) { before(callback) }
            val last = environment.forceReplace("forced", "module", memberTarget) { before(callback) }
            val generations = listOf(first, firstReplacement, secondReplacement, thirdReplacement, last)
            assertTrue(generations.all { it.isInstalled })
            assertTrue(generations.zipWithNext().all { (a, b) -> a.generation() < b.generation() })
            assertEquals(4, backend.replaceCount())
            first.registration().close()
            assertEquals(77, backend.call(add, null, 1, 2))
            backend.withCapability(Capability.ATOMIC_REPLACE,
                io.github.jichuo1.meguviz.hook.api.CapabilityResult.unsupported("fake", "off"))
            assertEquals(RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY,
                owner.forceReplace("forced", add) { before(callback) }.status())
            assertEquals(77, backend.call(add, null, 1, 2))
            last.registration().close()
        }
    }

    @Test
    fun classDescriptorsUseTypeNamesAndPreserveZeroArgumentDistinction() {
        val intType = Int::class.javaPrimitiveType!!
        val target = method(Fixtures::class, "add") {
            parameterTypes(intType, intType)
            returning(intType)
        }
        assertEquals(Fixtures::class.java.name, target.className())
        assertEquals(listOf("int", "int"), target.parameterTypeNames())
        assertEquals("int", target.returnTypeName())
        assertEquals(emptyList<String>(), method(Fixtures::class.java, "missing") { parameterTypes() }.parameterTypeNames())
        assertNull(method(Fixtures::class.java, "missing").parameterTypeNames())
        assertEquals(StringBuilder::class.java.name, constructor(StringBuilder::class) {
            parameterTypes(String::class.java)
        }.className())

        val backend = backendWithAdd()
        HookEnvironment.create(backend).use { environment ->
            val receipt = environment.forOwner("typed-descriptor").install("typed.add", target) {
                replaceConstant(55)
            }
            assertTrue(receipt.isInstalled)
            assertEquals(55, backend.call(add, null, 1, 2))
            assertEquals(0, backend.originalCallCount(add))
        }
    }

    @Test
    fun kotlinTypeTokensDistinguishPrimitiveBoxedAndZeroArguments() {
        val primitive = method(Fixtures::class, "add") {
            parameterTypes(Int::class, Int::class)
            returning(Int::class)
        }
        assertEquals(listOf("int", "int"), primitive.parameterTypeNames())
        assertEquals("int", primitive.returnTypeName())

        val boxed = method(Fixtures::class, "add") {
            parameterTypes(Int::class.javaObjectType)
            returning(Int::class.javaObjectType)
        }
        assertEquals(listOf("java.lang.Integer"), boxed.parameterTypeNames())
        assertEquals("java.lang.Integer", boxed.returnTypeName())
        assertEquals(emptyList<String>(), method(Fixtures::class, "none") { parameterTypes() }.parameterTypeNames())
        assertNull(method(Fixtures::class, "none").parameterTypeNames())
        assertEquals("kotlin.Unit", method(Fixtures::class, "none") { returning(Unit::class) }.returnTypeName())

        val backend = backendWithAdd()
        HookEnvironment.create(backend).use { environment ->
            val receipt = environment.forOwner("kotlin-types").install("kotlin.types", primitive) {
                replaceConstant(7)
            }
            assertTrue(receipt.isInstalled)
            assertEquals(7, backend.call(add, null, 1, 2))
            assertEquals(0, backend.originalCallCount(add))
        }
    }

    @Test
    fun rawMemberExtensionsKeepFailureAndReplacementSemantics() {
        val backend = backendWithAdd()
        HookEnvironment.create(backend).use { environment ->
            val first = environment.install("direct.member", "sample", add) { replaceConstant(10) }
            assertTrue(first.isInstalled)
            val updated = environment.replace("direct.member", "sample", add) { replaceResultOnSuccess(20) }
            assertTrue(updated.isInstalled)
            assertEquals(20, backend.call(add, null, 1, 2))
            assertEquals(1, backend.originalCallCount(add))
            val missing = environment.forOwner("sample").install("direct.missing", method(Fixtures::class, "absent")) {
                replaceConstant(1)
            }
            assertEquals(RegistrationReceipt.Status.TARGET_MISSING, missing.status())
        }
    }

    @Test
    fun constantReplacementUsesTheJavaContractAndDoesNotProceed() {
        val backend = backendWithAdd()
        HookEnvironment.create(backend).use { environment ->
            val receipt = environment.install("sample.kotlin.constant", "kotlin-sample", member(add)) {
                replaceConstant(42)
            }
            assertTrue(receipt.isInstalled)
            assertEquals(42, backend.call(add, null, 1, 2))
            assertEquals(0, backend.originalCallCount(add))
        }
        assertThrows(IllegalArgumentException::class.java) {
            hook("sample.kotlin.constant.conflict", "kotlin-sample", member(add)) {
                replaceConstant(42)
                before { }
            }
        }
    }

    @Test
    fun conditionalReplacementPreservesObserverAndProceedsOnMiss() {
        val concat = Fixtures::class.java.getMethod("concat", String::class.java, String::class.java)
        val backend = FakeBackend.create()
        backend.onTarget(concat) { _, args -> (args[0] as String) + (args[1] as String) }
        var matches = 0
        val observer = MatchObserver { matches++ }
        HookEnvironment.create(backend).use { environment ->
            val spec = hook("sample.kotlin.conditional", "kotlin-sample", member(concat)) {
                replaceIfStringArgumentEquals(0, "match", "fixed", observer)
            }
            assertEquals(ParameterAccess.READ_ARGS_VIEW, spec.access())
            assertSame(observer, spec.stringArgumentCondition().observer())
            assertTrue(environment.install(spec).isInstalled)
            assertEquals("fixed", backend.call(concat, null, "match", "x"))
            assertEquals(1, matches)
            assertEquals(0, backend.originalCallCount(concat))
            assertEquals("missx", backend.call(concat, null, "miss", "x"))
            assertEquals(1, backend.originalCallCount(concat))
        }
    }

    @Test
    fun successResultRunsOriginalAndRejectsMixedCallbacks() {
        val backend = backendWithAdd()
        HookEnvironment.create(backend).use { environment ->
            val receipt = environment.install("sample.kotlin.success", "kotlin-sample", member(add)) {
                replaceResultOnSuccess(93)
            }
            assertTrue(receipt.isInstalled)
            assertEquals(Integer.valueOf(93), backend.call(add, null, 1, 2))
            assertEquals(1, backend.originalCallCount(add))
            receipt.registration().close()
            assertEquals(Integer.valueOf(3), backend.call(add, null, 1, 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            hook("sample.kotlin.success.conflict", "kotlin-sample", member(add)) {
                replaceResultOnSuccess(93)
                before { }
            }
        }
    }

    @Test
    fun javaAndKotlinMutableHooksProduceTheSameResult() {
        val backend = backendWithAdd()
        HookEnvironment.create(backend).use { environment ->
            val javaSpec = HookSpec.Builder(
                HookId.of("sample.java.mutable"),
                HookOwner.of("java-sample"),
                TargetSpec.ofMember(add),
            )
                .access(ParameterAccess.MUTABLE_ARGS)
                .before { invocation -> (invocation as MutableArgsInvocation).setArgument(0, 10) }
                .build()
            val javaReceipt = environment.install(javaSpec)
            assertTrue(javaReceipt.isInstalled)
            assertEquals(Integer.valueOf(12), backend.call(add, null, 1, 2))
            javaReceipt.registration().close()

            val kotlinReceipt = environment.install("sample.kotlin.mutable", "kotlin-sample", member(add)) {
                access(ParameterAccess.MUTABLE_ARGS)
                before { invocation -> (invocation as MutableArgsInvocation).setArgument(0, 10) }
            }
            assertTrue(kotlinReceipt.isInstalled)
            assertEquals(Integer.valueOf(12), backend.call(add, null, 1, 2))
            assertEquals(2, backend.originalCallCount(add))
            assertTrue(kotlinReceipt.registration().close().newCallbacksRejected())
        }
    }

    @Test
    fun installExtensionReturnsTheOriginalReceiptAndPreservesEarlyReturn() {
        val backend = backendWithAdd()
        HookEnvironment.create(backend).use { environment ->
            val receipt = environment.install("sample.kotlin.early", "kotlin-sample", member(add)) {
                before { invocation -> invocation.setEarlyResult(99) }
            }

            assertTrue(receipt.isInstalled)
            assertEquals("sample.kotlin.early", receipt.id().value())
            assertEquals(Integer.valueOf(99), backend.call(add, null, 5, 6))
            assertEquals(0, backend.originalCallCount(add))
            assertTrue(receipt.registration().close().newCallbacksRejected())
        }
    }

    @Test
    @Suppress("UNUSED_EXPRESSION") // A non-Unit last expression must not become the hook result.
    fun aroundRequiresExplicitProceedAndIgnoresTheLambdaReturnValue() {
        val backend = backendWithAdd()
        HookEnvironment.create(backend).use { environment ->
            val spec = hook("sample.kotlin.around", "kotlin-sample", member(add)) {
                around { invocation ->
                    assertEquals(CallOutcome.State.RETURNED, invocation.proceed().state())
                    99
                }
            }
            val receipt = environment.install(spec)

            assertEquals(ParameterAccess.AROUND, spec.access())
            assertTrue(receipt.isInstalled)
            assertEquals(Integer.valueOf(3), backend.call(add, null, 1, 2))
            assertEquals(1, backend.originalCallCount(add))
        }
    }

    @Test
    fun descriptorDistinguishesAnyOverloadFromExactlyNoArguments() {
        val anyOverload = method("example.Target", "overloaded")
        val noArguments = method("example.Target", "overloaded") {
            parameters()
        }

        assertNull(anyOverload.parameterTypeNames())
        assertEquals(emptyList<String>(), noArguments.parameterTypeNames())
        assertEquals(MatchPolicy.UNIQUE, anyOverload.matchPolicy())
    }

    @Test
    fun descriptorOptionsDelegateToTheJavaBuilder() {
        val target = method("example.Target", "work") {
            parameters("java.lang.String")
            returning("void")
            initializeClass(true)
            match(MatchPolicy.ALL)
        }

        assertEquals(TargetKind.METHOD, target.kind())
        assertEquals("example.Target", target.className())
        assertEquals("work", target.memberName())
        assertEquals(listOf("java.lang.String"), target.parameterTypeNames())
        assertEquals("void", target.returnTypeName())
        assertTrue(target.initialize())
        assertEquals(MatchPolicy.ALL, target.matchPolicy())
    }

    @Test
    fun memberAndConstructorTargetsReuseExistingContractTypes() {
        val constructor = constructor("example.Target") {
            parameters("int")
        }

        assertSame(add, member(add).executable())
        assertEquals(TargetKind.CONSTRUCTOR, constructor.kind())
        assertEquals("<init>", constructor.memberName())
        assertEquals(listOf("int"), constructor.parameterTypeNames())
    }

    @Test
    fun hookDefaultsComeFromTheJavaBuilder() {
        val spec = hook("sample.defaults", "kotlin-test", member(add)) {
            before { }
        }

        assertEquals(ParameterAccess.NO_ARGS, spec.access())
        assertEquals(ErrorPolicy.PROPAGATE, spec.errorPolicy())
        assertEquals(ProcessFilter.all(), spec.processFilter())
        assertEquals(HookSpec.DEFAULT_PRIORITY, spec.priority())
        assertTrue(spec.requiredCapabilities().isEmpty())
    }

    @Test
    fun typedHookReusesIdentityTargetAndCallback() {
        val id = HookId.of("typed.identity")
        val owner = HookOwner.of("typed-owner")
        val target = member(add)
        var calls = 0
        val callback = BeforeCallback { invocation ->
            calls++
            invocation.setEarlyResult(77)
        }
        val configured = hook(id, owner, target) { before(callback) }
        val resolved = hook(id, owner, add) { before(callback) }
        val builder = HookSpec.Builder(id, owner, target).before(callback).build()

        assertSame(id, configured.id())
        assertSame(owner, configured.owner())
        assertSame(target, configured.target())
        assertSame(callback, configured.beforeCallbacks().single())
        assertSame(add, (resolved.target() as TargetSpec.MemberTarget).executable())
        assertTrue(configured.hasSameSemantics(builder))
        assertTrue(resolved.hasSameSemantics(builder))

        val backend = backendWithAdd()
        HookEnvironment.create(backend).use { environment ->
            assertTrue(environment.install(resolved).isInstalled)
            assertEquals(77, backend.call(add, null, 1, 2))
            assertEquals(1, calls)
            assertEquals(0, backend.originalCallCount(add))
        }
    }

    @Test
    fun hookOptionsAndCallbackInstancesArePreserved() {
        val before = BeforeCallback { }
        val after = AfterCallback { _, _ -> }
        val around = AroundCallback { invocation -> invocation.setEarlyResult(0) }
        val spec = hook("sample.options", "kotlin-test", member(add)) {
            access(ParameterAccess.READ_ARGS)
            before(before)
            after(after)
            errorPolicy(ErrorPolicy.PROPAGATE)
            require(Capability.FRAMEWORK_CHAIN)
            processes(ProcessFilter.only("main", "render"))
            priority(73)
        }
        val aroundSpec = hook("sample.options.around", "kotlin-test", member(add)) {
            around(around)
        }

        assertEquals(ParameterAccess.READ_ARGS, spec.access())
        assertSame(before, spec.beforeCallbacks().single())
        assertSame(after, spec.afterCallbacks().single())
        assertSame(around, aroundSpec.aroundCallback())
        assertEquals(setOf(Capability.FRAMEWORK_CHAIN), spec.requiredCapabilities())
        assertEquals(ProcessFilter.only("main", "render"), spec.processFilter())
        assertEquals(73, spec.priority())
    }

    @Test
    fun afterCallbackRunsAndObservesAnEarlyReturn() {
        val backend = backendWithAdd()
        var observedResult: Any? = null
        HookEnvironment.create(backend).use { environment ->
            val receipt = environment.install("sample.kotlin.after", "kotlin-sample", member(add)) {
                before { invocation -> invocation.setEarlyResult(99) }
                after { _, result -> observedResult = result.getResult() }
            }

            assertTrue(receipt.isInstalled)
            assertEquals(Integer.valueOf(99), backend.call(add, null, 1, 2))
            assertEquals(Integer.valueOf(99), observedResult)
            assertEquals(0, backend.originalCallCount(add))
        }
    }

    @Test
    fun aroundWithoutOutcomeDoesNotCallOriginalImplicitly() {
        val backend = backendWithAdd()
        HookEnvironment.create(backend).use { environment ->
            environment.install("sample.kotlin.undecided", "kotlin-sample", member(add)) {
                around { }
            }

            assertThrows(HookContractException::class.java) {
                backend.call(add, null, 1, 2)
            }
            assertEquals(0, backend.originalCallCount(add))
        }
    }

    @Test
    fun secondProceedRemainsAContractViolationWithoutReplay() {
        val backend = backendWithAdd()
        HookEnvironment.create(backend).use { environment ->
            environment.install("sample.kotlin.twice", "kotlin-sample", member(add)) {
                around { invocation ->
                    invocation.proceed()
                    invocation.proceed()
                }
            }

            assertThrows(HookContractException::class.java) {
                backend.call(add, null, 1, 2)
            }
            assertEquals(1, backend.originalCallCount(add))
        }
    }

    @Test
    fun replaceExtensionAtomicallySwapsTheCallback() {
        val backend = backendWithAdd()
        HookEnvironment.create(backend).use { environment ->
            val original = environment.install("sample.kotlin.replace", "kotlin-sample", member(add)) {
                before { invocation -> invocation.setEarlyResult(99) }
            }
            val replaced = environment.replace("sample.kotlin.replace", "kotlin-sample", member(add)) {
                before { invocation -> invocation.setEarlyResult(100) }
            }

            assertTrue(replaced.isInstalled)
            assertTrue(replaced.generation() > original.generation())
            assertEquals(1, backend.installCount())
            assertEquals(1, backend.replaceCount())
            assertEquals(Integer.valueOf(100), backend.call(add, null, 1, 2))
            original.registration().close()
            assertTrue(backend.isHooked(add))
            replaced.registration().close()
            assertTrue(!backend.isHooked(add))
        }
    }

    @Test
    fun javaBuilderValidationStillRejectsAnEmptyHook() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            hook("sample.empty", "kotlin-test", member(add)) { }
        }

        assertEquals("at least one before or after callback is required", failure.message)
    }

    private fun backendWithAdd(): FakeBackend {
        val backend = FakeBackend.create()
        backend.onTarget(add) { _, arguments ->
            (arguments[0] as Int) + (arguments[1] as Int)
        }
        return backend
    }
}
