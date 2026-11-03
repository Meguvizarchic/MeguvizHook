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
import java.lang.reflect.Method;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real Ref/session with a fake backend; no Android, real manager or performance claim. */
public final class PreparedRealRefSmokeTest {
    public static int alpha() { return 1; }
    public static int beta() { return 2; }
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
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
        TargetSpec target = TargetSpec.descriptor(TargetKind.METHOD, PreparedRealRefSmokeTest.class.getName(), name)
                .parameters().returning("int").match(MatchPolicy.UNIQUE).build();
        return HookSpec.builder(id, "real-ref-smoke", target).replaceConstant(9).build();
    }
    private static final class Context implements BackendContext {
        volatile ClassLoader loader = PreparedRealRefSmokeTest.class.getClassLoader();
        volatile long epoch = 1;
        @Override public String processName() { return "test.process"; }
        @Override public ClassLoader classLoader() { return loader; }
        @Override public long codeEpoch() { return epoch; }
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
            if (!latch.await(5, TimeUnit.SECONDS)) throw new BackendInstallException("query overlap timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new BackendInstallException("interrupted", failure);
        }
    }
    @org.junit.Test public void realRefContract() throws Throwable {
        System.out.println("IDENTITY env=" + classHash(HookEnvironment.class)
                + " session=" + classHash(MeguvizRefResolverSession.class) + " ref=" + classHash(CodeScope.class));
        Backend backend = new Backend();
        Method alpha = PreparedRealRefSmokeTest.class.getDeclaredMethod("alpha");
        Method beta = PreparedRealRefSmokeTest.class.getDeclaredMethod("beta");
        backend.fake.setClassLoader(backend.context.loader);
        backend.fake.onTarget(alpha, (receiver, values) -> 1);
        backend.fake.onTarget(beta, (receiver, values) -> 2);
        AtomicReference<CodeScope> scope = new AtomicReference<CodeScope>();
        AtomicInteger factoryCalls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(2);
        MeguvizRefResolverSession session = MeguvizRefResolverSession.withQueries(backend.context, loader -> {
            CodeScope result = CodeScope.open(loader);
            check(scope.compareAndSet(null, result), "unexpected scope creation");
            return result;
        }, (target, owner) -> {
            if (factoryCalls.incrementAndGet() <= 2) { entered.countDown(); await(entered); }
            return QuerySpec.builder(owner, MemberKind.METHOD).name(target.memberName())
                    .selection(Selection.ALL).resultLimit(128).build();
        });
        HookEnvironment env = HookEnvironment.create(backend, session, DiagnosticsSink.NONE);
        AtomicReference<HookEnvironment.Preparation> a = new AtomicReference<HookEnvironment.Preparation>();
        AtomicReference<HookEnvironment.Preparation> b = new AtomicReference<HookEnvironment.Preparation>();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread first = new Thread(() -> {
            try { a.set(env.prepareConcurrently(spec("alpha", "alpha"), 4)); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "real-ref-prepare-alpha");
        Thread second = new Thread(() -> {
            try { b.set(env.prepareConcurrently(spec("beta", "beta"), 4)); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "real-ref-prepare-beta");
        try {
            first.start(); second.start(); first.join(7000); second.join(7000);
            check(!first.isAlive() && !second.isAlive() && failure.get() == null, "parallel preparation failed: " + failure.get());
            check(a.get().status() == HookEnvironment.PreparationStatus.READY, "alpha prepare: " + a.get().status());
            check(b.get().status() == HookEnvironment.PreparationStatus.READY, "beta prepare: " + b.get().status());
            check(entered.getCount() == 0, "query factory did not overlap");
            check(a.get().prepared().commit().isInstalled() && b.get().prepared().commit().isInstalled(), "commit failed");
            check(backend.fake.call(alpha, null).equals(9) && backend.fake.call(beta, null).equals(9), "hook results");
            HookEnvironment.Preparation held = env.prepareConcurrently(spec("alpha-held", "alpha"), 4);
            check(held.status() == HookEnvironment.PreparationStatus.READY, "repeated prepare");
            Map<String, Long> stats = scope.get().diagnostics();
            check(stats.get("cacheHits") >= 1 && stats.get("cachedQueries") >= 2, "cache not used: " + stats);
            System.out.println("REF_STATS cacheHits=" + stats.get("cacheHits") + " cachedQueries=" + stats.get("cachedQueries"));
            check(session.requestClose().state() == MeguvizRefResolverSession.State.CLOSED, "session close");
            check(scope.get().state() == CodeScope.State.CLOSED, "scope close");
            // Resolution completed before session close; the ready token remains valid while environment/epoch remain valid.
            check(held.prepared().commit().isInstalled(), "ready token after session close");
            check(env.prepareConcurrently(spec("after-close", "alpha"), 4).status()
                    == HookEnvironment.PreparationStatus.RESOLUTION_FAILED, "closed session accepted new resolution");
            check(backend.fake.installCount() == 3, "unexpected backend installation count");
            System.out.println("REAL_REF_PREPARE_SMOKE_OK ready_token_survives_session_close=true");
        } finally {
            first.join(7000); second.join(7000);
            env.close(); session.close();
        }
    }
}
