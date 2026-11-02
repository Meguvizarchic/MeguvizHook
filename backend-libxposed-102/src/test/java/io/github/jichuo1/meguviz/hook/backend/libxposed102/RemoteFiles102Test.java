package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.JsonLineTraceTransport;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import io.github.libxposed.api.XposedInterface;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Pure proxy checks; no framework service, device, or filesystem is used. */
public final class RemoteFiles102Test {
    private static final class Framework {
        int apiVersion = 102;
        long properties;
        String[] names = {"trace.dat"};
        int propertyCalls;
        int listCalls;
        int openCalls;
        int logCalls;
        Object[] lastLog;
        RuntimeException propertyFailure;
        FileNotFoundException missing;

        XposedInterface api() {
            return (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                    new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getApiVersion": return apiVersion;
                            case "getFrameworkProperties":
                                propertyCalls++;
                                if (propertyFailure != null) throw propertyFailure;
                                return properties;
                            case "listRemoteFiles": listCalls++; return names;
                            case "openRemoteFile":
                                openCalls++;
                                if (missing != null) throw missing;
                                return null;
                            case "log": logCalls++; lastLog = args; return null;
                            default: throw new AssertionError("unexpected framework method " + method.getName());
                        }
                    });
        }
    }

    public static int target() { return 3; }

    @Test public void unsupportedRemoteDoesNotListOrOpenAndDoesNotHideProbeFailure() throws Exception {
        Framework stub = new Framework();
        RemoteFiles102 files = new RemoteFiles102(stub.api());
        assertEquals(0, stub.propertyCalls);
        assertFalse(files.isSupported());
        assertThrows(UnsupportedOperationException.class, files::list);
        assertThrows(UnsupportedOperationException.class, () -> files.openReadOnly("trace.dat"));
        assertEquals(0, stub.listCalls);
        assertEquals(0, stub.openCalls);
        SecurityException denied = new SecurityException("probe denied");
        stub.propertyFailure = denied;
        assertSame(denied, assertThrows(SecurityException.class, files::isSupported));
    }

    @Test public void fileNamesAndFrameworkFailuresAreExplicit() throws Exception {
        Framework stub = new Framework();
        stub.properties = XposedInterface.PROP_CAP_REMOTE;
        RemoteFiles102 files = new RemoteFiles102(stub.api());
        String[] names = files.list();
        names[0] = "changed";
        assertEquals("trace.dat", stub.names[0]);
        for (String invalid : new String[]{"", ".", "..", "a/b", "a\\b", "a\0b"}) {
            assertThrows(IllegalArgumentException.class, () -> files.openReadOnly(invalid));
        }
        assertEquals(0, stub.openCalls);
        FileNotFoundException missing = new FileNotFoundException("not present");
        stub.missing = missing;
        assertSame(missing, assertThrows(FileNotFoundException.class, () -> files.openReadOnly("trace.dat")));
        stub.missing = null;
        assertThrows(IllegalStateException.class, () -> files.openReadOnly("trace.dat"));
    }

    @Test public void frameworkLogIsOnlyCalledWhenCallerExplicitlyDelivers() throws Throwable {
        Framework stub = new Framework();
        FrameworkLogTraceTransport102 transport = new FrameworkLogTraceTransport102(stub.api(), "trace-test");
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("module\nprocess", 4);
        FakeBackend backend = FakeBackend.create();
        HookEnvironment environment = HookEnvironment.create(backend);
        Method target = RemoteFiles102Test.class.getMethod("target");
        RuntimeException failure = new IllegalStateException("callback");
        try {
            assertTrue(environment.install(new HookSpec.Builder(HookId.of("trace.log"), HookOwner.of("test"),
                            TargetSpec.ofMember(target))
                    .before(tracker.before(invocation -> { throw failure; })).build()).isInstalled());
            assertSame(failure, assertThrows(RuntimeException.class, () -> backend.call(target, null)));
            assertEquals(0, stub.logCalls);
            assertEquals(1, tracker.deliver(transport, 1));
            assertEquals(1, stub.logCalls);
            assertEquals("trace-test", stub.lastLog[1]);
            assertTrue(((String) stub.lastLog[2]).contains("source=module?process"));
            assertSame(failure, stub.lastLog[3]);
        } finally { environment.close(); }
    }

    @Test public void oldApiIsRejected() {
        Framework stub = new Framework();
        stub.apiVersion = 101;
        assertThrows(IllegalArgumentException.class, () -> new RemoteFiles102(stub.api()));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkLogTraceTransport102(stub.api(), "tag"));
    }

    @Test public void loggerTagRejectsControlCharacters() {
        Framework stub = new Framework();
        assertThrows(IllegalArgumentException.class,
                () -> new FrameworkLogTraceTransport102(stub.api(), "bad\ntag"));
    }

    @Test public void moduleWriterOnlyUsesCallerProvidedAppendStream() throws Exception {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        ModuleRemoteTraceWriter102 writer = new ModuleRemoteTraceWriter102("events.jsonl", 8, name -> {
            assertEquals("events.jsonl", name);
            opens.incrementAndGet();
            return new OutputStream() {
                @Override public void write(int value) { received.write(value); }
                @Override public void close() { closes.incrementAndGet(); }
            };
        });
        assertEquals(0, opens.get());
        writer.writeRecord("one\n".getBytes(StandardCharsets.UTF_8));
        writer.writeRecord("two\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("one\ntwo\n", received.toString("UTF-8"));
        assertEquals(2, opens.get());
        assertEquals(2, closes.get());
        assertThrows(IllegalArgumentException.class, () -> writer.writeRecord(new byte[9]));
        assertThrows(IllegalArgumentException.class,
                () -> new ModuleRemoteTraceWriter102("../events", 8, name -> received));
        assertEquals(2, opens.get());
    }

    @Test public void sameProcessFakeBridgeMovesRecordedJsonOnlyOnDelivery() throws Throwable {
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("injected-fixture", 2);
        FakeBackend backend = FakeBackend.create();
        HookEnvironment environment = HookEnvironment.create(backend);
        Method target = RemoteFiles102Test.class.getMethod("target");
        RuntimeException failure = new IllegalStateException("private message");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicInteger opens = new AtomicInteger();
        ModuleRemoteTraceWriter102 writer = new ModuleRemoteTraceWriter102("trace.jsonl", 131072, name -> {
            opens.incrementAndGet();
            return bytes;
        });
        try {
            assertTrue(environment.install(new HookSpec.Builder(HookId.of("trace.fake.bridge"), HookOwner.of("test"),
                            TargetSpec.ofMember(target))
                    .before(tracker.before(invocation -> { throw failure; })).build()).isInstalled());
            assertSame(failure, assertThrows(RuntimeException.class, () -> backend.call(target, null)));
            assertEquals(0, opens.get());
            assertEquals(0, bytes.size());
            assertEquals(1, tracker.deliver(new JsonLineTraceTransport(writer, false, 0), 1));
            assertEquals(1, opens.get());
            String json = bytes.toString("UTF-8");
            assertTrue(json.contains("\"hook\":\"trace.fake.bridge\""));
            assertFalse(json.contains("private message"));
            assertTrue(json.endsWith("}\n"));
        } finally { environment.close(); }
    }
}
