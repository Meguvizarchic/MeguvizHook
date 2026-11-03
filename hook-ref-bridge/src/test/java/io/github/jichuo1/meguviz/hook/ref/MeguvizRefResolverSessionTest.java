package io.github.jichuo1.meguviz.hook.ref;

import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.core.DiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind;
import io.github.jichuo1.meguviz.ref.api.evidence.CodeIdentity;
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.api.query.Selection;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import io.github.jichuo1.meguviz.ref.spi.CandidateBatch;
import io.github.jichuo1.meguviz.ref.spi.CandidateProvider;
import io.github.jichuo1.meguviz.ref.spi.CandidateRequest;
import io.github.jichuo1.meguviz.ref.spi.ProviderDescriptor;
import io.github.jichuo1.meguviz.ref.spi.ProviderRegistry;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class MeguvizRefResolverSessionTest {
    public static final class Host {
        public String value(String value) { return value; }
        public String value(Integer value) { return String.valueOf(value); }
    }

    private static final ClassLoader LOADER = Host.class.getClassLoader();
    private static final TargetSpec.DescriptorTarget TARGET = TargetSpec.descriptor(TargetKind.METHOD,
            Host.class.getName(), "value").parameters("java.lang.String").returning("java.lang.String").build();

    private static final class Context implements BackendContext {
        final AtomicReference<ClassLoader> loader = new AtomicReference<ClassLoader>(LOADER);
        final AtomicLong epoch = new AtomicLong(4);
        @Override public String processName() { return "test.process"; }
        @Override public ClassLoader classLoader() { return loader.get(); }
        @Override public long codeEpoch() { return epoch.get(); }
    }

    private static QuerySpec query(Class<?> owner) {
        return QuerySpec.builder(owner, MemberKind.METHOD).name("value")
                .parameters(String.class).selection(Selection.ALL).resultLimit(128).build();
    }

    @Test public void explicitlyReusesOneScopeAndPositiveQueryCache() throws Exception {
        Context context = new Context();
        AtomicInteger created = new AtomicInteger();
        AtomicReference<CodeScope> scope = new AtomicReference<CodeScope>();
        MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(context, loader -> {
            created.incrementAndGet();
            CodeScope value = CodeScope.open(loader);
            scope.set(value);
            return value;
        }, (target, owner) -> query(owner));
        List<Executable> first = session.resolve(TARGET, LOADER);
        List<Executable> second = session.resolve(TARGET, LOADER);
        assertEquals(first, second);
        assertEquals(1, first.size());
        first.clear();
        assertEquals("mutating one returned list must not affect another", 1, second.size());
        assertEquals(1, session.resolve(TARGET, LOADER).size());
        assertEquals(1, created.get());
        assertTrue("second complete query should hit the owned scope cache",
                scope.get().diagnostics().get("cacheHits") >= 1L);
        assertEquals(MeguvizRefResolverSession.State.OPEN, session.report().state());
        MeguvizRefResolverSession.CloseReport report = session.requestClose();
        assertEquals(MeguvizRefResolverSession.State.CLOSED, report.state());
        assertEquals(0, report.inFlight());
        assertNull(report.failure());
        assertEquals(CodeScope.State.CLOSED, scope.get().state());
        assertThrows(BackendInstallException.class, () -> session.resolve(TARGET, LOADER));
    }

    @Test public void structuralQuerySessionCanUseDefaultScope() throws Exception {
        MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(
                new Context(), (target, owner) -> query(owner));
        try {
            assertEquals(Host.class.getDeclaredMethod("value", String.class), session.resolve(TARGET, LOADER).get(0));
            assertEquals(1, session.resolve(TARGET, LOADER).size());
        } finally {
            session.close();
        }
        assertEquals(MeguvizRefResolverSession.State.CLOSED, session.report().state());
    }

    @Test public void explicitlyReusedQueryStillChecksEachTargetOwnerAndSessionIdentity() throws Exception {
        QuerySpec fixed = QuerySpec.builder(Host.class, MemberKind.METHOD).name("value")
                .parameters(String.class).returns(String.class).selection(Selection.ALL).resultLimit(128).build();
        AtomicInteger factoryCalls = new AtomicInteger();
        MeguvizRefMemberResolver.QueryFactory factory = (target, owner) -> {
            factoryCalls.incrementAndGet();
            if (target != TARGET || owner != Host.class) {
                throw new BackendInstallException("fixed query target or owner changed");
            }
            return fixed;
        };
        TargetSpec.DescriptorTarget other = TargetSpec.descriptor(TargetKind.METHOD, Host.class.getName(), "value")
                .parameters("java.lang.Integer").returning("java.lang.String").build();
        Context context = new Context();
        FakeBackend backend = FakeBackend.create();
        backend.setClassLoader(LOADER);
        try (MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(context, factory);
             HookEnvironment environment = HookEnvironment.create(backend, session, DiagnosticsSink.NONE)) {
            List<Executable> first = session.resolve(TARGET, LOADER);
            List<Executable> second = session.resolve(TARGET, LOADER);
            assertEquals(Host.class.getDeclaredMethod("value", String.class), first.get(0));
            assertEquals(first, second);
            assertNotSame(first, second);
            first.clear();
            assertEquals(1, second.size());
            assertEquals(2, factoryCalls.get());
            assertEquals(MeguvizRefResolverSession.State.OPEN, session.report().state());

            HookSpec wrongTarget = HookSpec.builder("fixed-query.other", "test", other)
                    .before(invocation -> { }).build();
            assertEquals(RegistrationReceipt.Status.FAILED, environment.install(wrongTarget).status());
            assertEquals(3, factoryCalls.get());
            assertEquals(MeguvizRefResolverSession.State.CLOSED, session.report().state());
            assertEquals(0, backend.installCount());
        }

        factoryCalls.set(0);
        Context loaderContext = new Context();
        FakeBackend loaderBackend = FakeBackend.create();
        ClassLoader otherLoader = new ClassLoader(LOADER) { };
        loaderBackend.setClassLoader(otherLoader);
        try (MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(loaderContext, factory);
             HookEnvironment environment = HookEnvironment.create(loaderBackend, session, DiagnosticsSink.NONE)) {
            HookSpec spec = HookSpec.builder("fixed-query.loader", "test", TARGET).before(invocation -> { }).build();
            assertEquals(RegistrationReceipt.Status.FAILED, environment.install(spec).status());
            assertEquals(0, factoryCalls.get());
            assertEquals(MeguvizRefResolverSession.State.CLOSED, session.report().state());
            assertEquals(0, loaderBackend.installCount());
        }

        factoryCalls.set(0);
        Context epochContext = new Context();
        FakeBackend epochBackend = FakeBackend.create();
        epochBackend.setClassLoader(LOADER);
        try (MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(epochContext, factory);
             HookEnvironment environment = HookEnvironment.create(epochBackend, session, DiagnosticsSink.NONE)) {
            epochContext.epoch.incrementAndGet();
            HookSpec spec = HookSpec.builder("fixed-query.epoch", "test", TARGET).before(invocation -> { }).build();
            assertEquals(RegistrationReceipt.Status.FAILED, environment.install(spec).status());
            assertEquals(0, factoryCalls.get());
            assertEquals(MeguvizRefResolverSession.State.CLOSED, session.report().state());
            assertEquals(0, epochBackend.installCount());
        }
    }

    @Test public void epochOrLoaderMismatchRetiresAndNeverReopens() throws Exception {
        Context context = new Context();
        MeguvizRefResolverSession session = MeguvizRefResolverSession.open(context);
        assertEquals(1, session.resolve(TARGET, LOADER).size());
        context.epoch.incrementAndGet();
        assertThrows(BackendInstallException.class, () -> session.resolve(TARGET, LOADER));
        assertEquals(MeguvizRefResolverSession.State.CLOSED, session.report().state());
        context.epoch.decrementAndGet(); // Even an invalid ABA must not revive the retired session.
        assertThrows(BackendInstallException.class, () -> session.resolve(TARGET, LOADER));

        MeguvizRefResolverSession wrong = MeguvizRefResolverSession.open(context);
        ClassLoader other = new ClassLoader(LOADER) { };
        assertThrows(BackendInstallException.class, () -> wrong.resolve(TARGET, other));
        assertEquals(MeguvizRefResolverSession.State.CLOSED, wrong.report().state());
        assertThrows(BackendInstallException.class, () -> wrong.resolve(TARGET, LOADER));
    }

    @Test public void closeDuringQueryRejectsNewWorkAndDiscardsAdmittedResult() throws Exception {
        Context context = new Context();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicInteger queries = new AtomicInteger();
        AtomicReference<CodeScope> scope = new AtomicReference<CodeScope>();
        MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(context, loader -> {
            CodeScope value = CodeScope.open(loader); scope.set(value); return value;
        }, (target, owner) -> {
            queries.incrementAndGet(); entered.countDown();
            try {
                if (!resume.await(5, TimeUnit.SECONDS)) throw new BackendInstallException("query timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new BackendInstallException("query interrupted", interrupted);
            }
            return query(owner);
        });
        AtomicReference<Throwable> observed = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try { session.resolve(TARGET, LOADER); }
            catch (Throwable failure) { observed.set(failure); }
        }, "ref-session-close");
        worker.setDaemon(true);
        try {
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            MeguvizRefResolverSession.CloseReport report = session.requestClose();
            assertEquals(MeguvizRefResolverSession.State.DRAINING, report.state());
            assertEquals(1, report.inFlight());
            assertThrows(BackendInstallException.class, () -> session.resolve(TARGET, LOADER));
            assertEquals(1, queries.get());
        } finally {
            resume.countDown(); worker.join(5000);
        }
        assertFalse(worker.isAlive());
        assertTrue(observed.get() instanceof BackendInstallException);
        assertEquals(MeguvizRefResolverSession.State.CLOSED, session.report().state());
        assertEquals(CodeScope.State.CLOSED, scope.get().state());
    }

    @Test public void closeWinningCompletionLockRejectsResultAndReleasesLease() throws Exception {
        MeguvizRefResolverSession session = MeguvizRefResolverSession.open(new Context());
        // Exercise the completion boundary directly: no production callback is inserted into the
        // interval between the final checkpoint and the session's completion lock.
        Method acquire = MeguvizRefResolverSession.class.getDeclaredMethod("acquire", ClassLoader.class);
        acquire.setAccessible(true);
        Object lease = acquire.invoke(session, LOADER);
        Method complete = MeguvizRefResolverSession.class.getDeclaredMethod("complete", lease.getClass());
        Method release = MeguvizRefResolverSession.class.getDeclaredMethod("release", Throwable.class);
        complete.setAccessible(true); release.setAccessible(true);
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicReference<Throwable> observed = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            ready.countDown();
            try {
                if (!proceed.await(5, TimeUnit.SECONDS)) throw new AssertionError("completion timed out");
                complete.invoke(session, lease);
                observed.set(new AssertionError("retired result was accepted"));
            } catch (InvocationTargetException expected) {
                observed.set(expected.getCause());
                try { release.invoke(session, expected.getCause()); }
                catch (ReflectiveOperationException failure) { observed.set(failure); }
            } catch (Throwable failure) { observed.set(failure); }
        }, "ref-session-completion-race");
        worker.setDaemon(true);
        try {
            worker.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            MeguvizRefResolverSession.CloseReport report = session.requestClose();
            assertEquals(MeguvizRefResolverSession.State.DRAINING, report.state());
            assertEquals(1, report.inFlight());
        } finally {
            proceed.countDown(); worker.join(5000);
        }
        assertFalse(worker.isAlive());
        assertTrue(observed.get() instanceof BackendInstallException);
        assertEquals(MeguvizRefResolverSession.State.CLOSED, session.report().state());
        assertEquals(0, session.report().inFlight());
    }

    @Test public void epochChangeDuringFactoryDiscardsQueryAndClosesScope() throws Exception {
        Context context = new Context();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<CodeScope> scope = new AtomicReference<CodeScope>();
        MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(context, loader -> {
            CodeScope value = CodeScope.open(loader); scope.set(value); return value;
        }, (target, owner) -> {
            entered.countDown();
            try {
                if (!resume.await(5, TimeUnit.SECONDS)) throw new BackendInstallException("query timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new BackendInstallException("query interrupted", interrupted);
            }
            return query(owner);
        });
        AtomicReference<Throwable> observed = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try { session.resolve(TARGET, LOADER); }
            catch (Throwable failure) { observed.set(failure); }
        }, "ref-session-epoch");
        worker.setDaemon(true);
        try {
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            context.epoch.incrementAndGet();
        } finally {
            resume.countDown(); worker.join(5000);
        }
        assertFalse(worker.isAlive());
        assertTrue(observed.get() instanceof BackendInstallException);
        assertEquals(MeguvizRefResolverSession.State.CLOSED, session.report().state());
        assertEquals(CodeScope.State.CLOSED, scope.get().state());
    }

    @Test public void recursiveResolutionRetiresTheSession() throws Exception {
        Context context = new Context();
        AtomicReference<MeguvizRefResolverSession> reference = new AtomicReference<MeguvizRefResolverSession>();
        MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(context, CodeScope::open,
                (target, owner) -> {
                    assertThrows(BackendInstallException.class, () -> reference.get().resolve(target, LOADER));
                    return query(owner);
                });
        reference.set(session);
        assertThrows(BackendInstallException.class, () -> session.resolve(TARGET, LOADER));
        assertEquals(MeguvizRefResolverSession.State.CLOSED, session.report().state());
        assertEquals("recursive session resolution", session.report().reason());
    }

    @Test public void incompleteOrExternallyInvalidatedScopeNeverGetsReused() throws Exception {
        Context context = new Context();
        MeguvizRefResolverSession incomplete = MeguvizRefResolverSession.withQueries(context, CodeScope::open,
                (target, owner) -> QuerySpec.builder(owner, MemberKind.METHOD).name("value")
                        .selection(Selection.ALL).candidateBudget(1).resultLimit(128).build());
        assertThrows(BackendInstallException.class, () -> incomplete.resolve(TARGET, LOADER));
        assertEquals(MeguvizRefResolverSession.State.CLOSED, incomplete.report().state());
        assertThrows(BackendInstallException.class, () -> incomplete.resolve(TARGET, LOADER));

        AtomicReference<CodeScope> leaked = new AtomicReference<CodeScope>();
        MeguvizRefResolverSession changed = MeguvizRefResolverSession.withQueries(context, loader -> {
            CodeScope scope = CodeScope.open(loader); leaked.set(scope); return scope;
        }, (target, owner) -> query(owner));
        // Deliberately violate ownership to verify fail-closed detection.
        leaked.get().advanceCodeEpoch("outside invalidation");
        assertThrows(BackendInstallException.class, () -> changed.resolve(TARGET, LOADER));
        assertEquals(MeguvizRefResolverSession.State.CLOSED, changed.report().state());
    }

    @Test public void concurrentResolutionsStayWithinOneGeneration() throws Exception {
        Context context = new Context();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch resume = new CountDownLatch(1);
        MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(context, CodeScope::open,
                (target, owner) -> {
                    entered.countDown();
                    try {
                        if (!resume.await(5, TimeUnit.SECONDS)) throw new BackendInstallException("query timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new BackendInstallException("query interrupted", interrupted);
                    }
                    return query(owner);
                });
        AtomicReference<List<Executable>> first = new AtomicReference<List<Executable>>();
        AtomicReference<List<Executable>> second = new AtomicReference<List<Executable>>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread a = new Thread(() -> {
            try { first.set(session.resolve(TARGET, LOADER)); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "ref-session-a");
        Thread b = new Thread(() -> {
            try { second.set(session.resolve(TARGET, LOADER)); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "ref-session-b");
        a.setDaemon(true); b.setDaemon(true);
        try {
            a.start(); b.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(2, session.report().inFlight());
        } finally {
            resume.countDown(); a.join(5000); b.join(5000);
        }
        assertFalse(a.isAlive()); assertFalse(b.isAlive()); assertNull(failure.get());
        assertEquals(1, first.get().size()); assertEquals(first.get(), second.get());
        assertEquals(MeguvizRefResolverSession.State.CLOSED, session.requestClose().state());
    }

    @Test public void rejectsPreviouslyUsedScopeAndClosesTransferredOwnership() {
        Context context = new Context();
        CodeScope used = CodeScope.open(LOADER);
        used.resolve(query(Host.class));
        assertThrows(BackendInstallException.class, () -> MeguvizRefResolverSession.withQueries(context,
                ignored -> used, (target, owner) -> query(owner)));
        assertEquals(CodeScope.State.CLOSED, used.state());
    }

    @Test public void providerCleanupFailureIsReportedAndNeverRetried() throws Exception {
        Context context = new Context();
        AtomicInteger closes = new AtomicInteger();
        IllegalStateException expected = new IllegalStateException("provider close may have side effects");
        CandidateProvider provider = new CandidateProvider() {
            @Override public ProviderDescriptor descriptor() {
                return new ProviderDescriptor("test", "1", 1, 0, "", Collections.emptyList());
            }
            @Override public CandidateBatch discover(CandidateRequest request) { throw new AssertionError("not used"); }
            @Override public void close() { closes.incrementAndGet(); throw expected; }
        };
        MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(context, loader -> {
            CodeScope scope = CodeScope.open(loader);
            scope.installProviders(ProviderRegistry.create(Collections.singletonList(provider)),
                    CodeIdentity.strong(Collections.singletonMap("test", "a".repeat(64)), "loader", "v1"));
            return scope;
        }, (target, owner) -> query(owner));
        MeguvizRefResolverSession.CloseReport result = session.requestClose();
        assertEquals(MeguvizRefResolverSession.State.FAILED, result.state());
        assertSame(expected, result.failure());
        assertEquals(1, closes.get());
        assertThrows(IllegalStateException.class, session::close);
        assertEquals(1, closes.get());
    }

    @Test public void fatalQueryAndCleanupPreservePrimaryAndRetireLease() throws Exception {
        VirtualMachineError queryFailure = new VirtualMachineError("synthetic query fatal") { };
        VirtualMachineError cleanupFailure = new VirtualMachineError("synthetic cleanup fatal") { };
        CandidateProvider provider = new CandidateProvider() {
            @Override public ProviderDescriptor descriptor() {
                return new ProviderDescriptor("fatal", "1", 1, 0, "", Collections.emptyList());
            }
            @Override public CandidateBatch discover(CandidateRequest request) { throw new AssertionError("not used"); }
            @Override public void close() { throw cleanupFailure; }
        };
        MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(new Context(), loader -> {
            CodeScope scope = CodeScope.open(loader);
            scope.installProviders(ProviderRegistry.create(Collections.singletonList(provider)),
                    CodeIdentity.strong(Collections.singletonMap("test", "c".repeat(64)), "loader", "v1"));
            return scope;
        }, (target, owner) -> { throw queryFailure; });
        assertSame(queryFailure, assertThrows(VirtualMachineError.class, () -> session.resolve(TARGET, LOADER)));
        assertEquals(1, queryFailure.getSuppressed().length);
        assertSame(cleanupFailure, queryFailure.getSuppressed()[0]);
        assertEquals(MeguvizRefResolverSession.State.FAILED, session.report().state());
        assertEquals(0, session.report().inFlight());
        assertSame(cleanupFailure, session.report().failure());
        assertThrows(BackendInstallException.class, () -> session.resolve(TARGET, LOADER));
    }

    @Test public void providerCloseDoesNotHoldTheSessionMonitor() throws Exception {
        Context context = new Context();
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        CandidateProvider provider = new CandidateProvider() {
            @Override public ProviderDescriptor descriptor() {
                return new ProviderDescriptor("slow", "1", 1, 0, "", Collections.emptyList());
            }
            @Override public CandidateBatch discover(CandidateRequest request) { throw new AssertionError("not used"); }
            @Override public void close() {
                closing.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("close timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        };
        MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(context, loader -> {
            CodeScope scope = CodeScope.open(loader);
            scope.installProviders(ProviderRegistry.create(Collections.singletonList(provider)),
                    CodeIdentity.strong(Collections.singletonMap("test", "b".repeat(64)), "loader", "v1"));
            return scope;
        }, (target, owner) -> query(owner));
        Thread closer = new Thread(() -> {
            try { session.requestClose(); } catch (Throwable error) { failure.set(error); }
        }, "ref-session-provider-close");
        Thread observer = new Thread(() -> {
            try {
                assertEquals(MeguvizRefResolverSession.State.DRAINING, session.report().state());
                assertThrows(BackendInstallException.class, () -> session.resolve(TARGET, LOADER));
            } catch (Throwable error) { failure.compareAndSet(null, error); }
            finally { observed.countDown(); }
        }, "ref-session-observer");
        closer.setDaemon(true); observer.setDaemon(true);
        try {
            closer.start();
            assertTrue(closing.await(5, TimeUnit.SECONDS));
            assertTrue("synchronous provider cleanup keeps the closer running", closer.isAlive());
            observer.start();
            assertTrue("session monitor held across provider close", observed.await(2, TimeUnit.SECONDS));
        } finally {
            resume.countDown(); closer.join(5000); observer.join(5000);
        }
        assertFalse(closer.isAlive()); assertFalse(observer.isAlive()); assertNull(failure.get());
        assertEquals(MeguvizRefResolverSession.State.CLOSED, session.report().state());
    }

    @Test public void failingContextGetterRetiresInsteadOfReturningCachedValues() throws Exception {
        Context context = new Context();
        AtomicInteger calls = new AtomicInteger();
        BackendContext unreliable = new BackendContext() {
            @Override public String processName() { return context.processName(); }
            @Override public ClassLoader classLoader() { return context.classLoader(); }
            @Override public long codeEpoch() {
                if (calls.incrementAndGet() > 2) throw new IllegalStateException("identity unavailable");
                return context.codeEpoch();
            }
        };
        MeguvizRefResolverSession session = MeguvizRefResolverSession.open(unreliable);
        assertThrows(BackendInstallException.class, () -> session.resolve(TARGET, LOADER));
        assertEquals(MeguvizRefResolverSession.State.CLOSED, session.report().state());
        assertEquals("identity check failed", session.report().reason());
    }
}
