package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.ConstantDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** JVM fake backend contract checks; these do not establish device behavior or speed. */
public class ConstantReplacementTest {
    public static final class Targets {
        public Targets() { }
        public static int integer(int ignored) { return ignored; }
        public static String text() { return "original"; }
        public static void nothing() { }
        public static int overloaded() { return 1; }
        public static String overloaded(int ignored) { return "original"; }
    }

    private static HookSpec.Builder spec(String id, Executable target) {
        return new HookSpec.Builder(HookId.of(id), HookOwner.of("constant-test"), TargetSpec.ofMember(target));
    }

    @Test public void constantReturnsExactReferenceWithoutArgsOrOriginalAndCloses() throws Throwable {
        Executable target = Targets.class.getMethod("integer", int.class);
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(target, (self, args) -> 3);
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            Integer value = Integer.valueOf(73);
            RegistrationReceipt receipt = env.install(spec("constant.primitive", target).replaceConstant(value).build());
            assertTrue(receipt.toString(), receipt.isInstalled());
            assertSame(value, backend.call(target, null, 5));
            assertEquals(0, backend.originalCallCount(target));
            assertEquals(0, backend.argumentMaterializationCount());
            receipt.registration().close();
            assertEquals(3, backend.call(target, null, 5));
            assertEquals(1, backend.originalCallCount(target));
        } finally { env.close(); }
    }

    @Test public void nullAndVoidResultsAreAllowedButWrongTypesAreRejectedBeforeInstall() throws Exception {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            Executable integer = Targets.class.getMethod("integer", int.class);
            Executable text = Targets.class.getMethod("text");
            Executable nothing = Targets.class.getMethod("nothing");
            Executable constructor = Targets.class.getConstructor();
            assertEquals(RegistrationReceipt.Status.FAILED,
                    env.install(spec("constant.nullprimitive", integer).replaceConstant(null).build()).status());
            assertEquals(RegistrationReceipt.Status.FAILED,
                    env.install(spec("constant.wrongprimitive", integer).replaceConstant(Long.valueOf(3)).build()).status());
            assertEquals(RegistrationReceipt.Status.FAILED,
                    env.install(spec("constant.wrongreference", text).replaceConstant(Integer.valueOf(3)).build()).status());
            assertEquals(RegistrationReceipt.Status.FAILED,
                    env.install(spec("constant.wrongvoid", nothing).replaceConstant("bad").build()).status());
            assertEquals(RegistrationReceipt.Status.FAILED,
                    env.install(spec("constant.wrongconstructor", constructor).replaceConstant("bad").build()).status());
            assertEquals(0, backend.installCount());
            assertTrue(env.install(spec("constant.nullref", text).replaceConstant(null).build()).isInstalled());
            assertTrue(env.install(spec("constant.nullvoid", nothing).replaceConstant(null).build()).isInstalled());
            assertTrue(env.install(spec("constant.nullconstructor", constructor).replaceConstant(null).build()).isInstalled());
        } finally { env.close(); }
    }

    @Test public void constantReplacementTransfersOwnershipAndUsesResultIdentity() throws Throwable {
        Executable target = Targets.class.getMethod("text");
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(target, (self, args) -> "original");
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            String first = new String("equal");
            String second = new String("equal");
            HookSpec firstSpec = spec("constant.replace", target).replaceConstant(first).build();
            HookRegistration old = env.install(firstSpec).registration();
            assertNotNull(old);
            assertEquals(RegistrationReceipt.Status.ALREADY_INSTALLED, env.replace(firstSpec).status());
            RegistrationReceipt replaced = env.replace(spec("constant.replace", target).replaceConstant(second).build());
            assertTrue(replaced.toString(), replaced.isInstalled());
            assertSame(second, backend.call(target, null));
            old.close();
            assertSame(second, backend.call(target, null));
            replaced.registration().close();
            assertEquals("original", backend.call(target, null));
        } finally { env.close(); }
    }

    @Test public void incompatibleReplacementDoesNotDisturbOldHook() throws Throwable {
        Executable target = Targets.class.getMethod("text");
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(target, (self, args) -> "original");
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            assertTrue(env.install(spec("constant.invalidreplace", target).replaceConstant("old").build()).isInstalled());
            assertEquals(RegistrationReceipt.Status.FAILED,
                    env.replace(spec("constant.invalidreplace", target).replaceConstant(Integer.valueOf(9)).build()).status());
            assertEquals(0, backend.replaceCount());
            assertEquals("old", backend.call(target, null));
        } finally { env.close(); }
    }

    @Test public void constantModeRejectsCallbacksAndArgumentShapes() throws Exception {
        Executable target = Targets.class.getMethod("text");
        assertThrows(IllegalArgumentException.class,
                () -> spec("constant.before", target).replaceConstant("").before(i -> { }).build());
        assertThrows(IllegalArgumentException.class,
                () -> spec("constant.after", target).replaceConstant("").after((i, r) -> { }).build());
        assertThrows(IllegalArgumentException.class,
                () -> spec("constant.args", target).replaceConstant("").access(ParameterAccess.READ_ARGS).build());
        assertThrows(IllegalArgumentException.class,
                () -> spec("constant.around", target).replaceConstant("").around(i -> { }).build());
    }

    @Test public void allTargetsAreTypeCheckedBeforeAnyIsInstalled() {
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            TargetSpec target = TargetSpec.descriptor(TargetKind.METHOD, Targets.class.getName(), "overloaded")
                    .match(MatchPolicy.ALL).build();
            HookSpec spec = new HookSpec.Builder(HookId.of("constant.all"), HookOwner.of("constant-test"), target)
                    .replaceConstant(Integer.valueOf(1)).build();
            assertEquals(RegistrationReceipt.Status.FAILED, env.install(spec).status());
            assertEquals(0, backend.installCount());
        } finally { env.close(); }
    }

    @Test public void ordinaryAndConstantGenerationsSwitchBothWays() throws Throwable {
        Executable target = Targets.class.getMethod("text");
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(target, (self, args) -> "original");
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            HookRegistration first = env.install(spec("constant.switch", target)
                    .before(i -> i.setEarlyResult("callback")).build()).registration();
            assertNotNull(first);
            assertEquals("callback", backend.call(target, null));
            RegistrationReceipt second = env.replace(spec("constant.switch", target).replaceConstant("fixed").build());
            assertTrue(second.toString(), second.isInstalled());
            assertEquals("fixed", backend.call(target, null));
            first.close();
            RegistrationReceipt third = env.replace(spec("constant.switch", target)
                    .before(i -> i.setEarlyResult("callback-again")).build());
            assertTrue(third.toString(), third.isInstalled());
            second.registration().close();
            assertEquals("callback-again", backend.call(target, null));
            third.registration().close();
            assertEquals("original", backend.call(target, null));
        } finally { env.close(); }
    }

    @Test public void thirdPartyChannelDefaultsToRequiringInvocation() {
        DispatchChannel channel = invocation -> { throw new AssertionError("unused"); };
        assertTrue(channel.requiresInvocation());
    }

    @Test public void directConstantLeaseCountsAtCloseAndKeepsNullResult() throws Exception {
        Executable target = Targets.class.getMethod("text");
        FakeBackend delegate = FakeBackend.create();
        AtomicReference<DispatchChannel> captured = new AtomicReference<DispatchChannel>();
        HookBackend backend = new HookBackend() {
            @Override public String id() { return delegate.id(); }
            @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
            @Override public BackendContext context() { return delegate.context(); }
            @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
                captured.set(channel);
                return delegate.install(plan, channel);
            }
        };
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            RegistrationReceipt receipt = env.install(spec("constant.direct.lease", target).replaceConstant(null).build());
            assertTrue(receipt.toString(), receipt.isInstalled());
            assertTrue(captured.get() instanceof ConstantDispatchChannel);
            ConstantDispatchChannel channel = (ConstantDispatchChannel) captured.get();
            assertFalse(channel.requiresInvocation());
            assertNull(channel.constantValue());
            assertTrue(channel.tryEnter());
            try {
                assertEquals(1, receipt.registration().close().inFlightAtClose());
                assertFalse(channel.tryEnter());
            } finally {
                channel.exit();
            }
            assertEquals(DispatchResult.Kind.PASSTHROUGH, channel.dispatch(null).kind());
        } finally { env.close(); }
    }

    @Test public void capturedConstantChannelAllowsNullAndOldSnapshotPassesThroughAfterClose() throws Exception {
        Executable target = Targets.class.getMethod("text");
        FakeBackend delegate = FakeBackend.create();
        AtomicReference<DispatchChannel> captured = new AtomicReference<DispatchChannel>();
        HookBackend backend = new HookBackend() {
            @Override public String id() { return delegate.id(); }
            @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
            @Override public BackendContext context() { return delegate.context(); }
            @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
                captured.set(channel);
                return delegate.install(plan, channel);
            }
        };
        HookEnvironment env = HookEnvironment.create(backend);
        CountDownLatch selected = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<DispatchResult> result = new AtomicReference<DispatchResult>();
        Thread thread = null;
        try {
            RegistrationReceipt receipt = env.install(spec("constant.snapshot", target).replaceConstant("fixed").build());
            assertTrue(receipt.toString(), receipt.isInstalled());
            DispatchChannel old = captured.get();
            assertFalse(old.requiresInvocation());
            DispatchResult firstResult = old.dispatch(null);
            assertEquals("fixed", firstResult.value());
            assertSame("an active constant channel reuses its prebuilt result", firstResult, old.dispatch(null));
            thread = new Thread(() -> {
                selected.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out");
                    result.set(old.dispatch(null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            thread.start();
            assertTrue(selected.await(5, TimeUnit.SECONDS));
            receipt.registration().close();
            release.countDown();
            thread.join(5000L);
            assertFalse(thread.isAlive());
            assertNotNull(result.get());
            assertEquals(DispatchResult.Kind.PASSTHROUGH, result.get().kind());
            assertFalse(old.requiresInvocation());
        } finally {
            release.countDown();
            if (thread != null) thread.join(5000L);
            env.close();
        }
    }
}
