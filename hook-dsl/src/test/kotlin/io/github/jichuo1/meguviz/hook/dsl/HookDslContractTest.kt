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
import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation
import io.github.jichuo1.meguviz.hook.api.ParameterAccess
import io.github.jichuo1.meguviz.hook.api.ProcessFilter
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

class HookDslContractTest {
    private val add: Method = Fixtures::class.java.getMethod(
        "add",
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
    )

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
