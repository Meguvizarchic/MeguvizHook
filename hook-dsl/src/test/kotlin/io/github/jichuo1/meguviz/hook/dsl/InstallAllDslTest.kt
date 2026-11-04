package io.github.jichuo1.meguviz.hook.dsl

import io.github.jichuo1.meguviz.hook.api.ParameterAccess
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt
import io.github.jichuo1.meguviz.hook.core.HookEnvironment
import io.github.jichuo1.meguviz.hook.testing.FakeBackend
import io.github.jichuo1.meguviz.hook.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
                    RegistrationReceipt.Status.INSTALLED,
                    RegistrationReceipt.Status.INSTALLED,
                    RegistrationReceipt.Status.TARGET_MISSING,
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

    @Test
    fun escapedCollectorRejectsLateHooks() {
        HookEnvironment.create(FakeBackend.create()).use { environment ->
            var escaped: HookBatchDsl? = null
            environment.forOwner("dsl-batch").installAll { escaped = this }
            val failure = runCatching { escaped!!.hook("dsl.batch.late", add) { before { } } }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        }
    }
}
