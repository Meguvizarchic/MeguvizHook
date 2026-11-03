package io.github.jichuo1.meguviz.hook.ref;

import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberDescriptor;
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind;
import io.github.jichuo1.meguviz.ref.api.evidence.CodeIdentity;
import io.github.jichuo1.meguviz.ref.api.evidence.ResolutionReceipt;
import io.github.jichuo1.meguviz.ref.api.query.Hierarchy;
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.api.query.Selection;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import io.github.jichuo1.meguviz.ref.spi.CandidateBatch;
import io.github.jichuo1.meguviz.ref.spi.CandidateProvider;
import io.github.jichuo1.meguviz.ref.spi.CandidateRequest;
import io.github.jichuo1.meguviz.ref.spi.ProviderDescriptor;
import io.github.jichuo1.meguviz.ref.spi.ProviderRegistry;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Contract against the published Ref 1.0 dependency, without the later provenance API. */
public final class RefWaiterPolicyOldCompatibilityTest {
    public static final class Host { public String value() { return "value"; } }

    private static final ClassLoader LOADER = Host.class.getClassLoader();
    private static final TargetSpec.DescriptorTarget TARGET = TargetSpec.descriptor(TargetKind.METHOD,
            Host.class.getName(), "value").parameters().returning("java.lang.String").build();
    private static final MeguvizRefResolverSession.WaiterInterruptionPolicy ISOLATE =
            MeguvizRefResolverSession.WaiterInterruptionPolicy.ISOLATE_VERIFIED_WAITER;

    private static final class Context implements BackendContext {
        final AtomicInteger reads = new AtomicInteger();
        @Override public String processName() { return "test.process"; }
        @Override public ClassLoader classLoader() { reads.incrementAndGet(); return LOADER; }
        @Override public long codeEpoch() { reads.incrementAndGet(); return 1; }
    }

    private static QuerySpec query(Class<?> owner) {
        return QuerySpec.builder(owner, MemberKind.METHOD).name("value").parameterCount(0)
                .provider("test").selection(Selection.ALL).resultLimit(16).build();
    }

    @Test public void missingEvidenceAndDefaultPolicyStayConservative() throws Exception {
        // This test intentionally qualifies the pinned released Ref 1.0, not a development JAR.
        assertThrows(NoSuchMethodException.class, () -> ResolutionReceipt.class.getMethod("interruptionOrigin"));
        MeguvizRefResolverSession defaultSession = MeguvizRefResolverSession.open(new Context());
        MeguvizRefResolverSession explicitDefault = MeguvizRefResolverSession.open(new Context(),
                MeguvizRefResolverSession.WaiterInterruptionPolicy.RETIRE_SESSION);
        MeguvizRefResolverSession isolated = MeguvizRefResolverSession.open(new Context(), ISOLATE);
        try {
            assertFalse(defaultSession.isWaiterInterruptionEvidenceAvailable());
            assertFalse(explicitDefault.isWaiterInterruptionEvidenceAvailable());
            assertFalse(isolated.isWaiterInterruptionEvidenceAvailable());
            assertEquals(1, defaultSession.resolve(TARGET, LOADER).size());
            assertEquals(1, isolated.resolve(TARGET, LOADER).size());
        } finally {
            defaultSession.close(); explicitDefault.close(); isolated.close();
        }
    }

    @Test public void nullPolicyIsRejectedBeforeIdentityOrScopeFactoryCalls() {
        Context context = new Context();
        AtomicInteger opened = new AtomicInteger();
        MeguvizRefMemberResolver.ScopeFactory scopes = loader -> {
            opened.incrementAndGet(); return CodeScope.open(loader);
        };
        MeguvizRefMemberResolver.QueryFactory queries = (target, owner) -> query(owner);
        assertThrows(NullPointerException.class, () -> MeguvizRefResolverSession.open(context, null));
        assertThrows(NullPointerException.class,
                () -> MeguvizRefResolverSession.withHierarchy(context, Hierarchy.DECLARED, null));
        assertThrows(NullPointerException.class,
                () -> MeguvizRefResolverSession.withQueriesAndWaiterPolicy(context, queries, null));
        assertThrows(NullPointerException.class,
                () -> MeguvizRefResolverSession.withQueries(context, scopes, queries, null));
        assertEquals(0, context.reads.get());
        assertEquals(0, opened.get());
    }

    @Test public void oldRefInterruptedWaiterStillRetiresEntireSession() throws Exception {
        assertThrows(NoSuchMethodException.class, () -> ResolutionReceipt.class.getMethod("interruptionOrigin"));
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch ownerRelease = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<CodeScope> scope = new AtomicReference<CodeScope>();
        CandidateProvider provider = new CandidateProvider() {
            @Override public ProviderDescriptor descriptor() {
                return new ProviderDescriptor("test", "1", 1, 0, "", Collections.emptyList());
            }
            @Override public CandidateBatch discover(CandidateRequest request) throws Exception {
                ownerEntered.countDown();
                if (!ownerRelease.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("owner timed out");
                MemberDescriptor member = MemberDescriptor.from(Host.class.getDeclaredMethod("value"));
                return new CandidateBatch(Collections.singletonList(member), request.codeFingerprint(), "test", true);
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(new Context(), loader -> {
            CodeScope opened = CodeScope.open(loader);
            opened.installProviders(ProviderRegistry.create(Collections.singletonList(provider)),
                    CodeIdentity.strong(Collections.singletonMap("test", "a".repeat(64)), "loader", "v1"));
            scope.set(opened);
            return opened;
        }, (target, owner) -> query(owner), ISOLATE);
        AtomicReference<Throwable> ownerError = new AtomicReference<Throwable>();
        AtomicReference<Throwable> waiterError = new AtomicReference<Throwable>();
        AtomicBoolean waiterInterrupted = new AtomicBoolean();
        Thread owner = new Thread(() -> {
            try { session.resolve(TARGET, LOADER); }
            catch (Throwable failure) { ownerError.set(failure); }
        }, "old-ref-owner");
        Thread waiter = new Thread(() -> {
            try { session.resolve(TARGET, LOADER); }
            catch (Throwable failure) { waiterError.set(failure); }
            finally { waiterInterrupted.set(Thread.currentThread().isInterrupted()); }
        }, "old-ref-waiter");
        owner.setDaemon(true); waiter.setDaemon(true);
        try {
            assertFalse(session.isWaiterInterruptionEvidenceAvailable());
            owner.start();
            assertTrue("owner did not enter provider", ownerEntered.await(5, TimeUnit.SECONDS));
            waiter.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (scope.get().diagnostics().get("inFlight") < 2L) {
                if (System.nanoTime() >= deadline) fail("waiter did not enter Ref");
                Thread.yield();
            }
            waiter.interrupt();
            waiter.join(5000);
            assertFalse("waiter did not finish", waiter.isAlive());
            assertTrue(waiterError.get() instanceof MeguvizRefResolutionException);
            assertEquals("wait_interrupted", ((MeguvizRefResolutionException) waiterError.get()).receipt().reason());
            assertTrue("Ref restored waiter interrupt", waiterInterrupted.get());
            assertEquals(MeguvizRefResolverSession.State.DRAINING, session.report().state());
            assertEquals(1, session.report().inFlight());
        } finally {
            ownerRelease.countDown();
            if (owner.getState() != Thread.State.NEW) owner.join(5000);
            if (waiter.getState() != Thread.State.NEW) waiter.join(5000);
            session.close();
        }
        assertFalse(owner.isAlive()); assertFalse(waiter.isAlive());
        assertTrue(ownerError.get() instanceof BackendInstallException);
        assertEquals(MeguvizRefResolverSession.State.CLOSED, session.report().state());
        assertEquals(CodeScope.State.CLOSED, scope.get().state());
        assertEquals(1, closes.get());
    }
}
