package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.CapabilityResult;
import io.github.jichuo1.meguviz.hook.api.ChainSemantics;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** A failed pre-anchor cannot be treated as a committed backend replacement. */
public final class ReplacementPreAnchorFailureTest {
    public static int target() { return 0; }

    @Test public void beforeAndAfterAnchorWritePreserveOldHandleAndOriginalFailure() throws Exception {
        for (boolean writeFirst : new boolean[] {false, true}) {
            for (boolean fatal : new boolean[] {false, true}) {
                Throwable failure = fatal ? new OutOfMemoryError("simulated anchor failure")
                        : new IllegalStateException("simulated anchor failure");
                Backend backend = new Backend();
                HookEnvironment environment = HookEnvironment.create(backend);
                HookSpec oldSpec = spec(1);
                HookSpec newSpec = spec(2);
                try {
                    RegistrationReceipt first = environment.install(oldSpec);
                    assertTrue(first.toString(), first.isInstalled());
                    HookRegistration old = first.registration();
                    FaultMap anchor = inject(environment, failure, writeFirst);

                    assertSame(failure, assertThrows(Throwable.class, () -> environment.replace(newSpec)));
                    assertEquals("backend must not have been called", 0, backend.replacements.get());
                    assertEquals(RegistrationState.INSTALLED, old.state());
                    assertNull(privateField(old, "pendingReplacement"));
                    assertNull(privateField(old, "uncertainOldHandle"));
                    assertEquals(writeFirst, anchor.containsKey(oldSpec.id()));

                    if (writeFirst) {
                        assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                                environment.install(oldSpec).status());
                        assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE,
                                environment.replace(newSpec).status());
                    } else {
                        assertEquals(RegistrationReceipt.Status.ALREADY_INSTALLED,
                                environment.install(oldSpec).status());
                    }
                    assertEquals(0, backend.replacements.get());
                    CloseReport report = old.close();
                    assertNotNull(report);
                    assertEquals(CloseReport.EntryRestoration.RESTORED, report.entryRestoration());
                    assertEquals(1, backend.uninstalls.get());
                    assertSame(report, old.close());
                    assertEquals(1, backend.uninstalls.get());
                    environment.close();
                    assertEquals(1, backend.uninstalls.get());
                    assertEquals("written anchor remains conservatively retained", writeFirst,
                            anchor.containsKey(oldSpec.id()));
                } finally { environment.close(); }
            }
        }
    }

    private static HookSpec spec(int value) throws Exception {
        Method target = ReplacementPreAnchorFailureTest.class.getMethod("target");
        return HookSpec.forMember("anchor.failure", "anchor-test", target).replaceConstant(value).build();
    }

    private static Object privateField(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    @SuppressWarnings("unchecked")
    private static FaultMap inject(HookEnvironment environment, Throwable failure, boolean writeFirst) throws Exception {
        Field field = HookEnvironment.class.getDeclaredField("interruptedReplacements");
        field.setAccessible(true);
        Map<HookId, CoreRegistration> existing = (Map<HookId, CoreRegistration>) field.get(environment);
        FaultMap result = new FaultMap(failure, writeFirst);
        result.putAll(existing);
        field.set(environment, result);
        result.armed = true;
        return result;
    }

    private static final class FaultMap extends LinkedHashMap<HookId, CoreRegistration> {
        private static final long serialVersionUID = 1L;
        final transient Throwable failure;
        final boolean writeFirst;
        boolean armed;

        FaultMap(Throwable failure, boolean writeFirst) {
            this.failure = failure;
            this.writeFirst = writeFirst;
        }

        @Override public CoreRegistration put(HookId id, CoreRegistration registration) {
            if (!armed) return super.put(id, registration);
            armed = false;
            if (writeFirst) super.put(id, registration);
            if (failure instanceof Error) throw (Error) failure;
            throw (RuntimeException) failure;
        }
    }

    private static final class Backend implements HookBackend, BackendContext {
        final AtomicInteger replacements = new AtomicInteger();
        final AtomicInteger uninstalls = new AtomicInteger();
        final BackendCapabilities capabilities = new BackendCapabilities.Builder("anchor-test", ChainSemantics.FRAMEWORK_CHAIN)
                .declare(Capability.ATOMIC_REPLACE, CapabilityResult.of(
                        CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL, "anchor-test", null, null, "test"))
                .build();

        @Override public String id() { return "anchor-test"; }
        @Override public BackendCapabilities capabilities() { return capabilities; }
        @Override public BackendContext context() { return this; }
        @Override public String processName() { return "test"; }
        @Override public ClassLoader classLoader() { return ReplacementPreAnchorFailureTest.class.getClassLoader(); }
        @Override public long codeEpoch() { return 1L; }
        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) {
            return new BackendHandle() {
                @Override public BackendCloseReport uninstall() {
                    uninstalls.incrementAndGet();
                    return new BackendCloseReport(CloseReport.EntryRestoration.RESTORED,
                            CloseReport.PhysicalRelease.NOT_CLAIMED, false, "removed");
                }
                @Override public BackendHandle replace(BoundPlan replacement, DispatchChannel next) {
                    replacements.incrementAndGet();
                    throw new AssertionError("pre-anchor failure must prevent backend replace");
                }
            };
        }
    }
}
