package io.github.jichuo1.meguviz.hook.sample.kotlin102

import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import io.github.jichuo1.meguviz.hook.api.ParameterAccess
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt
import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102Backend
import io.github.jichuo1.meguviz.hook.backend.libxposed102.LibXposed102Context
import io.github.jichuo1.meguviz.hook.core.HookEnvironment
import io.github.jichuo1.meguviz.hook.dsl.install
import io.github.jichuo1.meguviz.hook.dsl.member
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicLong

class KotlinSampleHookEntry : XposedModule() {
    private val clockCalls = AtomicLong()
    private lateinit var environment: HookEnvironment

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.getPackageName() != EXPECTED_PACKAGE) {
            return
        }
        val backend = LibXposed102Backend(
            this,
            LibXposed102Context(param.getApplicationInfo().processName, param.getClassLoader(), 1L),
        )
        environment = HookEnvironment.create(backend)
        installClockCounter()
        installReadOnlyTextUtilsObserver()
    }

    private fun installClockCounter() {
        try {
            val target = SystemClock::class.java.getMethod("elapsedRealtime")
            val receipt = environment.install(
                "sample.kotlin.systemclock.elapsed-realtime",
                "meguvizhook-sample-kotlin-102",
                member(target),
            ) {
                access(ParameterAccess.NO_ARGS)
                before { clockCalls.incrementAndGet() }
            }
            logReceipt(receipt)
        } catch (error: NoSuchMethodException) {
            Log.w(TAG, "clock target unavailable", error)
        }
    }

    private fun installReadOnlyTextUtilsObserver() {
        try {
            val target = TextUtils::class.java.getMethod("isEmpty", CharSequence::class.java)
            val receipt = environment.install(
                "sample.kotlin.textutils.is-empty",
                "meguvizhook-sample-kotlin-102",
                member(target),
            ) {
                access(ParameterAccess.READ_ARGS)
                beforeArgs { arguments ->
                    check(arguments.argumentCount() == 1) {
                        "unexpected TextUtils.isEmpty signature"
                    }
                }
            }
            logReceipt(receipt)
        } catch (error: NoSuchMethodException) {
            Log.w(TAG, "text utils target unavailable", error)
        }
    }

    private fun logReceipt(receipt: RegistrationReceipt) {
        Log.i(TAG, "install: $receipt")
    }

    internal fun observedClockCalls(): Long = clockCalls.get()

    companion object {
        private const val TAG = "MeguvizHookKotlinSample"
        private const val EXPECTED_PACKAGE = "com.example.host"
    }
}
