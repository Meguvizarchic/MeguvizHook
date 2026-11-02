package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.FusedConstantDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Core contract only; backend-level sentinel disambiguation has separate proxy tests. */
public class FusedConstantChannelContractTest {
    public static Object target() { return "original"; }

    private static HookSpec spec(String id, Executable target, Object value) {
        return new HookSpec.Builder(HookId.of(id), HookOwner.of("fused-test"), TargetSpec.ofMember(target))
                .replaceConstant(value).build();
    }

    private static HookBackend capture(FakeBackend delegate, AtomicReference<DispatchChannel> captured) {
        return new HookBackend() {
            @Override public String id() { return delegate.id(); }
            @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
            @Override public BackendContext context() { return delegate.context(); }
            @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
                captured.set(channel);
                return delegate.install(plan, channel);
            }
        };
    }

    @Test public void activeFusedChannelReturnsExactObjectOrNullAndClosedChannelSignalsNotEntered() throws Exception {
        Executable target = FusedConstantChannelContractTest.class.getMethod("target");
        Object[] values = {new Object(), null};
        for (int i = 0; i < values.length; i++) {
            FakeBackend fake = FakeBackend.create();
            AtomicReference<DispatchChannel> captured = new AtomicReference<DispatchChannel>();
            HookEnvironment env = HookEnvironment.create(capture(fake, captured));
            try {
                RegistrationReceipt receipt = env.install(spec("fused.value." + i, target, values[i]));
                assertTrue(receipt.toString(), receipt.isInstalled());
                assertTrue(captured.get() instanceof FusedConstantDispatchChannel);
                FusedConstantDispatchChannel channel = (FusedConstantDispatchChannel) captured.get();
                assertFalse(channel.requiresInvocation());
                assertSame(values[i], channel.constantValue());
                assertSame(values[i], channel.dispatchValueOrNotEntered());
                assertSame(values[i], channel.dispatchValueOrNotEntered());
                assertEquals(0, receipt.registration().close().inFlightAtClose());
                assertSame(FusedConstantDispatchChannel.NOT_ENTERED, channel.dispatchValueOrNotEntered());
                assertEquals(DispatchResult.Kind.PASSTHROUGH, channel.dispatch(null).kind());
            } finally { env.close(); }
        }
    }

    @Test public void oldGenerationIsRejectedAfterReplaceWhileHeldLeaseRemainsCounted() throws Throwable {
        Executable target = FusedConstantChannelContractTest.class.getMethod("target");
        FakeBackend fake = FakeBackend.create();
        fake.onTarget(target, (receiver, arguments) -> "original");
        AtomicReference<DispatchChannel> captured = new AtomicReference<DispatchChannel>();
        HookEnvironment env = HookEnvironment.create(capture(fake, captured));
        try {
            RegistrationReceipt first = env.install(spec("fused.replace", target, "first"));
            assertTrue(first.toString(), first.isInstalled());
            HookRegistration oldRegistration = first.registration();
            FusedConstantDispatchChannel old = (FusedConstantDispatchChannel) captured.get();
            assertTrue(old.tryEnter());
            try {
                RegistrationReceipt replacement = env.replace(spec("fused.replace", target, "second"));
                assertTrue(replacement.toString(), replacement.isInstalled());
                assertEquals(1, oldRegistration.close().inFlightAtClose());
                assertSame(FusedConstantDispatchChannel.NOT_ENTERED, old.dispatchValueOrNotEntered());
                assertEquals("second", fake.call(target, null));
                assertEquals(0, fake.originalCallCount(target));
            } finally { old.exit(); }
        } finally { env.close(); }
    }
}
