package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchObserver;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.ConditionalDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Old-backend fallback and registration contract, not an ART or framework performance test. */
public class ConditionalStringReplacementTest {
    public static final class Targets {
        public static boolean query(String key) { return false; }
        public static String text(String key) { return "original"; }
        public static boolean wrongArgument(Object key) { return false; }
        public static boolean overloaded(String key) { return false; }
        public static boolean overloaded(Object key) { return false; }
    }

    private static Executable query() throws Exception { return Targets.class.getMethod("query", String.class); }
    private static HookSpec.Builder spec(String id, Executable target) {
        return new HookSpec.Builder(HookId.of(id), HookOwner.of("conditional-test"), TargetSpec.ofMember(target));
    }
    private static HookSpec conditional(String id, Executable target, String expected, MatchObserver observer) {
        return spec(id, target).replaceIfStringArgumentEquals(0, expected, Boolean.TRUE, observer).build();
    }

    @Test public void oldBackendFallbackMatchesOrProceedsExactlyOnce() throws Throwable {
        Executable target = query();
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(target, (self, args) -> Boolean.FALSE);
        HookEnvironment env = HookEnvironment.create(backend);
        List<String> events = new ArrayList<String>();
        try {
            RegistrationReceipt receipt = env.install(conditional("conditional.fallback", target, "hit", () -> events.add("observed")));
            assertTrue(receipt.toString(), receipt.isInstalled());
            assertEquals(Boolean.TRUE, backend.call(target, null, "hit"));
            assertEquals(Arrays.asList("observed"), events);
            assertEquals(0, backend.originalCallCount(target));
            assertEquals(Boolean.FALSE, backend.call(target, null, "miss"));
            assertEquals(1, backend.originalCallCount(target));
            assertEquals(Arrays.asList("observed"), events);
            assertEquals("the legacy backend supplies its array through the default fallback", 2,
                    backend.argumentMaterializationCount());
        } finally { env.close(); }
    }

    @Test public void nullAndMissingArgumentsUseArgOrNullSemantics() throws Throwable {
        Executable target = query();
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(target, (self, args) -> Boolean.FALSE);
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicInteger matches = new AtomicInteger();
        try {
            assertTrue(env.install(conditional("conditional.null", target, null, matches::incrementAndGet)).isInstalled());
            assertEquals(Boolean.TRUE, backend.call(target, null, (Object) null));
            assertEquals(Boolean.TRUE, backend.call(target, null, new Object[0]));
            assertEquals(Boolean.FALSE, backend.call(target, null, "different"));
            assertEquals(2, matches.get());
            assertEquals(1, backend.originalCallCount(target));
        } finally { env.close(); }
    }

    @Test public void matchedNullableReferenceConstantIsNotMistakenForMiss() throws Throwable {
        Executable target = Targets.class.getMethod("text", String.class);
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(target, (self, args) -> "original");
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            HookSpec hook = spec("conditional.nullresult", target)
                    .replaceIfStringArgumentEquals(0, "hit", null).build();
            assertTrue(env.install(hook).isInstalled());
            assertNull(backend.call(target, null, "hit"));
            assertEquals(0, backend.originalCallCount(target));
            assertEquals("original", backend.call(target, null, "miss"));
            assertEquals(1, backend.originalCallCount(target));
        } finally { env.close(); }
    }

    @Test public void equalsUsesActualFirstAndFailuresRetainIdentity() throws Throwable {
        Executable target = query();
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(target, (self, args) -> Boolean.FALSE);
        HookEnvironment env = HookEnvironment.create(backend);
        RuntimeException equalsFailure = new RuntimeException("equals");
        Object badValue = new Object() {
            @Override public boolean equals(Object other) { throw equalsFailure; }
            @Override public int hashCode() { return 0; }
        };
        SyntheticVmError fatalEquals = new SyntheticVmError();
        Object fatalValue = new Object() {
            @Override public boolean equals(Object other) { throw fatalEquals; }
            @Override public int hashCode() { return 0; }
        };
        AtomicInteger matches = new AtomicInteger();
        Object asymmetric = new Object() {
            @Override public boolean equals(Object other) { return "hit".equals(other); }
            @Override public int hashCode() { return 0; }
        };
        try {
            assertTrue(env.install(conditional("conditional.equals", target, "hit", matches::incrementAndGet)).isInstalled());
            // FakeBackend deliberately permits an invalid String slot to expose equality direction.
            assertEquals(Boolean.TRUE, backend.call(target, null, asymmetric));
            Throwable observed = capture(() -> backend.call(target, null, badValue));
            assertSame(equalsFailure, observed);
            assertSame(fatalEquals, capture(() -> backend.call(target, null, fatalValue)));
            assertEquals(1, matches.get());
            assertEquals(0, backend.originalCallCount(target));
        } finally { env.close(); }
    }

    @Test public void observerAndOriginalThrowablesIncludingFatalKeepIdentity() throws Throwable {
        Executable target = query();
        Throwable[] failures = {new IllegalStateException("observer"), new Exception("checked observer"), new SyntheticVmError()};
        for (Throwable failure : failures) {
            FakeBackend backend = FakeBackend.create();
            backend.onTarget(target, (self, args) -> Boolean.FALSE);
            HookEnvironment env = HookEnvironment.create(backend);
            try {
                assertTrue(env.install(conditional("conditional.throw", target, "hit", () -> { throw failure; })).isInstalled());
                assertSame(failure, capture(() -> backend.call(target, null, "hit")));
                assertEquals(0, backend.originalCallCount(target));
            } finally { env.close(); }
        }
        Throwable[] downstream = {new IllegalArgumentException("original"), new SyntheticVmError()};
        for (Throwable failure : downstream) {
            FakeBackend backend = FakeBackend.create();
            backend.onTarget(target, (self, args) -> { throw failure; });
            HookEnvironment env = HookEnvironment.create(backend);
            try {
                assertTrue(env.install(conditional("conditional.original", target, "hit", null)).isInstalled());
                assertSame(failure, capture(() -> backend.call(target, null, "miss")));
                assertEquals(1, backend.originalCallCount(target));
            } finally { env.close(); }
        }
    }

    @Test public void observerCanCloseWhileItsMatchedLeaseIsCounted() throws Throwable {
        Executable target = query();
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(target, (self, args) -> Boolean.FALSE);
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicReference<HookRegistration> registration = new AtomicReference<HookRegistration>();
        AtomicReference<CloseReport> close = new AtomicReference<CloseReport>();
        try {
            RegistrationReceipt receipt = env.install(conditional("conditional.close", target, "hit", () -> close.set(registration.get().close())));
            assertTrue(receipt.toString(), receipt.isInstalled());
            registration.set(receipt.registration());
            assertEquals(Boolean.TRUE, backend.call(target, null, "hit"));
            assertEquals(1, close.get().inFlightAtClose());
            assertEquals(0, backend.originalCallCount(target));
            assertEquals(Boolean.FALSE, backend.call(target, null, "hit"));
            assertEquals(1, backend.originalCallCount(target));
        } finally { env.close(); }
    }

    @Test public void observerCanReplaceWithoutReplayingOrInvalidatingItsOwnResult() throws Throwable {
        Executable target = query();
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(target, (self, args) -> Boolean.FALSE);
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicReference<RegistrationReceipt> replacement = new AtomicReference<RegistrationReceipt>();
        try {
            HookSpec original = conditional("conditional.reentry", target, "hit", () ->
                    replacement.set(env.replace(spec("conditional.reentry", target).replaceConstant(false).build())));
            HookRegistration old = env.install(original).registration();
            assertNotNull(old);
            assertEquals("the accepted old generation finishes with its own result", Boolean.TRUE,
                    backend.call(target, null, "hit"));
            assertTrue(replacement.get().toString(), replacement.get().isInstalled());
            assertEquals(0, backend.originalCallCount(target));
            old.close();
            assertEquals(Boolean.FALSE, backend.call(target, null, "hit"));
            assertEquals(0, backend.originalCallCount(target));
            replacement.get().registration().close();
        } finally { env.close(); }
    }

    @Test public void missLeaseEncompassesTheBlockedOriginalDuringClose() throws Throwable {
        Executable target = query();
        FakeBackend backend = FakeBackend.create();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        backend.onTarget(target, (self, args) -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("original timed out");
            return Boolean.FALSE;
        });
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicReference<Object> value = new AtomicReference<Object>();
        AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Thread worker = null;
        try {
            HookRegistration registration = env.install(conditional("conditional.miss", target, "hit", null)).registration();
            assertNotNull(registration);
            worker = new Thread(() -> {
                try { value.set(backend.call(target, null, "miss")); }
                catch (Throwable failure) { error.set(failure); }
            });
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CloseReport report = registration.close();
            assertEquals(1, report.inFlightAtClose());
            release.countDown();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertNull(error.get());
            assertEquals(Boolean.FALSE, value.get());
            assertEquals(1, backend.originalCallCount(target));
        } finally {
            release.countDown();
            if (worker != null) worker.join(5000);
            env.close();
        }
    }

    @Test public void invalidShapesAndAllTargetTypeMismatchAreRejectedBeforeInstall() throws Exception {
        Executable target = query();
        assertThrows(IllegalArgumentException.class, () -> spec("conditional.negative", target)
                .replaceIfStringArgumentEquals(-1, "hit", true).build());
        assertThrows(IllegalArgumentException.class, () -> spec("conditional.before", target)
                .replaceIfStringArgumentEquals(0, "hit", true).before(i -> { }).build());
        assertThrows(IllegalArgumentException.class, () -> spec("conditional.after", target)
                .replaceIfStringArgumentEquals(0, "hit", true).after((i, r) -> { }).build());
        assertThrows(IllegalArgumentException.class, () -> spec("conditional.constant", target)
                .replaceConstant(true).replaceIfStringArgumentEquals(0, "hit", true).build());
        assertThrows(IllegalArgumentException.class, () -> spec("conditional.shape", target)
                .replaceIfStringArgumentEquals(0, "hit", true).access(ParameterAccess.NO_ARGS).build());
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            assertEquals(RegistrationReceipt.Status.FAILED, env.install(spec("conditional.index", target)
                    .replaceIfStringArgumentEquals(1, "hit", true).build()).status());
            assertEquals(RegistrationReceipt.Status.FAILED, env.install(spec("conditional.type", Targets.class.getMethod("wrongArgument", Object.class))
                    .replaceIfStringArgumentEquals(0, "hit", true).build()).status());
            assertEquals(RegistrationReceipt.Status.FAILED, env.install(spec("conditional.result", target)
                    .replaceIfStringArgumentEquals(0, "hit", "wrong").build()).status());
            TargetSpec all = TargetSpec.descriptor(TargetKind.METHOD, Targets.class.getName(), "overloaded")
                    .match(MatchPolicy.ALL).build();
            HookSpec mixed = new HookSpec.Builder(HookId.of("conditional.all"), HookOwner.of("conditional-test"), all)
                    .replaceIfStringArgumentEquals(0, "hit", true).build();
            assertEquals(RegistrationReceipt.Status.FAILED, env.install(mixed).status());
            assertEquals("ALL must validate every candidate before touching the backend", 0, backend.installCount());
        } finally { env.close(); }
    }

    @Test public void conditionalOrdinaryAndConstantGenerationsReplaceSafely() throws Throwable {
        Executable target = query();
        FakeBackend backend = FakeBackend.create();
        backend.onTarget(target, (self, args) -> Boolean.FALSE);
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            MatchObserver observer = () -> { };
            HookSpec firstSpec = conditional("conditional.generations", target, new String("hit"), observer);
            HookRegistration first = env.install(firstSpec).registration();
            assertNotNull(first);
            assertEquals(RegistrationReceipt.Status.ALREADY_INSTALLED,
                    env.replace(conditional("conditional.generations", target, new String("hit"), observer)).status());
            RegistrationReceipt second = env.replace(spec("conditional.generations", target).before(i -> i.setEarlyResult(false)).build());
            assertTrue(second.toString(), second.isInstalled());
            first.close();
            assertEquals(Boolean.FALSE, backend.call(target, null, "hit"));
            RegistrationReceipt third = env.replace(spec("conditional.generations", target).replaceConstant(true).build());
            assertTrue(third.toString(), third.isInstalled());
            second.registration().close();
            assertEquals(Boolean.TRUE, backend.call(target, null, "miss"));
            RegistrationReceipt fourth = env.replace(conditional("conditional.generations", target, "hit", null));
            assertTrue(fourth.toString(), fourth.isInstalled());
            third.registration().close();
            assertEquals(Boolean.TRUE, backend.call(target, null, "hit"));
            assertEquals(Boolean.FALSE, backend.call(target, null, "miss"));
            fourth.registration().close();
        } finally { env.close(); }
    }

    @Test public void unknownCommittedReplacementQuarantinesOldAndNewConditionalChannels() throws Throwable {
        Executable target = query();
        FakeBackend delegate = FakeBackend.create();
        delegate.onTarget(target, (self, args) -> Boolean.FALSE);
        AtomicReference<DispatchChannel> oldChannel = new AtomicReference<DispatchChannel>();
        AtomicReference<DispatchChannel> newChannel = new AtomicReference<DispatchChannel>();
        AtomicInteger unhooks = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("outcome unknown");
        HookBackend backend = new HookBackend() {
            @Override public String id() { return delegate.id(); }
            @Override public BackendCapabilities capabilities() { return delegate.capabilities(); }
            @Override public BackendContext context() { return delegate.context(); }
            @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
                oldChannel.set(channel);
                BackendHandle handle = delegate.install(plan, channel);
                return new BackendHandle() {
                    @Override public BackendCloseReport uninstall() { unhooks.incrementAndGet(); return handle.uninstall(); }
                    @Override public BackendHandle replace(BoundPlan plan, DispatchChannel next) throws BackendInstallException {
                        newChannel.set(next);
                        handle.replace(plan, next);
                        throw failure;
                    }
                };
            }
        };
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            HookSpec first = conditional("conditional.unknown", target, "first", null);
            HookSpec next = conditional("conditional.unknown", target, "next", null);
            HookRegistration old = env.install(first).registration();
            assertNotNull(old);
            assertSame(failure, capture(() -> env.replace(next)));
            assertEquals(RegistrationState.RETIRED_RESIDENT, old.state());
            assertFalse(((ConditionalDispatchChannel) oldChannel.get()).tryEnter());
            assertFalse(((ConditionalDispatchChannel) newChannel.get()).tryEnter());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, env.install(first).status());
            assertEquals(0, unhooks.get());
            assertEquals(Boolean.FALSE, delegate.call(target, null, "next"));
        } finally { env.close(); }
        assertEquals(0, unhooks.get());
    }

    @FunctionalInterface private interface Throwing { void run() throws Throwable; }
    private static Throwable capture(Throwing action) {
        try { action.run(); fail("expected throwable"); return null; }
        catch (Throwable failure) { return failure; }
    }
    private static final class SyntheticVmError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}
