package io.github.jichuo1.meguviz.hook.dsl

import io.github.jichuo1.meguviz.hook.api.ParameterAccess
import io.github.jichuo1.meguviz.hook.core.HookEnvironment
import io.github.jichuo1.meguviz.hook.testing.FakeBackend
import io.github.jichuo1.meguviz.hook.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedCallbackDslTest {
    private val add = Fixtures::class.java.getMethod("add", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

    @Test
    fun typedCallbacksNeedNoCastOrShapeCall() {
        val backend = FakeBackend.create()
        backend.onTarget(add) { _, args -> (args[0] as Int) + (args[1] as Int) }
        HookEnvironment.create(backend).use { environment ->
            val seen = mutableListOf<Any?>()
            val hooks = environment.forOwner("dsl-typed")
            val spec = hooks.spec("dsl.typed", add) {
                beforeArgs { seen += it.getArgument(0) }
                beforeMutableArgs { it.setArgument(1, 40) }
                afterArgs { invocation, result -> result.setResult((result.result as Int) + (invocation.getArgument(0) as Int)) }
            }
            assertEquals(ParameterAccess.MUTABLE_ARGS, spec.access())
            assertTrue(environment.install(spec).isInstalled)
            // 2 + 40 from the rewrite, then the after callback adds argument 0 again.
            assertEquals(44, backend.call(add, null, 2, 3))
            assertEquals(listOf<Any?>(2), seen)
        }
    }

    @Test
    fun readOnlyTypedCallbackSelectsTheView() {
        val backend = FakeBackend.create()
        backend.onTarget(add) { _, args -> (args[0] as Int) + (args[1] as Int) }
        HookEnvironment.create(backend).use { environment ->
            val hooks = environment.forOwner("dsl-typed")
            val spec = hooks.spec("dsl.typed.view", add) { beforeArgs { require(it.argumentCount() == 2) } }
            assertEquals(ParameterAccess.READ_ARGS_VIEW, spec.access())
            val receipt = environment.install(spec)
            assertTrue(receipt.isInstalled)
            assertEquals(3, backend.call(add, null, 1, 2))
        }
    }
}

class InstallAllDslTest {
    private val add = Fixtures::class.java.getMethod("add", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

    @Test
    fun installAllBuildsEverySpecThenInstallsInOrder() {
        val backend = FakeBackend.create()
        backend.onTarget(add) { _, args -> (args[0] as Int) + (args[1] as Int) }
        HookEnvironment.create(backend).use { environment ->
            val receipts = environment.forOwner("dsl-batch").installAll {
                hook("dsl.batch.add", add) { beforeArgs { require(it.argumentCount() == 2) } }
                hook("dsl.batch.concat", method(Fixtures::class.java.name, "concat") {
                    parameters("java.lang.String", "java.lang.String")
                }) { before { } }
                hook("dsl.batch.missing", method(Fixtures::class.java.name, "absent")) { before { } }
            }
            assertEquals(
                listOf(
                    io.github.jichuo1.meguviz.hook.api.RegistrationReceipt.Status.INSTALLED,
                    io.github.jichuo1.meguviz.hook.api.RegistrationReceipt.Status.INSTALLED,
                    io.github.jichuo1.meguviz.hook.api.RegistrationReceipt.Status.TARGET_MISSING,
                ),
                receipts.map { it.status() },
            )
            assertEquals(3, backend.call(add, null, 1, 2))
        }
    }

    @Test
    fun configurationErrorInstallsNothing() {
        val backend = FakeBackend.create()
        HookEnvironment.create(backend).use { environment ->
            val failure = runCatching {
                environment.forOwner("dsl-batch").installAll {
                    hook("dsl.batch.ok", add) { before { } }
                    hook("dsl.batch.bad", add) { access(ParameterAccess.NO_ARGS); beforeArgs { } }
                }
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(0, backend.installCount())
        }
    }
}
