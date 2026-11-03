package io.github.jichuo1.meguviz.hook.ref;

import io.github.jichuo1.meguviz.hook.api.*;
import io.github.jichuo1.meguviz.hook.core.*;
import io.github.jichuo1.meguviz.hook.core.spi.*;
import io.github.jichuo1.meguviz.hook.ref.MeguvizRefResolverSession;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind;
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.api.query.Selection;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One controlled close-during-prepare race; actual Ref session with a fake backend. */
public final class PreparedRealRefCloseTest {
    public static int alpha() { return 1; }
    public static int beta() { return 2; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static String classHash(Class<?> type) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (input == null) throw new IllegalStateException("missing class resource");
            byte[] buffer = new byte[4096];
            for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        StringBuilder text = new StringBuilder();
        for (byte value : digest.digest()) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
    private static HookSpec spec(String id, String name) {
        TargetSpec target = TargetSpec.descriptor(TargetKind.METHOD, PreparedRealRefCloseTest.class.getName(), name)
                .parameters().returning("int").match(MatchPolicy.UNIQUE).build();
        return HookSpec.builder(id, "real-ref-close", target).replaceConstant(9).build();
    }
    private static final class Context implements BackendContext {
        @Override public String processName() { return "test.process"; }
        @Override public ClassLoader classLoader() { return PreparedRealRefCloseTest.class.getClassLoader(); }
        @Override public long codeEpoch() { return 1; }
    }
    private static final class Backend implements HookBackend {
        final FakeBackend fake = FakeBackend.create();
        final Context context = new Context();
        @Override public String id() { return fake.id(); }
        @Override public BackendCapabilities capabilities() { return fake.capabilities(); }
        @Override public BackendContext context() { return context; }
        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            return fake.install(plan, channel);
        }
    }
    private static void await(CountDownLatch latch) throws BackendInstallException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new BackendInstallException("timeout");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new BackendInstallException("interrupted", failure);
        }
    }
    @org.junit.Test public void realRefContract() throws Throwable {
        System.out.println("IDENTITY env=" + classHash(HookEnvironment.class)
                + " session=" + classHash(MeguvizRefResolverSession.class) + " ref=" + classHash(CodeScope.class));
        Backend backend = new Backend();
        backend.fake.setClassLoader(backend.context.classLoader());
        AtomicReference<CodeScope> scope = new AtomicReference<CodeScope>();
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(backend.context, loader -> {
            CodeScope value = CodeScope.open(loader);
            check(scope.compareAndSet(null, value), "unexpected second scope");
            return value;
        }, (target, owner) -> {
            entered.countDown(); await(release);
            return QuerySpec.builder(owner, MemberKind.METHOD).name(target.memberName())
                    .selection(Selection.ALL).resultLimit(128).build();
        });
        HookEnvironment env = HookEnvironment.create(backend, session, DiagnosticsSink.NONE);
        AtomicReference<HookEnvironment.Preparation> alpha = new AtomicReference<HookEnvironment.Preparation>();
        AtomicReference<HookEnvironment.Preparation> beta = new AtomicReference<HookEnvironment.Preparation>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread first = new Thread(() -> {
            try { alpha.set(env.prepareConcurrently(spec("a", "alpha"), 4)); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "real-ref-close-alpha");
        Thread second = new Thread(() -> {
            try { beta.set(env.prepareConcurrently(spec("b", "beta"), 4)); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "real-ref-close-beta");
        try {
            first.start(); second.start();
            check(entered.await(5, TimeUnit.SECONDS), "both queries did not enter");
            MeguvizRefResolverSession.CloseReport closing = session.requestClose();
            check(closing.state() == MeguvizRefResolverSession.State.DRAINING && closing.inFlight() == 2,
                    "session did not enter DRAINING with both leases");
            release.countDown(); first.join(7000); second.join(7000);
            check(!first.isAlive() && !second.isAlive() && failure.get() == null, "worker failed: " + failure.get());
            check(alpha.get().status() == HookEnvironment.PreparationStatus.RESOLUTION_FAILED && alpha.get().prepared() == null,
                    "alpha not rejected after session close");
            check(beta.get().status() == HookEnvironment.PreparationStatus.RESOLUTION_FAILED && beta.get().prepared() == null,
                    "beta not rejected after session close");
            check(session.report().state() == MeguvizRefResolverSession.State.CLOSED && scope.get().state() == CodeScope.State.CLOSED,
                    "scope failed to drain");
            check(backend.fake.installCount() == 0, "unexpected backend installation");
            System.out.println("REAL_REF_CLOSE_RACE_OK admitted=2 result=RESOLUTION_FAILED scope=CLOSED");
        } finally {
            release.countDown(); first.join(7000); second.join(7000);
            env.close(); session.close();
        }
    }
}
