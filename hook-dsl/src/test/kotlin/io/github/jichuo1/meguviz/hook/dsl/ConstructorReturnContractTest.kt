package io.github.jichuo1.meguviz.hook.dsl

import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt
import io.github.jichuo1.meguviz.hook.core.HookEnvironment
import io.github.jichuo1.meguviz.hook.testing.FakeBackend
import io.github.jichuo1.meguviz.hook.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class ConstructorReturnContractTest {
    @Test
    fun constructorReturnTypeUsesTheSameJvmVoidContract() {
        for (returnType in listOf(null, "void", "int", "java.lang.Void")) {
            val backend = FakeBackend.create()
            HookEnvironment.create(backend).use { environment ->
                val target = constructor(Fixtures.Constructed::class.java.name) {
                    parameters("int")
                    if (returnType != null) returning(returnType)
                }
                val receipt = environment.install("constructor.dsl", "conformance", target) {
                    before { }
                }
                val matches = returnType == null || returnType == "void"
                assertEquals(
                    if (matches) RegistrationReceipt.Status.INSTALLED else RegistrationReceipt.Status.TARGET_MISSING,
                    receipt.status(),
                )
                assertEquals(if (matches) 1 else 0, backend.installCount())
            }
        }
    }
}
