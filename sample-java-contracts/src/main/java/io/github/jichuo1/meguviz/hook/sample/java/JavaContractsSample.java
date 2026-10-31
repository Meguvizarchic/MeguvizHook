package io.github.jichuo1.meguviz.hook.sample.java;

import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import io.github.jichuo1.meguviz.hook.testing.Fixtures;

import java.lang.reflect.Method;

/**
 * Java-only consumer sample on a plain JVM: no Android, Kotlin, framework, service or native code.
 * It intentionally runs against the fake backend, so it demonstrates this SDK's own contracts and
 * proves nothing about a real framework or device.
 */
public final class JavaContractsSample {
    private JavaContractsSample() {
    }

    public static void main(String[] args) throws Throwable {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment environment = HookEnvironment.create(backend);
        Method add = Fixtures.class.getMethod("add", int.class, int.class);
        backend.onTarget(add, (thisObject, callArgs) -> (Integer) callArgs[0] + (Integer) callArgs[1]);

        HookRegistration registration = installMutable(environment, add);
        Object rewritten = backend.call(add, null, 1, 2);
        require(Integer.valueOf(12).equals(rewritten), "mutable argument rewrite must reach the original", rewritten);
        require(backend.originalCallCount(add) == 1, "original must run exactly once", backend.originalCallCount(add));

        CloseReport mutableClose = registration.close();
        require(mutableClose.newCallbacksRejected(), "close must reject new callbacks", mutableClose);

        HookRegistration early = installEarlyReturn(environment, add);
        Object skipped = backend.call(add, null, 5, 6);
        require(Integer.valueOf(99).equals(skipped), "early return must replace the result", skipped);
        require(backend.originalCallCount(add) == 1, "early return must not execute the original", backend.originalCallCount(add));

        RegistrationReceipt replaced = replaceEarlyReturn(environment, add);
        Object replacedValue = backend.call(add, null, 5, 6);
        require(Integer.valueOf(100).equals(replacedValue), "replacement must swap the callback", replacedValue);
        require(backend.originalCallCount(add) == 1, "replacement must not execute the original", backend.originalCallCount(add));
        CloseReport superseded = early.close();
        require(superseded.newCallbacksRejected(), "superseded close must reject callbacks", superseded);
        Object afterOldClose = backend.call(add, null, 5, 6);
        require(Integer.valueOf(100).equals(afterOldClose), "old close must not uninstall replacement", afterOldClose);

        CloseReport closeReport = replaced.registration().close();
        require(closeReport.newCallbacksRejected(), "close must reject new callbacks", closeReport);
        environment.close();
        System.out.println("SAMPLE_OK backend=" + backend.id() + " close=" + closeReport.state());
    }

    private static HookRegistration installMutable(HookEnvironment environment, Method add) {
        HookSpec spec = new HookSpec.Builder(HookId.of("sample.java.mutable"), HookOwner.of("java-sample"),
                TargetSpec.ofMember(add))
                .access(ParameterAccess.MUTABLE_ARGS)
                .before(invocation -> ((MutableArgsInvocation) invocation).setArgument(0, 10))
                .build();
        RegistrationReceipt receipt = environment.install(spec);
        System.out.println("install mutable: " + receipt);
        require(receipt.isInstalled(), "mutable install failed", receipt);
        return receipt.registration();
    }

    private static HookRegistration installEarlyReturn(HookEnvironment environment, Method add) {
        HookSpec spec = new HookSpec.Builder(HookId.of("sample.java.early"), HookOwner.of("java-sample"),
                TargetSpec.ofMember(add))
                .before(invocation -> invocation.setEarlyResult(99))
                .build();
        RegistrationReceipt receipt = environment.install(spec);
        System.out.println("install early: " + receipt);
        require(receipt.isInstalled(), "early install failed", receipt);
        return receipt.registration();
    }

    private static RegistrationReceipt replaceEarlyReturn(HookEnvironment environment, Method add) {
        HookSpec spec = new HookSpec.Builder(HookId.of("sample.java.early"), HookOwner.of("java-sample"),
                TargetSpec.ofMember(add))
                .before(invocation -> invocation.setEarlyResult(100))
                .build();
        RegistrationReceipt receipt = environment.replace(spec);
        System.out.println("replace early: " + receipt);
        require(receipt.isInstalled(), "replacement failed", receipt);
        return receipt;
    }

    private static void require(boolean condition, String message, Object detail) {
        if (!condition) {
            throw new IllegalStateException(message + ": " + detail);
        }
    }
}
