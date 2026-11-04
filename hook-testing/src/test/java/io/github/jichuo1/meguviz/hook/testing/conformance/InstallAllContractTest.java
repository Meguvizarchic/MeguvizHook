package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.Capability;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.ProcessFilter;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetInstallReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.DiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.ReflectiveMemberResolver;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.BatchMemberResolver;
import io.github.jichuo1.meguviz.hook.core.spi.MemberResolver;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import io.github.jichuo1.meguviz.hook.testing.Fixtures;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** installAll keeps per-spec install semantics and scopes resolver batches to one call. */
public final class InstallAllContractTest {
    private static final String FIXTURES = Fixtures.class.getName();
    private static final BeforeCallback NOOP = invocation -> { };

    private static TargetSpec method(String name, String... parameters) {
        TargetSpec.DescriptorTarget.Builder builder = TargetSpec.descriptor(TargetKind.METHOD, FIXTURES, name);
        if (parameters != null) builder.parameters(parameters);
        return builder.build();
    }

    private static HookSpec spec(String id, TargetSpec target) {
        return HookSpec.builder(id, "conformance", target).before(NOOP).build();
    }

    /** A spread of outcomes: installed, missing, ambiguous, member target, duplicates, filtered. */
    private static List<HookSpec> mixedSpecs() {
        TargetSpec ambiguous = TargetSpec.descriptor(TargetKind.METHOD, FIXTURES, "overloaded").match(MatchPolicy.UNIQUE).build();
        TargetSpec all = TargetSpec.descriptor(TargetKind.METHOD, FIXTURES, "overloaded").match(MatchPolicy.ALL).build();
        HookSpec add = spec("batch.add", method("add", "int", "int"));
        return Arrays.asList(
                add,
                spec("batch.concat", method("concat", "java.lang.String", "java.lang.String")),
                spec("batch.missing-method", method("absent")),
                spec("batch.missing-class", TargetSpec.descriptor(TargetKind.METHOD, FIXTURES + "$Absent", "x").build()),
                spec("batch.ambiguous", ambiguous),
                spec("batch.all", all),
                spec("batch.member", TargetSpec.ofMember(TestSupport.method("noArguments"))),
                spec("batch.ctor", TargetSpec.descriptor(TargetKind.CONSTRUCTOR, Fixtures.Constructed.class.getName(), "<init>")
                        .parameters("int").build()),
                add,
                HookSpec.builder("batch.add", "conformance", method("add", "int", "int")).before(invocation -> { }).build(),
                HookSpec.builder("batch.filtered", "conformance", method("concat", "java.lang.String", "java.lang.String"))
                        .processes(ProcessFilter.only("other.process")).before(NOOP).build(),
                HookSpec.builder("batch.capability", "conformance", method("concat", "java.lang.String", "java.lang.String"))
                        .require(Capability.HOT_RELOAD).before(NOOP).build());
    }

    @Test public void receiptsMatchSequentialInstallInOrder() {
        List<RegistrationReceipt> sequential = new ArrayList<RegistrationReceipt>();
        FakeBackend first = FakeBackend.create();
        HookEnvironment one = HookEnvironment.create(first);
        try {
            for (HookSpec spec : mixedSpecs()) sequential.add(one.install(spec));
        } finally { one.close(); }

        FakeBackend second = FakeBackend.create();
        HookEnvironment batched = HookEnvironment.create(second);
        try {
            List<RegistrationReceipt> receipts = batched.installAll(mixedSpecs());
            assertEquals(sequential.size(), receipts.size());
            for (int i = 0; i < receipts.size(); i++) {
                RegistrationReceipt expected = sequential.get(i), actual = receipts.get(i);
                assertEquals("spec " + i, expected.id(), actual.id());
                assertEquals("spec " + i + " " + actual, expected.status(), actual.status());
                assertEquals("spec " + i, targets(expected), targets(actual));
                assertEquals("spec " + i, expected.message(), actual.message());
                assertEquals(expected.registration() != null, actual.registration() != null);
                assertEquals("spec " + i, expected.generation(), actual.generation());
                assertEquals("spec " + i, targetStatuses(expected), targetStatuses(actual));
            }
            assertEquals(first.installCount(), second.installCount());
            assertEquals(RegistrationReceipt.Status.INSTALLED, receipts.get(0).status());
            assertEquals(RegistrationReceipt.Status.TARGET_MISSING, receipts.get(2).status());
            assertEquals(RegistrationReceipt.Status.AMBIGUOUS_TARGET, receipts.get(4).status());
            assertEquals(RegistrationReceipt.Status.ALREADY_INSTALLED, receipts.get(8).status());
            assertEquals(RegistrationReceipt.Status.CONFLICT, receipts.get(9).status());
            assertEquals(RegistrationReceipt.Status.FILTERED, receipts.get(10).status());
            assertEquals(RegistrationReceipt.Status.UNSUPPORTED_CAPABILITY, receipts.get(11).status());
        } finally { batched.close(); }
    }

    @Test public void oneBatchPerLoaderAndEpochClosedBeforeReturn() {
        FakeBackend backend = FakeBackend.create();
        CountingResolver resolver = new CountingResolver();
        HookEnvironment env = HookEnvironment.create(backend, resolver);
        try {
            List<RegistrationReceipt> receipts = env.installAll(Arrays.asList(
                    spec("count.member", TargetSpec.ofMember(TestSupport.method("noArguments"))),
                    spec("count.add", method("add", "int", "int")),
                    spec("count.concat", method("concat", "java.lang.String", "java.lang.String")),
                    spec("count.missing", method("absent"))));
            assertEquals(4, receipts.size());
            assertEquals("member targets do not open a batch; descriptors share one", 1, resolver.opened.get());
            assertEquals(1, resolver.closed.get());
            assertEquals(3, resolver.batchResolves.get());
            assertEquals(0, resolver.plainResolves.get());

            // Ordinary install never batches.
            env.install(spec("count.single", method("noArguments")));
            assertEquals(1, resolver.opened.get());
            assertEquals(1, resolver.plainResolves.get());

            // A later installAll gets a fresh batch: nothing survives the previous call.
            env.installAll(Collections.singletonList(spec("count.again", method("append", "java.lang.StringBuilder", "java.lang.String"))));
            assertEquals(2, resolver.opened.get());
            assertEquals(2, resolver.closed.get());
        } finally { env.close(); }
    }

    @Test public void epochOrLoaderChangeReopensTheBatch() {
        FakeBackend backend = FakeBackend.create().withEpochBumpOnNextInstall();
        CountingResolver resolver = new CountingResolver();
        HookEnvironment env = HookEnvironment.create(backend, resolver);
        try {
            List<RegistrationReceipt> receipts = env.installAll(Arrays.asList(
                    spec("epoch.add", method("add", "int", "int")),
                    spec("epoch.concat", method("concat", "java.lang.String", "java.lang.String"))));
            // The first install bumps the epoch after it was observed; the next spec must not reuse
            // the first batch. The first receipt reports the identity change as usual.
            assertEquals(RegistrationReceipt.Status.STALE_IDENTITY, receipts.get(0).status());
            assertEquals(RegistrationReceipt.Status.INSTALLED, receipts.get(1).status());
            assertEquals(2, resolver.opened.get());
            assertEquals(2, resolver.closed.get());
        } finally { env.close(); }

        FakeBackend loaders = FakeBackend.create();
        CountingResolver second = new CountingResolver();
        ClassLoader original = loaders.context().classLoader();
        ClassLoader child = new ClassLoader(original) { };
        // Resolving the first target swaps the backend loader, as a host could between targets.
        second.afterBatchResolve = () -> loaders.setClassLoader(child);
        HookEnvironment swapping = HookEnvironment.create(loaders, second);
        try {
            List<RegistrationReceipt> receipts = swapping.installAll(Arrays.asList(
                    spec("loader.add", method("add", "int", "int")),
                    spec("loader.concat", method("concat", "java.lang.String", "java.lang.String"))));
            assertEquals(RegistrationReceipt.Status.STALE_IDENTITY, receipts.get(0).status());
            assertEquals(RegistrationReceipt.Status.INSTALLED, receipts.get(1).status());
            assertEquals("a different loader opens its own batch", 2, second.opened.get());
            assertEquals(2, second.closed.get());
            assertEquals(Arrays.asList(original, child), second.loaders);
        } finally {
            loaders.setClassLoader(original);
            swapping.close();
        }
    }

    @Test public void uncheckedFailureStopsDeliversEarlierReceiptsAndClosesBatch() {
        FakeBackend backend = FakeBackend.create();
        CountingResolver resolver = new CountingResolver();
        IllegalStateException boom = new IllegalStateException("resolver bug");
        resolver.failOn = "concat";
        resolver.failure = boom;
        HookEnvironment env = HookEnvironment.create(backend, resolver);
        List<RegistrationReceipt> seen = new ArrayList<RegistrationReceipt>();
        try {
            Throwable thrown = TestSupport.capture(() -> env.installAll(Arrays.asList(
                    spec("fail.add", method("add", "int", "int")),
                    spec("fail.concat", method("concat", "java.lang.String", "java.lang.String")),
                    spec("fail.never", method("noArguments"))), seen::add));
            assertSame(boom, thrown);
            assertEquals(1, seen.size());
            assertTrue(seen.get(0).isInstalled());
            assertEquals("the earlier registration stays installed and owned", 1, env.activeRegistrationCount());
            assertEquals("the remaining spec is not attempted", 2, resolver.batchResolves.get());
            assertEquals(1, resolver.closed.get());
        } finally { env.close(); }

        CountingResolver consumerCase = new CountingResolver();
        HookEnvironment second = HookEnvironment.create(FakeBackend.create(), consumerCase);
        RuntimeException consumerFailure = new RuntimeException("consumer");
        try {
            Throwable thrown = TestSupport.capture(() -> second.installAll(Arrays.asList(
                    spec("consumer.add", method("add", "int", "int")),
                    spec("consumer.concat", method("concat", "java.lang.String", "java.lang.String"))),
                    receipt -> { throw consumerFailure; }));
            assertSame(consumerFailure, thrown);
            assertEquals(1, consumerCase.batchResolves.get());
            assertEquals(1, consumerCase.closed.get());
        } finally { second.close(); }
    }

    @Test public void declinedOrFailingOpenResolvesEachTargetSeparately() {
        CountingResolver declines = new CountingResolver();
        declines.declineBatch = true;
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend, declines);
        try {
            List<RegistrationReceipt> receipts = env.installAll(Arrays.asList(
                    spec("decline.add", method("add", "int", "int")),
                    spec("decline.concat", method("concat", "java.lang.String", "java.lang.String"))));
            assertTrue(receipts.get(0).isInstalled());
            assertTrue(receipts.get(1).isInstalled());
            assertEquals("a declined batch is asked once per loader and epoch", 1, declines.openAttempts.get());
            assertEquals(2, declines.plainResolves.get());

            // A new epoch between specs, not caused by an install, asks again.
            env.installAll(Arrays.asList(
                    spec("decline.missing", method("absent")),
                    spec("decline.member", TargetSpec.ofMember(TestSupport.method("noArguments")))),
                    receipt -> backend.bumpCodeEpoch());
            env.installAll(Arrays.asList(
                    spec("decline.one", method("absent")),
                    spec("decline.two", method("absent"))),
                    receipt -> backend.bumpCodeEpoch());
            assertEquals(4, declines.openAttempts.get());
        } finally { env.close(); }

        CountingResolver failsOnce = new CountingResolver();
        failsOnce.failNextOpen = true;
        List<DiagnosticsSink.Event> events = new ArrayList<DiagnosticsSink.Event>();
        HookEnvironment second = HookEnvironment.create(FakeBackend.create(), failsOnce, events::add);
        try {
            List<RegistrationReceipt> receipts = second.installAll(Arrays.asList(
                    spec("open.add", method("add", "int", "int")),
                    spec("open.concat", method("concat", "java.lang.String", "java.lang.String"))));
            assertTrue("a failed open resolves like install would", receipts.get(0).isInstalled());
            assertTrue(receipts.get(1).isInstalled());
            assertEquals("the failure is not retried for the same identity", 1, failsOnce.openAttempts.get());
            assertEquals(2, failsOnce.plainResolves.get());
            assertEquals(1, events.size());
            assertEquals(DiagnosticsSink.Event.Kind.BACKEND_FAILURE, events.get(0).kind());
            assertEquals("open.add", events.get(0).id().value());
            assertTrue(events.get(0).failure() instanceof BackendInstallException);
        } finally { second.close(); }

        CountingResolver unchecked = new CountingResolver();
        IllegalStateException openBug = new IllegalStateException("open bug");
        unchecked.uncheckedOpen = openBug;
        HookEnvironment third = HookEnvironment.create(FakeBackend.create(), unchecked);
        try {
            assertSame(openBug, TestSupport.capture(() -> third.installAll(Collections.singletonList(
                    spec("open.bug", method("add", "int", "int"))))));
            assertEquals(0, third.activeRegistrationCount());
        } finally { third.close(); }
    }

    @Test public void closeFailuresAreReportedOrPropagatedByFatality() {
        CountingResolver nonFatal = new CountingResolver();
        nonFatal.closeFailure = new IllegalStateException("close bug");
        List<DiagnosticsSink.Event> events = new ArrayList<DiagnosticsSink.Event>();
        FakeBackend backend = FakeBackend.create();
        HookEnvironment env = HookEnvironment.create(backend, nonFatal, events::add);
        try {
            // The consumer bumps the epoch, so the second target closes the first batch (rebind)
            // and the end of the call closes the second one: two non-fatal close failures.
            List<RegistrationReceipt> receipts = new ArrayList<RegistrationReceipt>();
            env.installAll(Arrays.asList(
                    spec("close.add", method("add", "int", "int")),
                    spec("close.concat", method("concat", "java.lang.String", "java.lang.String"))),
                    receipt -> { receipts.add(receipt); backend.bumpCodeEpoch(); });
            assertEquals(2, receipts.size());
            assertTrue(receipts.get(0).isInstalled());
            assertTrue(receipts.get(1).isInstalled());
            assertEquals(2, events.size());
            assertEquals("close.concat", events.get(0).id().value());
            assertSame(nonFatal.closeFailure, events.get(0).failure());
        } finally { env.close(); }

        CountingResolver fatal = new CountingResolver();
        SimulatedVmError vmError = new SimulatedVmError();
        fatal.closeFailure = vmError;
        HookEnvironment second = HookEnvironment.create(FakeBackend.create(), fatal);
        try {
            assertSame(vmError, TestSupport.capture(() -> second.installAll(Collections.singletonList(
                    spec("fatal.add", method("add", "int", "int"))))));
        } finally { second.close(); }

        CountingResolver fatalAfterPrimary = new CountingResolver();
        SimulatedVmError laterError = new SimulatedVmError();
        fatalAfterPrimary.closeFailure = laterError;
        fatalAfterPrimary.failOn = "add";
        fatalAfterPrimary.failure = new IllegalStateException("resolver bug");
        HookEnvironment third = HookEnvironment.create(FakeBackend.create(), fatalAfterPrimary);
        try {
            Throwable thrown = TestSupport.capture(() -> third.installAll(Collections.singletonList(
                    spec("fatal.primary", method("add", "int", "int")))));
            assertSame("a fatal close failure wins over an ordinary primary", laterError, thrown);
            assertSame(fatalAfterPrimary.failure, thrown.getSuppressed()[0]);
        } finally { third.close(); }

        CountingResolver ordinaryAfterPrimary = new CountingResolver();
        ordinaryAfterPrimary.closeFailure = new IllegalStateException("close bug");
        ordinaryAfterPrimary.failOn = "add";
        ordinaryAfterPrimary.failure = new IllegalArgumentException("resolver bug");
        HookEnvironment fourth = HookEnvironment.create(FakeBackend.create(), ordinaryAfterPrimary);
        try {
            Throwable thrown = TestSupport.capture(() -> fourth.installAll(Collections.singletonList(
                    spec("ordinary.primary", method("add", "int", "int")))));
            assertSame(ordinaryAfterPrimary.failure, thrown);
            assertSame(ordinaryAfterPrimary.closeFailure, thrown.getSuppressed()[0]);
        } finally { fourth.close(); }
    }

    @Test public void reentrantAndClosingCallsKeepOrdinaryInstallSemantics() {
        FakeBackend backend = FakeBackend.create();
        CountingResolver resolver = new CountingResolver();
        HookEnvironment env = HookEnvironment.create(backend, resolver);
        try {
            AtomicInteger seen = new AtomicInteger();
            List<RegistrationReceipt> nested = new ArrayList<RegistrationReceipt>();
            List<RegistrationReceipt> receipts = new ArrayList<RegistrationReceipt>();
            env.installAll(Arrays.asList(
                    spec("reenter.add", method("add", "int", "int")),
                    spec("reenter.concat", method("concat", "java.lang.String", "java.lang.String"))),
                    receipt -> {
                        receipts.add(receipt);
                        if (seen.getAndIncrement() == 0) {
                            nested.add(env.install(spec("reenter.nested", method("noArguments"))));
                            nested.addAll(env.installAll(Collections.singletonList(
                                    spec("reenter.nested-all", method("append", "java.lang.StringBuilder", "java.lang.String")))));
                        }
                    });
            assertTrue(receipts.get(0).isInstalled());
            assertTrue(receipts.get(1).isInstalled());
            assertTrue(nested.get(0).isInstalled());
            assertTrue(nested.get(1).isInstalled());
            assertEquals("the nested installAll has its own batch", 2, resolver.opened.get());
            assertEquals(2, resolver.closed.get());
        } finally { env.close(); }

        CountingResolver closing = new CountingResolver();
        HookEnvironment closed = HookEnvironment.create(FakeBackend.create(), closing);
        List<RegistrationReceipt> afterClose = closed.installAll(Arrays.asList(
                spec("closing.add", method("add", "int", "int")),
                spec("closing.concat", method("concat", "java.lang.String", "java.lang.String"))));
        assertTrue(afterClose.get(0).isInstalled());
        List<RegistrationReceipt> results = new ArrayList<RegistrationReceipt>();
        HookEnvironment requested = HookEnvironment.create(FakeBackend.create(), closing);
        requested.installAll(Arrays.asList(
                spec("request.add", method("add", "int", "int")),
                spec("request.concat", method("concat", "java.lang.String", "java.lang.String"))),
                receipt -> { results.add(receipt); requested.requestClose(); });
        assertTrue(results.get(0).isInstalled());
        assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, results.get(1).status());
        requested.close();
        closed.close();
        assertEquals(closing.opened.get(), closing.closed.get());

        List<RegistrationReceipt> closedMidCall = new ArrayList<RegistrationReceipt>();
        HookEnvironment selfClosing = HookEnvironment.create(FakeBackend.create(), closing);
        selfClosing.installAll(Arrays.asList(
                spec("self.add", method("add", "int", "int")),
                spec("self.concat", method("concat", "java.lang.String", "java.lang.String"))),
                receipt -> { closedMidCall.add(receipt); if (closedMidCall.size() == 1) selfClosing.close(); });
        assertEquals(RegistrationReceipt.Status.TEMPORARILY_UNAVAILABLE, closedMidCall.get(1).status());
        assertEquals(closing.opened.get(), closing.closed.get());
    }

    @Test public void initializingDescriptorTargetsInitializeThroughTheBatch() {
        assertFalse(InitProbe.initialized);
        HookEnvironment env = HookEnvironment.create(FakeBackend.create());
        try {
            String owner = InitTarget.class.getName();
            List<RegistrationReceipt> receipts = env.installAll(Arrays.asList(
                    spec("init.lazy", TargetSpec.descriptor(TargetKind.METHOD, owner, "value").build()),
                    spec("init.eager", TargetSpec.descriptor(TargetKind.METHOD, owner, "other").initializeClass(true).build())));
            assertTrue(receipts.get(0).isInstalled());
            assertTrue(receipts.get(1).isInstalled());
            assertTrue("initializeClass(true) initializes even after an uninitialized lookup", InitProbe.initialized);
        } finally { env.close(); }
    }

    @Test public void resolverWithoutBatchSupportUsesOrdinaryResolution() {
        AtomicInteger calls = new AtomicInteger();
        MemberResolver plain = (descriptor, loader) -> {
            calls.incrementAndGet();
            return new ReflectiveMemberResolver().resolve(descriptor, loader);
        };
        HookEnvironment env = HookEnvironment.create(FakeBackend.create(), plain);
        try {
            List<RegistrationReceipt> receipts = env.installAll(Arrays.asList(
                    spec("plain.add", method("add", "int", "int")),
                    spec("plain.concat", method("concat", "java.lang.String", "java.lang.String"))));
            assertTrue(receipts.get(0).isInstalled());
            assertTrue(receipts.get(1).isInstalled());
            assertEquals(2, calls.get());
        } finally { env.close(); }
    }

    private static List<TargetInstallReceipt.Status> targetStatuses(RegistrationReceipt receipt) {
        List<TargetInstallReceipt.Status> result = new ArrayList<TargetInstallReceipt.Status>();
        for (TargetInstallReceipt target : receipt.targets()) result.add(target.status());
        return result;
    }

    static final class InitProbe { static boolean initialized; }

    static final class InitTarget {
        static { InitProbe.initialized = true; }
        static int value() { return 1; }
        static int other() { return 2; }
    }

    static final class SimulatedVmError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }

    private static List<Executable> targets(RegistrationReceipt receipt) {
        List<Executable> result = new ArrayList<Executable>();
        for (TargetInstallReceipt target : receipt.targets()) result.add(target.target());
        return result;
    }

    /** Wraps the default resolver and records batch lifecycle calls. */
    private static final class CountingResolver implements BatchMemberResolver {
        private final ReflectiveMemberResolver delegate = new ReflectiveMemberResolver();
        final AtomicInteger openAttempts = new AtomicInteger();
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final AtomicInteger batchResolves = new AtomicInteger();
        final AtomicInteger plainResolves = new AtomicInteger();
        final List<ClassLoader> loaders = new ArrayList<ClassLoader>();
        boolean declineBatch;
        boolean failNextOpen;
        RuntimeException uncheckedOpen;
        Throwable closeFailure;
        String failOn;
        Runnable afterBatchResolve;
        RuntimeException failure;

        @Override
        public List<Executable> resolve(TargetSpec.DescriptorTarget descriptor, ClassLoader loader) throws BackendInstallException {
            plainResolves.incrementAndGet();
            return delegate.resolve(descriptor, loader);
        }

        @Override
        public Batch openBatch(ClassLoader loader) throws BackendInstallException {
            openAttempts.incrementAndGet();
            if (failNextOpen) {
                failNextOpen = false;
                throw new BackendInstallException("open failed");
            }
            if (uncheckedOpen != null) throw uncheckedOpen;
            if (declineBatch) return null;
            opened.incrementAndGet();
            loaders.add(loader);
            final Batch inner = delegate.openBatch(loader);
            return new Batch() {
                @Override public List<Executable> resolve(TargetSpec.DescriptorTarget descriptor) throws BackendInstallException {
                    batchResolves.incrementAndGet();
                    if (descriptor.memberName().equals(failOn)) throw failure;
                    List<Executable> result = inner.resolve(descriptor);
                    Runnable hook = afterBatchResolve;
                    afterBatchResolve = null;
                    if (hook != null) hook.run();
                    return result;
                }
                @Override public void close() {
                    closed.incrementAndGet();
                    inner.close();
                    Throwable failure = closeFailure;
                    if (failure instanceof Error) throw (Error) failure;
                    if (failure != null) throw (RuntimeException) failure;
                }
            };
        }
    }
}
