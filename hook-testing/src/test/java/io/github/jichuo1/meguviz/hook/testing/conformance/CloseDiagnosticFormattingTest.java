package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MatchPolicy;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.RegistrationState;
import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DiagnosticsSink;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Executable;
import java.lang.reflect.GenericSignatureFormatError;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Closing installed entries must not depend on formatting nonessential diagnostics. */
public final class CloseDiagnosticFormattingTest {
    private static final class FixtureLoader extends ClassLoader {
        FixtureLoader() { super(CloseDiagnosticFormattingTest.class.getClassLoader()); }
        Class<?> define(byte[] bytes) { return defineClass("fixture.MalformedSignatureFixture", bytes, 0, bytes.length); }
    }

    /** Two executable methods; only the first has a malformed generic Signature attribute. */
    private static byte[] fixture() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0xcafebabe); out.writeShort(0); out.writeShort(52);
        out.writeShort(15); // constant pool count
        utf(out, "fixture/MalformedSignatureFixture"); // 1
        out.writeByte(7); out.writeShort(1);             // 2 Class
        utf(out, "java/lang/Object");                  // 3
        out.writeByte(7); out.writeShort(3);             // 4 Class
        utf(out, "<init>");                            // 5
        utf(out, "()V");                               // 6
        utf(out, "Code");                              // 7
        out.writeByte(12); out.writeShort(5); out.writeShort(6); // 8 NameAndType
        out.writeByte(10); out.writeShort(4); out.writeShort(8); // 9 Object.<init>
        utf(out, "broken");                            // 10
        utf(out, "()I");                               // 11
        utf(out, "Signature");                         // 12
        utf(out, "(");                                 // 13 malformed generic method signature
        utf(out, "good");                              // 14
        out.writeShort(0x0021); out.writeShort(2); out.writeShort(4); // public, super, Object
        out.writeShort(0); out.writeShort(0); out.writeShort(3); // interfaces, fields, methods
        out.writeShort(0x0001); out.writeShort(5); out.writeShort(6); out.writeShort(1);
        code(out, 1, 1, new byte[] { 0x2a, (byte) 0xb7, 0, 9, (byte) 0xb1 });
        out.writeShort(0x0009); out.writeShort(10); out.writeShort(11); out.writeShort(2);
        code(out, 1, 0, new byte[] { 0x04, (byte) 0xac }); // return 1
        out.writeShort(12); out.writeInt(2); out.writeShort(13);
        out.writeShort(0x0009); out.writeShort(14); out.writeShort(11); out.writeShort(1);
        code(out, 1, 0, new byte[] { 0x05, (byte) 0xac }); // return 2
        out.writeShort(0); // class attributes
        out.flush();
        return bytes.toByteArray();
    }

    private static void utf(DataOutputStream out, String value) throws IOException {
        out.writeByte(1); out.writeUTF(value);
    }

    private static void code(DataOutputStream out, int stack, int locals, byte[] instructions) throws IOException {
        out.writeShort(7); out.writeInt(12 + instructions.length);
        out.writeShort(stack); out.writeShort(locals); out.writeInt(instructions.length); out.write(instructions);
        out.writeShort(0); out.writeShort(0); // exception table and nested attributes
    }

    private static final class Backend implements HookBackend {
        final FakeBackend fake = FakeBackend.create();
        final AtomicInteger attempts = new AtomicInteger();
        final Executable fails;
        final RuntimeException failure;
        Backend(ClassLoader loader, Executable fails, RuntimeException failure) {
            fake.setClassLoader(loader); this.fails = fails; this.failure = failure;
        }
        @Override public String id() { return fake.id(); }
        @Override public BackendCapabilities capabilities() { return fake.capabilities(); }
        @Override public BackendContext context() { return fake.context(); }
        @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
            BackendHandle handle = fake.install(plan, channel);
            return () -> {
                attempts.incrementAndGet();
                if (plan.target().equals(fails)) throw failure;
                return handle.uninstall();
            };
        }
    }

    private static HookSpec all(String id, AtomicInteger callbacks) {
        TargetSpec target = TargetSpec.descriptor(TargetKind.METHOD, "fixture.MalformedSignatureFixture", "unused")
                .match(MatchPolicy.ALL).build();
        return HookSpec.builder(id, "format-test", target).before(invocation -> callbacks.incrementAndGet()).build();
    }

    @Test public void malformedGenericSignatureDoesNotInterruptMultiTargetClose() throws Throwable {
        FixtureLoader loader = new FixtureLoader();
        Class<?> fixture = loader.define(fixture());
        Method malformed = fixture.getDeclaredMethod("broken");
        Method good = fixture.getDeclaredMethod("good");
        assertThrows(GenericSignatureFormatError.class, malformed::toGenericString);
        Backend backend = new Backend(loader, null, null);
        backend.fake.onTarget(malformed, (receiver, args) -> 1);
        backend.fake.onTarget(good, (receiver, args) -> 2);
        AtomicInteger callbacks = new AtomicInteger();
        HookEnvironment env = HookEnvironment.create(backend,
                (target, contextLoader) -> Arrays.<Executable>asList(malformed, good), DiagnosticsSink.NONE);
        try {
            RegistrationReceipt receipt = env.install(all("close.bad-generic", callbacks));
            assertEquals(RegistrationReceipt.Status.INSTALLED, receipt.status());
            assertEquals(2, receipt.targets().size());
            String malformedText = "TargetInstallReceipt[fixture.MalformedSignatureFixture#broken INSTALLED installed]";
            String goodText = "TargetInstallReceipt[" + good.toGenericString() + " INSTALLED installed]";
            assertEquals(malformedText, receipt.targets().get(0).toString());
            assertEquals(goodText, receipt.targets().get(1).toString());
            String fullText = "RegistrationReceipt[" + receipt.id() + " " + receipt.status()
                    + " backend=" + receipt.backendId() + " generation=" + receipt.generation()
                    + " " + receipt.message() + " targets=[" + malformedText + ", " + goodText + "]]";
            assertEquals(fullText, receipt.toString());
            HookRegistration registration = receipt.registration();
            assertEquals(1, backend.fake.call(malformed, null));
            assertEquals(2, backend.fake.call(good, null));
            assertEquals(2, callbacks.get());
            CloseReport report = registration.close();
            assertEquals(2, backend.attempts.get());
            assertEquals(RegistrationState.CLOSED, report.state());
            assertEquals(CloseReport.EntryRestoration.RESTORED, report.entryRestoration());
            assertTrue(report.newCallbacksRejected());
            assertTrue(report.message().contains("fixture.MalformedSignatureFixture#broken"));
            assertFalse(backend.fake.isHooked(malformed));
            assertFalse(backend.fake.isHooked(good));
            assertSame(report, registration.close());
        } finally { env.close(); }
    }

    @Test public void failingThrowableToStringDoesNotPreventLaterUninstall() throws Throwable {
        Method first = CloseDiagnosticFormattingTest.class.getDeclaredMethod("first");
        Method second = CloseDiagnosticFormattingTest.class.getDeclaredMethod("second");
        RuntimeException failure = new RuntimeException("backend uninstall failed") {
            private static final long serialVersionUID = 1L;
            @Override public String toString() { throw new IllegalStateException("bad throwable formatter"); }
        };
        Backend backend = new Backend(getClass().getClassLoader(), first, failure);
        backend.fake.onTarget(first, (receiver, args) -> 3);
        backend.fake.onTarget(second, (receiver, args) -> 4);
        AtomicInteger callbacks = new AtomicInteger();
        HookEnvironment env = HookEnvironment.create(backend,
                (target, contextLoader) -> Arrays.<Executable>asList(first, second), DiagnosticsSink.NONE);
        try {
            RegistrationReceipt receipt = env.install(all("close.bad-throwable", callbacks));
            assertEquals(RegistrationReceipt.Status.INSTALLED, receipt.status());
            HookRegistration registration = receipt.registration();
            CloseReport report = registration.close();
            assertEquals(2, backend.attempts.get());
            assertEquals(RegistrationState.RETIRED_RESIDENT, report.state());
            assertEquals(CloseReport.EntryRestoration.UNKNOWN, report.entryRestoration());
            assertTrue(report.newCallbacksRejected());
            assertTrue(report.message().contains(failure.getClass().getName()));
            assertTrue(backend.fake.isHooked(first));
            assertFalse(backend.fake.isHooked(second));
            assertEquals(3, backend.fake.call(first, null));
            assertEquals(0, callbacks.get());
            assertSame(report, registration.close());
        } finally { env.close(); }
    }

    @Test public void sneakyInterruptedUninstallDoesNotSkipLaterHandleAndRestoresInterrupt() throws Throwable {
        boolean wasInterrupted = Thread.interrupted();
        Method first = CloseDiagnosticFormattingTest.class.getDeclaredMethod("first");
        Method second = CloseDiagnosticFormattingTest.class.getDeclaredMethod("second");
        FakeBackend fake = FakeBackend.create();
        fake.onTarget(first, (receiver, args) -> 3);
        fake.onTarget(second, (receiver, args) -> 4);
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean laterClearedInterrupt = new AtomicBoolean();
        InterruptedException interruption = new InterruptedException("synthetic backend interruption");
        HookBackend backend = new HookBackend() {
            @Override public String id() { return fake.id(); }
            @Override public BackendCapabilities capabilities() { return fake.capabilities(); }
            @Override public BackendContext context() { return fake.context(); }
            @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
                BackendHandle handle = fake.install(plan, channel);
                return () -> {
                    attempts.incrementAndGet();
                    if (plan.target().equals(first)) {
                        Thread.currentThread().interrupt();
                        throw sneaky(interruption);
                    }
                    laterClearedInterrupt.set(Thread.interrupted());
                    return handle.uninstall();
                };
            }
        };
        HookEnvironment env = HookEnvironment.create(backend,
                (target, contextLoader) -> Arrays.<Executable>asList(first, second), DiagnosticsSink.NONE);
        try {
            RegistrationReceipt receipt = env.install(all("close.sneaky-interrupted", new AtomicInteger()));
            assertEquals(RegistrationReceipt.Status.INSTALLED, receipt.status());
            CloseReport report = receipt.registration().close();
            assertEquals(2, attempts.get());
            assertTrue("later backend should have observed and cleared the interrupt", laterClearedInterrupt.get());
            assertTrue("close must restore the interrupted status", Thread.currentThread().isInterrupted());
            assertEquals(RegistrationState.RETIRED_RESIDENT, report.state());
            assertEquals(CloseReport.EntryRestoration.UNKNOWN, report.entryRestoration());
            assertTrue(report.newCallbacksRejected());
            assertTrue(fake.isHooked(first));
            assertFalse(fake.isHooked(second));
        } finally {
            Thread.interrupted();
            try { env.close(); }
            finally { if (wasInterrupted) Thread.currentThread().interrupt(); }
        }
    }

    @Test public void environmentCloseRestoresInterruptAcrossSeparateRegistrations() throws Exception {
        boolean wasInterrupted = Thread.interrupted();
        Method first = CloseDiagnosticFormattingTest.class.getDeclaredMethod("first");
        Method second = CloseDiagnosticFormattingTest.class.getDeclaredMethod("second");
        FakeBackend fake = FakeBackend.create();
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean laterClearedInterrupt = new AtomicBoolean();
        InterruptedException interruption = new InterruptedException("synthetic first registration interruption");
        HookBackend backend = new HookBackend() {
            @Override public String id() { return fake.id(); }
            @Override public BackendCapabilities capabilities() { return fake.capabilities(); }
            @Override public BackendContext context() { return fake.context(); }
            @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
                BackendHandle handle = fake.install(plan, channel);
                return () -> {
                    attempts.incrementAndGet();
                    if (plan.target().equals(first)) {
                        Thread.currentThread().interrupt();
                        throw sneaky(interruption);
                    }
                    laterClearedInterrupt.set(Thread.interrupted());
                    return handle.uninstall();
                };
            }
        };
        HookEnvironment env = HookEnvironment.create(backend);
        try {
            RegistrationReceipt a = env.install(HookSpec.forMember("environment.interrupted.first", "format-test", first)
                    .replaceConstant(8).build());
            RegistrationReceipt b = env.install(HookSpec.forMember("environment.interrupted.second", "format-test", second)
                    .replaceConstant(9).build());
            assertTrue(a.isInstalled()); assertTrue(b.isInstalled());
            env.close();
            assertEquals(2, attempts.get());
            assertTrue("second backend must observe and clear the first interruption", laterClearedInterrupt.get());
            assertTrue("environment close must restore the interrupted status", Thread.currentThread().isInterrupted());
            assertTrue(fake.isHooked(first));
            assertFalse(fake.isHooked(second));
            CloseReport firstReport = a.registration().close();
            CloseReport secondReport = b.registration().close();
            assertEquals(CloseReport.EntryRestoration.UNKNOWN, firstReport.entryRestoration());
            assertEquals(CloseReport.EntryRestoration.RESTORED, secondReport.entryRestoration());
        } finally {
            Thread.interrupted();
            try { env.close(); }
            finally { if (wasInterrupted) Thread.currentThread().interrupt(); }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable failure) throws T { throw (T) failure; }

    public static int first() { return 3; }
    public static int second() { return 4; }
}
