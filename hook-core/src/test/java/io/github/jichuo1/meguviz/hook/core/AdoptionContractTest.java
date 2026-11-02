package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.ChainSemantics;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendAdoption;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Executable;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Tests core ownership and failure handling, not framework lifecycle or a real device. */
public final class AdoptionContractTest {
    public static int target() { return 0; }

    private static HookSpec spec(String id) throws Exception {
        Method target = AdoptionContractTest.class.getMethod("target");
        return new HookSpec.Builder(HookId.of(id), HookOwner.of("adoption-test"), TargetSpec.ofMember(target))
                .before(invocation -> invocation.setEarlyResult(42)).build();
    }

    private static final class Backend implements HookBackend, BackendContext {
        final AtomicInteger installs = new AtomicInteger();
        long epoch = 1;
        @Override public String id() { return "adoption-test"; }
        @Override public BackendCapabilities capabilities() {
            return new BackendCapabilities.Builder(id(), ChainSemantics.FRAMEWORK_CHAIN).build();
        }
        @Override public BackendContext context() { return this; }
        @Override public String processName() { return "test"; }
        @Override public ClassLoader classLoader() { return AdoptionContractTest.class.getClassLoader(); }
        @Override public long codeEpoch() { return epoch; }
        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) {
            installs.incrementAndGet();
            throw new AssertionError("adoption must not install a second hook");
        }
    }

    private static BackendHandle handle(AtomicInteger closes) {
        return () -> {
            closes.incrementAndGet();
            return new BackendCloseReport(CloseReport.EntryRestoration.RESTORED,
                    CloseReport.PhysicalRelease.NOT_CLAIMED, false, "removed");
        };
    }

    @FunctionalInterface
    private interface Attempt {
        BackendHandle replace(BoundPlan plan, DispatchChannel channel) throws BackendInstallException;
    }

    private static BackendAdoption adoption(Backend backend, Attempt attempt) {
        return new BackendAdoption() {
            @Override public HookBackend backend() { return backend; }
            @Override public BackendHandle replace(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
                return attempt.replace(plan, channel);
            }
        };
    }

    @Test public void adoptionPublishesReturnedOwnershipWithoutInstalling() throws Exception {
        Backend backend = new Backend();
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicInteger replaces = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<DispatchChannel> captured = new AtomicReference<DispatchChannel>();
        Method target = AdoptionContractTest.class.getMethod("target");
        RegistrationReceipt receipt = env.adopt(spec("adopt.ok"), adoption(backend, (plan, channel) -> {
            replaces.incrementAndGet();
            assertEquals(target, plan.target());
            assertSame(backend.classLoader(), plan.loader());
            captured.set(channel);
            return handle(closes);
        }));
        assertTrue(receipt.toString(), receipt.isInstalled());
        assertEquals(1, replaces.get());
        assertEquals(0, backend.installs.get());
        DispatchResult result = captured.get().dispatch(new BackendInvocation() {
            @Override public Executable target() { return target; }
            @Override public Object thisObject() { return null; }
            @Override public Object[] arguments() { return new Object[0]; }
            @Override public Object proceed(Object[] arguments) { return 0; }
        });
        assertEquals(DispatchResult.Kind.RETURNED, result.kind());
        assertEquals(42, result.value());
        assertEquals(RegistrationReceipt.Status.CONFLICT,
                env.adopt(spec("adopt.ok"), adoption(backend, (plan, channel) -> { fail("duplicate reached bridge"); return null; })).status());
        receipt.registration().close();
        env.close();
        assertEquals(1, closes.get());
    }

    @Test public void checkedFailureDoesNotPublishOrRetryTheBridge() throws Exception {
        Backend backend = new Backend();
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicInteger calls = new AtomicInteger();
        RegistrationReceipt receipt = env.adopt(spec("adopt.failure"), adoption(backend, (plan, channel) -> {
            calls.incrementAndGet();
            throw new BackendInstallException("uncommitted");
        }));
        assertEquals(RegistrationReceipt.Status.FAILED, receipt.status());
        assertNull(receipt.registration());
        assertEquals(1, calls.get());
        assertEquals(0, env.activeRegistrationCount());
        env.close();
    }

    @Test public void unknownOutcomeRetiresNewChannelAndQuarantinesId() throws Exception {
        Backend backend = new Backend();
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicReference<DispatchChannel> captured = new AtomicReference<DispatchChannel>();
        try {
            env.adopt(spec("adopt.unknown"), adoption(backend, (plan, channel) -> {
                captured.set(channel);
                throw new IllegalStateException("commit unknown");
            }));
            fail("unknown outcome must throw");
        } catch (IllegalStateException expected) {
            assertEquals("commit unknown", expected.getMessage());
        }
        assertEquals(DispatchResult.Kind.PASSTHROUGH, captured.get().dispatch(null).kind());
        assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                env.adopt(spec("adopt.unknown"), adoption(backend, (plan, channel) -> { fail("quarantined id reached bridge"); return null; })).status());
        env.close();
    }

    @Test public void allCannotInvokeABridgeAndEpochChangeQuarantinesCommittedHandle() throws Exception {
        Backend backend = new Backend();
        HookEnvironment env = HookEnvironment.create(backend);
        TargetSpec all = TargetSpec.descriptor(TargetKind.METHOD, AdoptionContractTest.class.getName(), "target")
                .match(MatchPolicy.ALL).build();
        HookSpec multi = new HookSpec.Builder(HookId.of("adopt.all"), HookOwner.of("adoption-test"), all)
                .before(invocation -> invocation.setEarlyResult(42)).build();
        assertEquals(RegistrationReceipt.Status.CONFLICT,
                env.adopt(multi, adoption(backend, (plan, channel) -> { fail("ALL reached bridge"); return null; })).status());
        AtomicInteger closes = new AtomicInteger();
        RegistrationReceipt stale = env.adopt(spec("adopt.stale"), adoption(backend, (plan, channel) -> {
            backend.epoch++;
            return handle(closes);
        }));
        assertEquals(RegistrationReceipt.Status.STALE_IDENTITY, stale.status());
        assertTrue(stale.message().contains("old hook cannot be restored"));
        assertEquals(0, closes.get());
        assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                env.adopt(spec("adopt.stale"), adoption(backend, (plan, channel) -> { fail("quarantined id reached bridge"); return null; })).status());
        env.close();
        assertEquals(0, closes.get());
    }

    @Test public void foreignBackendCannotCommit() throws Exception {
        Backend backend = new Backend();
        HookEnvironment env = HookEnvironment.create(backend);
        RegistrationReceipt receipt = env.adopt(spec("adopt.foreign"), adoption(new Backend(), (plan, channel) -> {
            fail("foreign bridge reached");
            return null;
        }));
        assertEquals(RegistrationReceipt.Status.CONFLICT, receipt.status());
        env.close();
    }

    @Test public void publicationFailureRetainsCommittedHandleBeforeAndAfterMapWrite() throws Exception {
        for (boolean writeFirst : new boolean[]{false, true}) {
            Backend backend = new Backend();
            HookEnvironment env = HookEnvironment.create(backend);
            AtomicInteger closes = new AtomicInteger();
            AtomicReference<DispatchChannel> captured = new AtomicReference<DispatchChannel>();
            Field field = HookEnvironment.class.getDeclaredField("registrations");
            field.setAccessible(true);
            field.set(env, new FaultMap(writeFirst));
            try {
                env.adopt(spec("adopt.publish"), adoption(backend, (plan, channel) -> {
                    captured.set(channel);
                    return handle(closes);
                }));
                fail("publication should fail");
            } catch (IllegalStateException expected) {
                assertEquals("publish", expected.getMessage());
            }
            assertEquals(DispatchResult.Kind.PASSTHROUGH, captured.get().dispatch(null).kind());
            assertEquals(0, closes.get());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                    env.adopt(spec("adopt.publish"), adoption(backend, (plan, channel) -> {
                        fail("quarantined id reached bridge");
                        return null;
                    })).status());
            env.close();
            assertEquals(0, closes.get());
        }
    }

    private static final class FaultMap extends LinkedHashMap<HookId, CoreRegistration> {
        private static final long serialVersionUID = 1L;
        private final boolean writeFirst;
        FaultMap(boolean writeFirst) { this.writeFirst = writeFirst; }
        @Override public CoreRegistration put(HookId key, CoreRegistration value) {
            if (writeFirst) super.put(key, value);
            throw new IllegalStateException("publish");
        }
    }
}
