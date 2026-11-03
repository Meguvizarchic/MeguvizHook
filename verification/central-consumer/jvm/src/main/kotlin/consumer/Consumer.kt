package consumer

import io.github.jichuo1.meguviz.hook.core.HookEnvironment
import io.github.jichuo1.meguviz.hook.core.DiagnosticsSink
import io.github.jichuo1.meguviz.hook.api.TargetKind
import io.github.jichuo1.meguviz.hook.api.TargetSpec
import io.github.jichuo1.meguviz.hook.dsl.install
import io.github.jichuo1.meguviz.hook.dsl.member
import io.github.jichuo1.meguviz.hook.ref.MeguvizRefMemberResolver
import io.github.jichuo1.meguviz.hook.testing.FakeBackend
import io.github.jichuo1.meguviz.hook.testing.Fixtures

fun main() {
    val backend = FakeBackend.create()
    val target = Fixtures::class.java.getMethod("add", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    backend.onTarget(target) { _, args -> (args[0] as Int) + (args[1] as Int) }
    HookEnvironment.create(backend).use { env ->
        val receipt = env.install("consumer.example", "consumer", member(target)) {
            before { it.setEarlyResult(42) }
        }
        check(receipt.isInstalled)
        check(backend.call(target, null, 1, 2) == 42)
        check(receipt.registration().close().newCallbacksRejected())
    }
    val descriptor = TargetSpec.descriptor(TargetKind.METHOD, Fixtures::class.java.name, "add")
        .parameters("int", "int").returning("int").build()
    HookEnvironment.create(backend, MeguvizRefMemberResolver(), DiagnosticsSink.NONE).use { env ->
        val receipt = env.install("consumer.ref.example", "consumer", descriptor) {
            before { it.setEarlyResult(84) }
        }
        check(receipt.isInstalled)
        check(backend.call(target, null, 1, 2) == 84)
        check(receipt.registration().close().newCallbacksRejected())
    }
    println("JVM_CONSUMER_OK fake_backend_only=true ref_bridge=true")
}
