package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.*;
import io.github.jichuo1.meguviz.hook.core.*;
import io.github.jichuo1.meguviz.hook.core.spi.*;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** A focused fake-backend contract matrix, not real framework, ART or performance evidence. */
public final class PreparedInstallContractTest {
    public static int first() { return 1; }
    public static int second() { return 2; }
    private static final Method FIRST;
    private static final Method SECOND;
    private static final AtomicInteger INITIALIZATIONS = new AtomicInteger();
    public static final class InitTarget { static { INITIALIZATIONS.incrementAndGet(); } }
    static {
        try {
            FIRST = PreparedInstallContractTest.class.getDeclaredMethod("first");
            SECOND = PreparedInstallContractTest.class.getDeclaredMethod("second");
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private interface Task { void run() throws Throwable; }
    private static int failed;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void run(String name, Task task) {
        try { task.run(); System.out.println("CASE " + name + " PASS"); }
        catch (Throwable failure) {
            failed++;
            System.out.println("CASE " + name + " FAIL " + failure.getClass().getName() + ":" + failure.getMessage());
            failure.printStackTrace(System.err);
        }
    }
    private static void await(CountDownLatch latch, String message) {
        try { check(latch.await(5, TimeUnit.SECONDS), message); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static HookSpec.Builder builder(String id, MatchPolicy policy) {
        TargetSpec descriptor = TargetSpec.descriptor(TargetKind.METHOD, PreparedInstallContractTest.class.getName(), "x")
                .parameters().returning("int").match(policy).build();
        return HookSpec.builder(id, "contract", descriptor).replaceConstant(7);
    }
    private static HookSpec spec(String id, MatchPolicy policy) { return builder(id, policy).build(); }

    private static final class Context implements BackendContext {
        volatile ClassLoader loader = PreparedInstallContractTest.class.getClassLoader();
        volatile long epoch = 1;
        volatile String process = "allowed";
        @Override public String processName() { return process; }
        @Override public ClassLoader classLoader() { return loader; }
        @Override public long codeEpoch() { return epoch; }
    }
    private static final class Backend implements HookBackend {
        final FakeBackend fake = FakeBackend.create();
        final Context context = new Context();
        volatile Executable failTarget;
        volatile boolean bumpOnInstall;
        volatile Runnable onInstall;
        final AtomicInteger installs = new AtomicInteger();
        @Override public String id() { return fake.id(); }
        @Override public BackendCapabilities capabilities() { return fake.capabilities(); }
        @Override public BackendContext context() { return context; }
        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            if (onInstall != null) onInstall.run();
            if (plan.target().equals(failTarget)) throw new BackendInstallException("selected failure");
            BackendHandle handle = fake.install(plan, channel);
            installs.incrementAndGet();
            if (bumpOnInstall) context.epoch++;
            return handle;
        }
    }
    private static HookEnvironment env(Backend backend, MemberResolver resolver) {
        return HookEnvironment.create(backend, resolver, DiagnosticsSink.NONE);
    }
    private static List<Executable> both() { return Arrays.<Executable>asList(SECOND, FIRST); }
    private static HookEnvironment.Preparation ready(HookEnvironment env, HookSpec spec) {
        HookEnvironment.Preparation p = env.prepareConcurrently(spec, 4);
        check(p.status() == HookEnvironment.PreparationStatus.READY && p.prepared() != null, "ready");
        return p;
    }

    private static void firstOrderAndSnapshot() {
        Backend backend = new Backend();
        List<Executable> source = new ArrayList<Executable>(both());
        HookEnvironment env = env(backend, (t, l) -> source);
        try {
            HookEnvironment.Preparation p = ready(env, spec("first", MatchPolicy.FIRST));
            source.clear();
            RegistrationReceipt result = p.prepared().commit();
            check(result.isInstalled() && result.targets().size() == 1, "first receipt");
            check(result.targets().get(0).target().equals(SECOND), "source order / immutable snapshot");
            check(backend.fake.isHooked(SECOND) && !backend.fake.isHooked(FIRST), "wrong target hooked");
        } finally { env.close(); }
    }
    private static void uniqueRejectsAmbiguous() {
        Backend backend = new Backend(); HookEnvironment env = env(backend, (t,l) -> both());
        try {
            RegistrationReceipt r = ready(env, spec("unique", MatchPolicy.UNIQUE)).prepared().commit();
            check(r.status() == RegistrationReceipt.Status.AMBIGUOUS_TARGET, "unique ambiguity");
            check(backend.installs.get() == 0, "ambiguous installed");
        } finally { env.close(); }
    }
    private static void allPartialOrder() {
        Backend backend = new Backend(); backend.failTarget = FIRST;
        HookEnvironment env = env(backend, (t,l) -> both());
        try {
            HookEnvironment.PreparedInstall token = ready(env, spec("all", MatchPolicy.ALL)).prepared();
            RegistrationReceipt r = token.commit();
            check(r.status() == RegistrationReceipt.Status.PARTIALLY_INSTALLED, "partial status");
            check(r.registration() != null && r.targets().size() == 2, "partial receipt");
            check(r.targets().get(0).target().equals(SECOND) && r.targets().get(0).status() == TargetInstallReceipt.Status.INSTALLED,
                    "first target/order");
            check(r.targets().get(1).target().equals(FIRST) && r.targets().get(1).status() == TargetInstallReceipt.Status.FAILED,
                    "second target/order");
            check(backend.fake.isHooked(SECOND) && !backend.fake.isHooked(FIRST), "partial ownership");
            try { token.commit(); throw new AssertionError("partial token replayed"); }
            catch (IllegalStateException expected) { }
        } finally { env.close(); }
    }
    private static void allRetainsDuplicates() {
        Backend backend = new Backend();
        HookEnvironment env = env(backend, (t,l) -> Arrays.<Executable>asList(FIRST, FIRST));
        try {
            RegistrationReceipt r = ready(env, spec("duplicate", MatchPolicy.ALL)).prepared().commit();
            check(r.isInstalled() && r.targets().size() == 2, "duplicate status/count");
            check(r.targets().get(0).target().equals(FIRST) && r.targets().get(1).target().equals(FIRST), "duplicate order");
            check(backend.installs.get() == 2, "duplicate removed");
        } finally { env.close(); }
    }
    private static void resultBoundsAndNull() {
        Backend backend = new Backend();
        final AtomicReference<List<Executable>> results = new AtomicReference<List<Executable>>(both());
        HookEnvironment env = env(backend, (t,l) -> results.get());
        try {
            HookEnvironment.Preparation limit = env.prepareConcurrently(spec("limit", MatchPolicy.ALL), 1);
            check(limit.status() == HookEnvironment.PreparationStatus.TOO_MANY && limit.prepared() == null, "too many");
            results.set(Arrays.<Executable>asList(FIRST, null));
            HookEnvironment.Preparation item = env.prepareConcurrently(spec("null_item", MatchPolicy.ALL), 2);
            check(item.status() == HookEnvironment.PreparationStatus.INVALID_RESULT && item.prepared() == null, "null member");
            results.set(null);
            check(env.prepareConcurrently(spec("null_list", MatchPolicy.ALL), 2).status()
                    == HookEnvironment.PreparationStatus.INVALID_RESULT, "null list");
            results.set(Collections.<Executable>emptyList());
            HookEnvironment.Preparation empty = env.prepareConcurrently(spec("empty", MatchPolicy.ALL), 2);
            check(empty.status() == HookEnvironment.PreparationStatus.NOT_FOUND_NOW && empty.prepared() == null, "empty");
            try { env.prepareConcurrently(spec("bad_limit", MatchPolicy.ALL), 0); throw new AssertionError("bad bound"); }
            catch (IllegalArgumentException expected) { }
            check(backend.installs.get() == 0, "invalid input installed");
        } finally { env.close(); }
    }
    private static void processAndCapabilityOrdering() {
        Backend backend = new Backend(); final AtomicInteger queries = new AtomicInteger();
        HookEnvironment env = env(backend, (t,l) -> { queries.incrementAndGet(); return both(); });
        try {
            HookSpec filtered = builder("filtered", MatchPolicy.FIRST).processes(ProcessFilter.only("other")).build();
            check(env.prepareConcurrently(filtered, 4).status() == HookEnvironment.PreparationStatus.FILTERED, "filter");
            HookSpec unsupported = builder("unsupported", MatchPolicy.FIRST).require(Capability.HOT_RELOAD).build();
            check(env.prepareConcurrently(unsupported, 4).status() == HookEnvironment.PreparationStatus.UNSUPPORTED, "capability");
            check(queries.get() == 0, "resolver ran before filter/capability");
            HookSpec allowed = builder("commit-filtered", MatchPolicy.FIRST).processes(ProcessFilter.only("allowed")).build();
            HookEnvironment.Preparation p = ready(env, allowed);
            check(queries.get() == 1, "resolver count");
            backend.context.process = "other";
            check(p.prepared().commit().status() == RegistrationReceipt.Status.FILTERED, "commit did not recheck filter");
            check(backend.installs.get() == 0 && queries.get() == 1, "commit side effect");
        } finally { env.close(); }
    }
    private static void epochBeforeAndDuringCommit() {
        Backend backend = new Backend(); HookEnvironment env = env(backend, (t,l) -> Collections.<Executable>singletonList(FIRST));
        try {
            HookEnvironment.Preparation stale = ready(env, spec("stale", MatchPolicy.UNIQUE));
            backend.context.epoch++;
            check(stale.prepared().commit().status() == RegistrationReceipt.Status.STALE_IDENTITY, "epoch before commit");
            check(backend.installs.get() == 0, "stale installed");
            HookEnvironment.Preparation inInstall = ready(env, spec("stale-after", MatchPolicy.UNIQUE));
            backend.bumpOnInstall = true;
            RegistrationReceipt result = inInstall.prepared().commit();
            check(result.status() == RegistrationReceipt.Status.STALE_IDENTITY, "epoch during install");
            check(result.targets().size() == 1 && result.targets().get(0).status() == TargetInstallReceipt.Status.ROLLED_BACK,
                    "stale rollback receipt");
            check(!backend.fake.isHooked(FIRST), "stale backend hook remains");
        } finally { env.close(); }
    }
    private static void closeBeforeCommit() {
        Backend backend = new Backend(); HookEnvironment env = env(backend, (t,l) -> Collections.<Executable>singletonList(FIRST));
        HookEnvironment.PreparedInstall token = ready(env, spec("close-first", MatchPolicy.UNIQUE)).prepared();
        env.close();
        check(token.commit().status() == RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, "closed environment committed");
        check(backend.installs.get() == 0, "backend installed after close");
        try { token.commit(); throw new AssertionError("closed commit replayed"); } catch (IllegalStateException expected) { }
    }
    private static void checkedAndFatalResolverErrors() {
        Backend backend = new Backend();
        final AtomicInteger attempts = new AtomicInteger();
        final BackendInstallException checked = new BackendInstallException("provider failure");
        final OutOfMemoryError fatal = new OutOfMemoryError("synthetic fatal");
        HookEnvironment env = env(backend, (t,l) -> {
            if (attempts.incrementAndGet() == 1) throw checked;
            throw fatal;
        });
        try {
            HookEnvironment.Preparation result = env.prepareConcurrently(spec("checked", MatchPolicy.UNIQUE), 4);
            check(result.status() == HookEnvironment.PreparationStatus.RESOLUTION_FAILED && result.failure() == checked
                    && result.prepared() == null, "checked resolver failure identity");
            try { env.prepareConcurrently(spec("fatal", MatchPolicy.UNIQUE), 4); throw new AssertionError("fatal swallowed"); }
            catch (OutOfMemoryError observed) { check(observed == fatal, "fatal identity"); }
            check(attempts.get() == 2 && backend.installs.get() == 0, "unexpected retry/install");
        } finally { env.close(); }
    }
    private static void reentrantResolverClose() {
        Backend backend = new Backend();
        AtomicReference<HookEnvironment> ref = new AtomicReference<HookEnvironment>();
        HookEnvironment env = env(backend, (t,l) -> {
            check(!Thread.holdsLock(ref.get()), "resolver under environment lock");
            ref.get().close();
            return Collections.<Executable>singletonList(FIRST);
        });
        ref.set(env);
        HookEnvironment.Preparation p = env.prepareConcurrently(spec("reentrant", MatchPolicy.UNIQUE), 4);
        check(p.status() == HookEnvironment.PreparationStatus.CLOSED && p.prepared() == null, "reentrant close result");
        check(backend.installs.get() == 0, "reentrant close installed");
    }
    private static void preflightPrecedesResolverAndInitialization() {
        Backend backend = new Backend(); AtomicInteger queries = new AtomicInteger();
        String name = PreparedInstallContractTest.class.getName() + "$InitTarget";
        MemberResolver resolver = (t,l) -> {
            queries.incrementAndGet();
            try { Class.forName(name, true, l); }
            catch (ClassNotFoundException e) { throw new AssertionError(e); }
            return Collections.<Executable>singletonList(FIRST);
        };
        HookEnvironment env = env(backend, resolver);
        try {
            HookSpec filtered = builder("init-filtered", MatchPolicy.FIRST).processes(ProcessFilter.only("other")).build();
            HookSpec unsupported = builder("init-unsupported", MatchPolicy.FIRST).require(Capability.HOT_RELOAD).build();
            check(env.prepareConcurrently(filtered, 4).status() == HookEnvironment.PreparationStatus.FILTERED, "filtered");
            check(env.prepareConcurrently(unsupported, 4).status() == HookEnvironment.PreparationStatus.UNSUPPORTED, "unsupported");
            check(queries.get() == 0 && INITIALIZATIONS.get() == 0, "resolver or initializer ran early");
            HookEnvironment.Preparation ready = env.prepareConcurrently(spec("init-allowed", MatchPolicy.FIRST), 4);
            check(ready.status() == HookEnvironment.PreparationStatus.READY && queries.get() == 1 && INITIALIZATIONS.get() == 1,
                    "allowed resolver or initialization missing");
            ready.prepared().close();
        } finally { env.close(); }
    }
    private static void capabilityRecheckedBeforeCommit() {
        Backend backend = new Backend();
        backend.fake.withCapability(Capability.HOT_RELOAD, CapabilityResult.of(
                CapabilityResult.Status.SUPPORTED_IN_DECLARED_CELL, backend.id(), "fake", "test", "synthetic"));
        AtomicInteger calls = new AtomicInteger();
        HookEnvironment env = env(backend, (t,l) -> { calls.incrementAndGet(); return Collections.<Executable>singletonList(FIRST); });
        try {
            HookSpec required = builder("recheck", MatchPolicy.UNIQUE).require(Capability.HOT_RELOAD).build();
            HookEnvironment.PreparedInstall token = ready(env, required).prepared();
            backend.fake.withCapability(Capability.HOT_RELOAD, CapabilityResult.unsupported(backend.id(), "revoked"));
            check(token.commit().status() == RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY, "capability not rechecked");
            check(calls.get() == 1 && backend.installs.get() == 0, "commit repeated lookup or installed");
        } finally { env.close(); }
    }
    private static void changedWhilePreparing(int change) throws Throwable {
        Backend backend = new Backend(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        HookEnvironment env = env(backend, (t,l) -> { entered.countDown(); await(release, "resolver release"); return both(); });
        AtomicReference<HookEnvironment.Preparation> result = new AtomicReference<HookEnvironment.Preparation>();
        AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> { try { result.set(env.prepareConcurrently(spec("blocked", MatchPolicy.FIRST), 4)); }
                                           catch (Throwable t) { error.set(t); } });
        try {
            worker.start(); await(entered, "resolver entered");
            if (change == 0) env.close();
            else if (change == 1) backend.context.epoch++;
            else backend.context.loader = new ClassLoader(PreparedInstallContractTest.class.getClassLoader()) { };
            release.countDown(); worker.join(6000);
            check(!worker.isAlive() && error.get() == null, "worker failed");
            check(result.get().status() == (change == 0 ? HookEnvironment.PreparationStatus.CLOSED : HookEnvironment.PreparationStatus.STALE_IDENTITY),
                    "close/epoch result");
            check(result.get().prepared() == null && backend.installs.get() == 0, "closed or stale token");
        } finally { release.countDown(); worker.join(6000); env.close(); }
    }
    private static void commitCancelRace() throws Throwable {
        Backend backend = new Backend(); HookEnvironment env = env(backend, (t,l) -> Collections.<Executable>singletonList(FIRST));
        try {
            HookEnvironment.PreparedInstall token = ready(env, spec("race", MatchPolicy.UNIQUE)).prepared();
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<RegistrationReceipt> receipt = new AtomicReference<RegistrationReceipt>();
            AtomicReference<Throwable> error = new AtomicReference<Throwable>();
            Thread commit = new Thread(() -> { await(start, "start"); try { receipt.set(token.commit()); } catch (Throwable t) { error.set(t); } });
            Thread cancel = new Thread(() -> { await(start, "start"); token.close(); });
            commit.start(); cancel.start(); start.countDown(); commit.join(6000); cancel.join(6000);
            check(!commit.isAlive() && !cancel.isAlive(), "race did not terminate");
            if (receipt.get() != null) check(receipt.get().isInstalled() && error.get() == null && backend.installs.get() == 1, "commit won");
            else check(error.get() instanceof IllegalStateException && backend.installs.get() == 0, "cancel won");
            Field payload = token.getClass().getDeclaredField("payload"); payload.setAccessible(true);
            check(payload.get(token) == null, "terminal token retained payload");
            try { token.commit(); throw new AssertionError("token reused"); } catch (IllegalStateException expected) { }
        } finally { env.close(); }
    }
    private static void tokenReferencesReleaseAcrossTerminalOutcomes() throws Exception {
        Backend backend = new Backend(); HookEnvironment env = env(backend, (t,l) -> Collections.<Executable>singletonList(FIRST));
        try {
            Field payload = HookEnvironment.PreparedInstall.class.getDeclaredField("payload");
            payload.setAccessible(true);
            HookEnvironment.PreparedInstall cancelled = ready(env, spec("refs-cancel", MatchPolicy.UNIQUE)).prepared();
            cancelled.close();
            check(payload.get(cancelled) == null, "cancel retained payload");
            HookEnvironment.PreparedInstall committed = ready(env, spec("refs-commit", MatchPolicy.UNIQUE)).prepared();
            check(committed.commit().isInstalled(), "commit");
            check(payload.get(committed) == null, "success retained payload");
            HookEnvironment.PreparedInstall stale = ready(env, spec("refs-stale", MatchPolicy.UNIQUE)).prepared();
            backend.context.epoch++;
            check(stale.commit().status() == RegistrationReceipt.Status.STALE_IDENTITY, "stale status");
            check(payload.get(stale) == null, "stale retained payload");
        } finally { env.close(); }
    }
    private static void reentrantBackendTokenClose() {
        Backend backend = new Backend(); HookEnvironment env = env(backend, (t,l) -> Collections.<Executable>singletonList(FIRST));
        try {
            HookEnvironment.PreparedInstall token = ready(env, spec("reenter-token", MatchPolicy.UNIQUE)).prepared();
            backend.onInstall = () -> {
                check(!Thread.holdsLock(token), "backend called with token lock held");
                token.close();
            };
            check(token.commit().isInstalled(), "reentrant token close prevented claimed commit");
            try { token.commit(); throw new AssertionError("reentrant token replay"); } catch (IllegalStateException expected) { }
        } finally { env.close(); }
    }


    @org.junit.Test public void first_order_snapshot() throws Throwable { firstOrderAndSnapshot(); }
    @org.junit.Test public void unique_ambiguous() throws Throwable { uniqueRejectsAmbiguous(); }
    @org.junit.Test public void all_partial_order() throws Throwable { allPartialOrder(); }
    @org.junit.Test public void all_duplicates() throws Throwable { allRetainsDuplicates(); }
    @org.junit.Test public void bounds_null_empty() throws Throwable { resultBoundsAndNull(); }
    @org.junit.Test public void process_capability_order() throws Throwable { processAndCapabilityOrdering(); }
    @org.junit.Test public void epoch_before_during_commit() throws Throwable { epochBeforeAndDuringCommit(); }
    @org.junit.Test public void epoch_during_prepare() throws Throwable { changedWhilePreparing(1); }
    @org.junit.Test public void close_during_prepare() throws Throwable { changedWhilePreparing(0); }
    @org.junit.Test public void loader_during_prepare() throws Throwable { changedWhilePreparing(2); }
    @org.junit.Test public void close_before_commit() throws Throwable { closeBeforeCommit(); }
    @org.junit.Test public void resolver_checked_fatal() throws Throwable { checkedAndFatalResolverErrors(); }
    @org.junit.Test public void resolver_reentrant_close() throws Throwable { reentrantResolverClose(); }
    @org.junit.Test public void preflight_initializer_order() throws Throwable { preflightPrecedesResolverAndInitialization(); }
    @org.junit.Test public void capability_recheck() throws Throwable { capabilityRecheckedBeforeCommit(); }
    @org.junit.Test public void commit_cancel_race() throws Throwable { commitCancelRace(); }
    @org.junit.Test public void terminal_token_references() throws Throwable { tokenReferencesReleaseAcrossTerminalOutcomes(); }
    @org.junit.Test public void reentrant_backend_token_close() throws Throwable { reentrantBackendTokenClose(); }
}
