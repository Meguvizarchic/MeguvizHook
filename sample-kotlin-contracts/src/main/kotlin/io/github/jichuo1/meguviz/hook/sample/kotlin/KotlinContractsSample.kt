package io.github.jichuo1.meguviz.hook.sample.kotlin

import io.github.jichuo1.meguviz.hook.core.HookEnvironment
import io.github.jichuo1.meguviz.hook.dsl.install
import io.github.jichuo1.meguviz.hook.dsl.member
import io.github.jichuo1.meguviz.hook.dsl.method
import io.github.jichuo1.meguviz.hook.dsl.replace
import io.github.jichuo1.meguviz.hook.testing.FakeBackend
import io.github.jichuo1.meguviz.hook.testing.Fixtures

fun main() {
    val backend = FakeBackend.create()
    HookEnvironment.create(backend).use { environment ->
        val add = Fixtures::class.java.getMethod(
            "add",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        backend.onTarget(add) { _, arguments ->
            (arguments[0] as Int) + (arguments[1] as Int)
        }

        val descriptor = method(Fixtures::class.java.name, "add") {
            parameters("int", "int")
            returning("int")
        }
        val mutable = environment.install("sample.kotlin.mutable", "kotlin-sample", descriptor) {
            beforeMutableArgs { invocation -> invocation.setArgument(0, 10) }
        }
        println("install mutable: $mutable")
        check(mutable.isInstalled) { "mutable install failed: $mutable" }
        check(backend.call(add, null, 1, 2) == 12) { "mutable argument rewrite must reach the original" }
        check(backend.originalCallCount(add) == 1) { "original must run exactly once" }
        check(mutable.registration().close().newCallbacksRejected()) { "close must reject new callbacks" }

        val early = environment.install("sample.kotlin.early", "kotlin-sample", member(add)) {
            before { invocation -> invocation.setEarlyResult(99) }
        }
        println("install early: $early")
        check(early.isInstalled) { "early install failed: $early" }
        check(backend.call(add, null, 5, 6) == 99) { "early return must replace the result" }
        check(backend.originalCallCount(add) == 1) { "early return must not execute the original" }

        val replaced = environment.replace("sample.kotlin.early", "kotlin-sample", member(add)) {
            before { invocation -> invocation.setEarlyResult(100) }
        }
        println("replace early: $replaced")
        check(replaced.isInstalled) { "replacement failed: $replaced" }
        check(backend.call(add, null, 5, 6) == 100) { "replacement must swap the callback" }
        check(backend.originalCallCount(add) == 1) { "replacement must not execute the original" }
        val superseded = early.registration().close()
        check(superseded.newCallbacksRejected()) { "superseded close must reject callbacks" }
        check(backend.call(add, null, 5, 6) == 100) { "old close must not uninstall replacement" }

        val closeReport = replaced.registration().close()
        check(closeReport.newCallbacksRejected()) { "close must reject new callbacks" }
        println("KOTLIN_SAMPLE_OK backend=${backend.id()} close=${closeReport.state()}")
    }
}
