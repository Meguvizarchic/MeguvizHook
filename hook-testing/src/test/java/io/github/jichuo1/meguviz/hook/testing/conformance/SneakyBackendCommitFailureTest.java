package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.api.TargetInstallReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendAdoption;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import io.github.jichuo1.meguviz.hook.testing.Fixtures;
import java.io.IOException;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Unknown backend commits from undeclared checked failures must not be mistaken for safe failures. */
public final class SneakyBackendCommitFailureTest {
    private static final Executable ADD = TestSupport.method("add", int.class, int.class);
    private static final Executable INT = TestSupport.method("overloaded", int.class);
    private static final Executable STRING = TestSupport.method("overloaded", String.class);

    private enum Mode { NONE, INSTALL_UNKNOWN, INSTALL_CHECKED, INSTALL_MESSAGE_FAILURE,
        IDENTITY_UNKNOWN, REPLACE_UNKNOWN, REPLACE_CHECKED }

    private static final class Backend implements HookBackend {
        final FakeBackend fake = FakeBackend.create();
        final IOException failure = new IOException("undeclared backend failure after possible commit");
        final List<Executable> attempted = new ArrayList<Executable>();
        final List<DispatchChannel> channels = new ArrayList<DispatchChannel>();
        final List<BackendHandle> rawHandles = new ArrayList<BackendHandle>();
        final Mode mode;
        final int failAt;
        final Throwable messageFailure;
        boolean identityFailureArmed;
        int uninstalls;
        int replacements;
        DispatchChannel replacementChannel;

        private final BackendContext context = new BackendContext() {
            @Override public String processName() { return fake.context().processName(); }
            @Override public ClassLoader classLoader() {
                if (identityFailureArmed) { identityFailureArmed = false; throw sneaky(failure); }
                return fake.context().classLoader();
            }
            @Override public long codeEpoch() { return fake.context().codeEpoch(); }
        };

        Backend(Mode mode, int failAt) {
            this(mode, failAt, null);
        }

        Backend(Mode mode, int failAt, Throwable messageFailure) {
            this.mode = mode; this.failAt = failAt;
            this.messageFailure = messageFailure;
            fake.onTarget(ADD, (receiver, args) -> (Integer) args[0] + (Integer) args[1]);
            fake.onTarget(INT, (receiver, args) -> args[0]);
            fake.onTarget(STRING, (receiver, args) -> args[0]);
        }

        @Override public String id() { return fake.id(); }
        @Override public BackendCapabilities capabilities() { return fake.capabilities(); }
        @Override public BackendContext context() { return context; }

        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            attempted.add(plan.target());
            channels.add(channel);
            int number = attempted.size();
            if (mode == Mode.INSTALL_CHECKED && number == failAt) {
                throw new BackendInstallException("guaranteed uncommitted install");
            }
            if (mode == Mode.INSTALL_MESSAGE_FAILURE && number == failAt) {
                throw new BackendInstallException("uncommitted install with broken diagnostic") {
                    private static final long serialVersionUID = 1L;
                    @Override public String getMessage() { throw sneaky(messageFailure); }
                };
            }
            BackendHandle handle = fake.install(plan, channel);
            rawHandles.add(handle);
            if (mode == Mode.INSTALL_UNKNOWN && number == failAt) throw sneaky(failure);
            if (mode == Mode.IDENTITY_UNKNOWN && number == failAt) identityFailureArmed = true;
            return wrap(handle);
        }

        private BackendHandle wrap(BackendHandle handle) {
            return new BackendHandle() {
                @Override public BackendCloseReport uninstall() { uninstalls++; return handle.uninstall(); }
                @Override public BackendHandle replace(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
                    replacements++;
                    if (mode == Mode.REPLACE_CHECKED) throw new BackendInstallException("guaranteed uncommitted replacement");
                    replacementChannel = channel;
                    BackendHandle next = handle.replace(plan, channel);
                    if (mode == Mode.REPLACE_UNKNOWN) throw sneaky(failure);
                    return wrap(next);
                }
            };
        }
    }

    private static HookSpec addSpec(String id, int value, AtomicInteger callbacks) {
        return TestSupport.spec(id, ADD).before(invocation -> {
            callbacks.incrementAndGet(); invocation.setEarlyResult(value);
        }).build();
    }

    private static HookSpec allSpec(String id, AtomicInteger callbacks) {
        TargetSpec target = TargetSpec.descriptor(TargetKind.METHOD, Fixtures.class.getName(), "overloaded")
                .match(MatchPolicy.ALL).build();
        return HookSpec.builder(id, "sneaky-commit-test", target)
                .before(invocation -> callbacks.incrementAndGet()).build();
    }

    private static HookEnvironment allEnvironment(Backend backend) {
        return HookEnvironment.create(backend, (target, loader) -> Arrays.asList(INT, STRING), DiagnosticsSink.NONE);
    }

    @Test public void firstInstallCommitThenUndeclaredCheckedFailureQuarantinesUnknown() throws Throwable {
        Backend backend = new Backend(Mode.INSTALL_UNKNOWN, 1);
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicInteger callbacks = new AtomicInteger();
        HookSpec spec = addSpec("sneaky.install.first", 99, callbacks);
        try {
            assertSame(backend.failure, TestSupport.capture(() -> env.install(spec)));
            assertTrue(backend.fake.isHooked(ADD));
            assertEquals(0, backend.uninstalls);
            assertEquals(DispatchResult.Kind.PASSTHROUGH, backend.channels.get(0).dispatch(null).kind());
            assertEquals(3, backend.fake.call(ADD, null, 1, 2));
            assertEquals(0, callbacks.get());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, env.install(spec).status());
            assertEquals(1, backend.fake.installCount());
        } finally { env.close(); }
        assertEquals(0, backend.uninstalls);
        assertTrue(backend.fake.isHooked(ADD));
    }

    @Test public void laterUnknownInstallClosesKnownHandleAndRetiresBothChannels() throws Throwable {
        Backend backend = new Backend(Mode.INSTALL_UNKNOWN, 2);
        HookEnvironment env = allEnvironment(backend);
        AtomicInteger callbacks = new AtomicInteger();
        HookSpec spec = allSpec("sneaky.install.all", callbacks);
        try {
            assertSame(backend.failure, TestSupport.capture(() -> env.install(spec)));
            assertEquals(Arrays.asList(INT, STRING), backend.attempted);
            assertEquals(1, backend.uninstalls);
            assertFalse(backend.fake.isHooked(INT));
            assertTrue(backend.fake.isHooked(STRING));
            assertEquals(DispatchResult.Kind.PASSTHROUGH, backend.channels.get(0).dispatch(null).kind());
            assertEquals(DispatchResult.Kind.PASSTHROUGH, backend.channels.get(1).dispatch(null).kind());
            assertEquals(7, backend.fake.call(INT, null, 7));
            assertEquals("original", backend.fake.call(STRING, null, "original"));
            assertEquals(0, callbacks.get());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, env.install(spec).status());
        } finally { env.close(); }
        assertEquals(1, backend.uninstalls);
        assertTrue(backend.fake.isHooked(STRING));
    }

    @Test public void checkedUncommittedInstallStillPublishesPartialSuccess() throws Throwable {
        Backend backend = new Backend(Mode.INSTALL_CHECKED, 2);
        HookEnvironment env = allEnvironment(backend);
        AtomicInteger callbacks = new AtomicInteger();
        try {
            RegistrationReceipt receipt = env.install(allSpec("sneaky.install.checked", callbacks));
            assertEquals(RegistrationReceipt.Status.PARTIALLY_INSTALLED, receipt.status());
            assertNotNull(receipt.registration());
            assertEquals(TargetInstallReceipt.Status.INSTALLED, receipt.targets().get(0).status());
            assertEquals(TargetInstallReceipt.Status.FAILED, receipt.targets().get(1).status());
            assertTrue(backend.fake.isHooked(INT));
            assertFalse(backend.fake.isHooked(STRING));
            assertEquals(7, backend.fake.call(INT, null, 7));
            assertEquals(1, callbacks.get());
            receipt.registration().close();
            assertEquals(1, backend.uninstalls);
        } finally { env.close(); }
    }

    @Test public void failedCheckedFailureMessageCleansKnownHandleAndQuarantinesId() throws Throwable {
        for (Throwable diagnosticFailure : new Throwable[] {
                new IllegalStateException("diagnostic failed"), new IOException("undeclared diagnostic failure") }) {
            Backend backend = new Backend(Mode.INSTALL_MESSAGE_FAILURE, 2, diagnosticFailure);
            HookEnvironment env = allEnvironment(backend);
            AtomicInteger callbacks = new AtomicInteger();
            HookSpec spec = allSpec("sneaky.install.message", callbacks);
            try {
                assertSame(diagnosticFailure, TestSupport.capture(() -> env.install(spec)));
                assertEquals(Arrays.asList(INT, STRING), backend.attempted);
                assertEquals(1, backend.fake.installCount());
                assertEquals(1, backend.uninstalls);
                assertFalse(backend.fake.isHooked(INT));
                assertFalse(backend.fake.isHooked(STRING));
                assertEquals(DispatchResult.Kind.PASSTHROUGH, backend.channels.get(0).dispatch(null).kind());
                assertEquals(7, backend.fake.call(INT, null, 7));
                assertEquals(0, callbacks.get());
                assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, env.install(spec).status());
                assertEquals(1, backend.fake.installCount());
            } finally { env.close(); }
            assertEquals(1, backend.uninstalls);
        }
    }

    @Test public void postInstallIdentityFailureClosesKnownHandleAndQuarantinesId() throws Throwable {
        Backend backend = new Backend(Mode.IDENTITY_UNKNOWN, 1);
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicInteger callbacks = new AtomicInteger();
        HookSpec spec = addSpec("sneaky.install.identity", 99, callbacks);
        try {
            assertSame(backend.failure, TestSupport.capture(() -> env.install(spec)));
            assertEquals(1, backend.uninstalls);
            assertFalse(backend.fake.isHooked(ADD));
            assertEquals(DispatchResult.Kind.PASSTHROUGH, backend.channels.get(0).dispatch(null).kind());
            assertEquals(3, backend.fake.call(ADD, null, 1, 2));
            assertEquals(0, callbacks.get());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, env.install(spec).status());
        } finally { env.close(); }
        assertEquals(1, backend.uninstalls);
    }

    @Test public void committedReplacementWithUndeclaredFailureRetiresBothAndDoesNotUnhookUnknown() throws Throwable {
        Backend backend = new Backend(Mode.REPLACE_UNKNOWN, 0);
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicInteger oldCalls = new AtomicInteger(), newCalls = new AtomicInteger();
        HookSpec oldSpec = addSpec("sneaky.replace", 99, oldCalls);
        HookSpec next = addSpec("sneaky.replace", 100, newCalls);
        try {
            HookRegistration old = TestSupport.installOrFail(env, oldSpec);
            assertEquals(99, backend.fake.call(ADD, null, 1, 2));
            oldCalls.set(0);
            assertSame(backend.failure, TestSupport.capture(() -> env.replace(next)));
            assertEquals(RegistrationState.RETIRED_RESIDENT, old.state());
            assertEquals(DispatchResult.Kind.PASSTHROUGH, backend.channels.get(0).dispatch(null).kind());
            assertEquals(DispatchResult.Kind.PASSTHROUGH, backend.replacementChannel.dispatch(null).kind());
            assertEquals(3, backend.fake.call(ADD, null, 1, 2));
            assertEquals(0, oldCalls.get()); assertEquals(0, newCalls.get());
            assertEquals(CloseReport.EntryRestoration.UNKNOWN, old.close().entryRestoration());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, env.install(oldSpec).status());
            assertEquals(0, backend.uninstalls);
            assertEquals(1, backend.replacements);
        } finally { env.close(); }
        assertEquals(0, backend.uninstalls);
        assertTrue(backend.fake.isHooked(ADD));
    }

    @Test public void checkedUncommittedReplacementLeavesOldGenerationUsable() throws Throwable {
        Backend backend = new Backend(Mode.REPLACE_CHECKED, 0);
        HookEnvironment env = HookEnvironment.create(backend);
        AtomicInteger oldCalls = new AtomicInteger();
        HookSpec oldSpec = addSpec("sneaky.replace.checked", 99, oldCalls);
        try {
            HookRegistration old = TestSupport.installOrFail(env, oldSpec);
            RegistrationReceipt failed = env.replace(addSpec("sneaky.replace.checked", 100, new AtomicInteger()));
            assertEquals(RegistrationReceipt.Status.FAILED, failed.status());
            assertEquals(RegistrationState.INSTALLED, old.state());
            assertEquals(99, backend.fake.call(ADD, null, 1, 2));
            assertEquals(1, oldCalls.get());
            assertEquals(CloseReport.EntryRestoration.RESTORED, old.close().entryRestoration());
            assertEquals(1, backend.uninstalls);
        } finally { env.close(); }
    }

    @Test public void committedAdoptionFailureRetiresNewChannelWithoutUnhookingUnknownHandle() throws Throwable {
        Backend backend = new Backend(Mode.NONE, 0);
        HookEnvironment oldEnv = HookEnvironment.create(backend);
        HookEnvironment newEnv = HookEnvironment.create(backend);
        AtomicInteger oldCalls = new AtomicInteger(), newCalls = new AtomicInteger();
        HookSpec oldSpec = addSpec("sneaky.adoption", 99, oldCalls);
        HookSpec next = addSpec("sneaky.adoption", 100, newCalls);
        AtomicInteger adoptionCalls = new AtomicInteger();
        try {
            HookRegistration old = TestSupport.installOrFail(oldEnv, oldSpec);
            BackendHandle frameworkOldHandle = backend.rawHandles.get(0);
            BackendAdoption adoption = new BackendAdoption() {
                @Override public HookBackend backend() { return backend; }
                @Override public BackendHandle replace(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
                    adoptionCalls.incrementAndGet();
                    backend.replacementChannel = channel;
                    BackendHandle unknown = frameworkOldHandle.replace(plan, channel);
                    assertNotNull(unknown);
                    // The transferred handle is never returned to the new SDK environment.
                    throw sneaky(backend.failure);
                }
            };
            assertSame(backend.failure, TestSupport.capture(() -> newEnv.adopt(next, adoption)));
            assertEquals(1, adoptionCalls.get());
            assertEquals(DispatchResult.Kind.PASSTHROUGH, backend.replacementChannel.dispatch(null).kind());
            assertEquals(3, backend.fake.call(ADD, null, 1, 2));
            assertEquals(0, oldCalls.get()); assertEquals(0, newCalls.get());
            assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, newEnv.install(next).status());
            // Old-generation retirement is a separate owner responsibility after adoption.
            old.close();
            assertEquals(DispatchResult.Kind.PASSTHROUGH, backend.channels.get(0).dispatch(null).kind());
            assertEquals(1, backend.uninstalls);
            assertTrue("old handle must not remove the newly adopted entry", backend.fake.isHooked(ADD));
        } finally { try { newEnv.close(); } finally { oldEnv.close(); } }
        assertEquals(1, backend.uninstalls);
        assertTrue("unreturned adoption handle must not be unhooked", backend.fake.isHooked(ADD));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable failure) throws T { throw (T) failure; }
}
