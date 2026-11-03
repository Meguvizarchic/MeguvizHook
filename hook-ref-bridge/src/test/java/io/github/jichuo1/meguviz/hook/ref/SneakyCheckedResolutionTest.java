package io.github.jichuo1.meguviz.hook.ref;

import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
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
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** A JVM checked throwable may escape an SPI method that does not declare it, as in Kotlin. */
public final class SneakyCheckedResolutionTest {
    public static final class Host { public String target(String text) { return text; } }
    private static final ClassLoader LOADER = Host.class.getClassLoader();
    private static final TargetSpec.DescriptorTarget TARGET = TargetSpec.descriptor(TargetKind.METHOD,
            Host.class.getName(), "target").parameters("java.lang.String").returning("java.lang.String").build();

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneaky(Throwable failure) throws E { throw (E) failure; }

    private static final class Context implements BackendContext {
        volatile Throwable loaderFailure;
        @Override public String processName() { return "test"; }
        @Override public ClassLoader classLoader() {
            Throwable failure = loaderFailure;
            return failure == null ? LOADER : sneaky(failure);
        }
        @Override public long codeEpoch() { return 4; }
    }

    private static final class Provider implements CandidateProvider {
        final AtomicBoolean armed = new AtomicBoolean();
        final AtomicInteger closes = new AtomicInteger();
        final Throwable descriptorFailure;
        final Throwable cleanupFailure;
        Provider(Throwable descriptorFailure, Throwable cleanupFailure) {
            this.descriptorFailure = descriptorFailure; this.cleanupFailure = cleanupFailure;
        }
        @Override public ProviderDescriptor descriptor() {
            if (armed.get() && descriptorFailure != null) return sneaky(descriptorFailure);
            return new ProviderDescriptor("probe", "1", 1, 0, "", Collections.emptyList());
        }
        @Override public CandidateBatch discover(CandidateRequest request) {
            return new CandidateBatch(Collections.emptyList(), request.codeFingerprint(), "probe", false);
        }
        @Override public void close() {
            closes.incrementAndGet();
            if (cleanupFailure != null) sneaky(cleanupFailure);
        }
    }

    private static CodeScope scope(ClassLoader loader, Provider provider, AtomicReference<CodeScope> opened) {
        CodeScope scope = CodeScope.open(loader);
        scope.installProviders(ProviderRegistry.create(Collections.singletonList(provider)),
                CodeIdentity.strong(Collections.singletonMap("test", "a".repeat(64)), "loader", "v1"));
        opened.set(scope);
        provider.armed.set(true);
        return scope;
    }

    private static QuerySpec query(Class<?> owner) {
        return QuerySpec.builder(owner, MemberKind.METHOD).name("target").provider("probe")
                .selection(Selection.ALL).resultLimit(10).build();
    }

    @Test public void createClosesOwnedScopeWhenSecondIdentityGetterThrowsChecked() {
        Context context = new Context();
        IOException failure = new IOException("identity getter");
        Provider provider = new Provider(null, null);
        AtomicReference<CodeScope> opened = new AtomicReference<>();
        assertSame(failure, assertThrows(IOException.class, () -> MeguvizRefResolverSession.withQueries(context, loader -> {
            CodeScope scope = scope(loader, provider, opened);
            context.loaderFailure = failure;
            return scope;
        }, (target, owner) -> query(owner))));
        assertNotNull(opened.get());
        assertEquals(CodeScope.State.CLOSED, opened.get().state());
        assertEquals(1, provider.closes.get());
    }

    @Test public void createFatalCleanupTakesPriorityOverCheckedIdentityFailure() {
        Context context = new Context();
        IOException primary = new IOException("identity getter");
        SyntheticFatal cleanup = new SyntheticFatal("cleanup");
        Provider provider = new Provider(null, cleanup);
        AtomicReference<CodeScope> opened = new AtomicReference<>();
        assertSame(cleanup, assertThrows(SyntheticFatal.class, () -> MeguvizRefResolverSession.withQueries(context, loader -> {
            CodeScope scope = scope(loader, provider, opened);
            context.loaderFailure = primary;
            return scope;
        }, (target, owner) -> query(owner))));
        assertArrayEquals(new Throwable[] { primary }, cleanup.getSuppressed());
        assertEquals(CodeScope.State.CLOSED, opened.get().state());
        assertEquals(1, provider.closes.get());
    }

    @Test public void sneakyInterruptedIdentityRetiresSessionAndPreservesInterruptFlag() throws Exception {
        Context context = new Context();
        InterruptedException failure = new InterruptedException("identity getter");
        Provider provider = new Provider(null, null);
        AtomicReference<CodeScope> opened = new AtomicReference<>();
        MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(context,
                loader -> scope(loader, provider, opened), (target, owner) -> query(owner));
        context.loaderFailure = failure;
        AtomicReference<Throwable> observed = new AtomicReference<>();
        AtomicBoolean before = new AtomicBoolean(true), after = new AtomicBoolean(true);
        Thread worker = new Thread(() -> {
            before.set(Thread.currentThread().isInterrupted());
            try { session.resolve(TARGET, LOADER); }
            catch (Throwable error) { observed.set(error); }
            after.set(Thread.currentThread().isInterrupted());
        }, "sneaky-ref-identity");
        worker.setDaemon(true);
        worker.start(); worker.join(5000);
        assertFalse("resolution did not finish", worker.isAlive());
        assertSame(failure, observed.get());
        assertFalse(before.get());
        assertFalse("bridge must not invent an interrupt flag", after.get());
        assertEquals(MeguvizRefResolverSession.State.CLOSED, session.report().state());
        assertEquals(0, session.report().inFlight());
        assertEquals(CodeScope.State.CLOSED, opened.get().state());
        assertEquals(1, provider.closes.get());
        assertThrows(BackendInstallException.class, () -> session.resolve(TARGET, LOADER));
        session.close();
    }

    @Test public void checkedQueryFactoryFailureReleasesLeaseAndRecordsCleanupFailure() throws Exception {
        Context context = new Context();
        IOException primary = new IOException("query factory");
        IllegalStateException cleanup = new IllegalStateException("provider cleanup");
        Provider provider = new Provider(null, cleanup);
        AtomicReference<CodeScope> opened = new AtomicReference<>();
        AtomicReference<MeguvizRefResolverSession> owner = new AtomicReference<>();
        MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(context,
                loader -> scope(loader, provider, opened), (target, type) -> {
                    assertEquals(1, owner.get().report().inFlight());
                    return sneaky(primary);
                });
        owner.set(session);
        assertSame(primary, assertThrows(IOException.class, () -> session.resolve(TARGET, LOADER)));
        assertEquals(0, session.report().inFlight());
        assertEquals(MeguvizRefResolverSession.State.FAILED, session.report().state());
        assertSame(cleanup, session.report().failure());
        assertEquals(CodeScope.State.CLOSED, opened.get().state());
        assertEquals(1, provider.closes.get());
        assertSame(cleanup, assertThrows(IllegalStateException.class, session::close).getCause());
        assertEquals(1, provider.closes.get());
    }

    @Test public void sharedSessionFatalCleanupTakesPriorityOverCheckedQueryFailure() throws Exception {
        Context context = new Context();
        IOException primary = new IOException("query factory");
        SyntheticFatal cleanup = new SyntheticFatal("cleanup");
        Provider provider = new Provider(null, cleanup);
        AtomicReference<CodeScope> opened = new AtomicReference<>();
        MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(context,
                loader -> scope(loader, provider, opened), (target, owner) -> sneaky(primary));
        assertSame(cleanup, assertThrows(SyntheticFatal.class, () -> session.resolve(TARGET, LOADER)));
        assertArrayEquals(new Throwable[] { primary }, cleanup.getSuppressed());
        assertEquals(MeguvizRefResolverSession.State.FAILED, session.report().state());
        assertEquals(0, session.report().inFlight());
        assertSame(cleanup, session.report().failure());
        assertEquals(CodeScope.State.CLOSED, opened.get().state());
        assertEquals(1, provider.closes.get());
    }

    @Test public void ownedScopeCheckedFailureKeepsIdentityAndSuppressesCleanup() {
        IOException primary = new IOException("provider descriptor");
        IllegalStateException cleanup = new IllegalStateException("provider cleanup");
        Provider provider = new Provider(primary, cleanup);
        AtomicReference<CodeScope> opened = new AtomicReference<>();
        MeguvizRefMemberResolver resolver = MeguvizRefMemberResolver.withQueries(
                loader -> scope(loader, provider, opened), (target, owner) -> query(owner));
        assertSame(primary, assertThrows(IOException.class, () -> resolver.resolve(TARGET, LOADER)));
        assertArrayEquals(new Throwable[] { cleanup }, primary.getSuppressed());
        assertEquals(CodeScope.State.CLOSED, opened.get().state());
        assertEquals(1, provider.closes.get());
    }

    @Test public void ownedScopeFatalCleanupWinsAndDoubleFatalKeepsPrimary() {
        IOException checked = new IOException("provider descriptor");
        SyntheticFatal fatalCleanup = new SyntheticFatal("cleanup");
        assertFatalCleanup(checked, fatalCleanup, fatalCleanup, checked);
        SyntheticFatal fatalPrimary = new SyntheticFatal("primary");
        SyntheticFatal secondFatal = new SyntheticFatal("cleanup");
        assertFatalCleanup(fatalPrimary, secondFatal, fatalPrimary, secondFatal);
    }

    private static void assertFatalCleanup(Throwable primary, Throwable cleanup, Throwable expected,
                                           Throwable expectedSuppressed) {
        Provider provider = new Provider(primary, cleanup);
        AtomicReference<CodeScope> opened = new AtomicReference<>();
        MeguvizRefMemberResolver resolver = MeguvizRefMemberResolver.withQueries(
                loader -> scope(loader, provider, opened), (target, owner) -> query(owner));
        assertSame(expected, assertThrows(SyntheticFatal.class, () -> resolver.resolve(TARGET, LOADER)));
        assertArrayEquals(new Throwable[] { expectedSuppressed }, expected.getSuppressed());
        assertEquals(CodeScope.State.CLOSED, opened.get().state());
        assertEquals(1, provider.closes.get());
    }

    private static final class SyntheticFatal extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
        SyntheticFatal(String message) { super(message); }
    }
}
