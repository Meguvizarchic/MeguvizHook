package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.JsonLineTraceTransport;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Slice-capable record writers on API 102. The module-side writer is exercised end to end with
 * an in-memory stream. The Binder sender cannot transact on the host (android.jar stubs), so
 * only its type and its argument checks, which run before any Parcel is obtained, are tested.
 */
public final class TraceSliceWriters102Test {
    private static final class Opened extends ByteArrayOutputStream {
        int writes;
        int flushes;
        int closes;
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            writes++;
            super.write(bytes, offset, length);
        }
        @Override public void flush() { flushes++; }
        @Override public void close() { closes++; }
    }

    @Test public void moduleWriterAppendsExactlyTheSliceThroughOneStream() throws Exception {
        List<Opened> opened = new ArrayList<Opened>();
        ModuleRemoteTraceWriter102 writer = new ModuleRemoteTraceWriter102("trace.jsonl", 8, name -> {
            Opened stream = new Opened();
            opened.add(stream);
            return stream;
        });
        assertTrue(writer instanceof JsonLineTraceTransport.SlicedRecordWriter);
        byte[] buffer = "xx{\"a\":1}\nyy".getBytes(StandardCharsets.UTF_8);
        writer.writeRecord(buffer, 2, 8);
        assertEquals(1, opened.size());
        assertEquals("{\"a\":1}\n", new String(opened.get(0).toByteArray(), StandardCharsets.UTF_8));
        assertEquals(1, opened.get(0).writes);
        assertEquals(1, opened.get(0).flushes);
        assertEquals(1, opened.get(0).closes);
        // The whole-array path is unchanged.
        writer.writeRecord("abc\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, opened.size());
        assertEquals("abc\n", new String(opened.get(1).toByteArray(), StandardCharsets.UTF_8));
    }

    @Test public void moduleWriterRejectsBadSlicesBeforeOpening() {
        int[] opens = {0};
        ModuleRemoteTraceWriter102 writer = new ModuleRemoteTraceWriter102("trace.jsonl", 4, name -> {
            opens[0]++;
            return new Opened();
        });
        byte[] buffer = new byte[10];
        assertThrows(NullPointerException.class, () -> writer.writeRecord(null, 0, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> writer.writeRecord(buffer, -1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> writer.writeRecord(buffer, 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> writer.writeRecord(buffer, 8, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> writer.writeRecord(buffer, Integer.MAX_VALUE, 2));
        assertThrows(IllegalArgumentException.class, () -> writer.writeRecord(buffer, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> writer.writeRecord(buffer, 0, 5));
        assertEquals(0, opens[0]);
    }

    @Test public void moduleSideJsonLineTransportWritesWithoutTrimCopy() throws Exception {
        Opened stream = new Opened();
        ModuleRemoteTraceWriter102 writer = new ModuleRemoteTraceWriter102("trace.jsonl", 4096, name -> stream);
        CallbackExceptionTracker tracker = new CallbackExceptionTracker("module", 2);
        RuntimeException failure = new IllegalStateException("module failure");
        try {
            tracker.before(ignored -> { throw failure; }).before(new StubInvocation());
            fail("callback did not throw");
        } catch (Throwable thrown) {
            assertSame(failure, thrown);
        }
        CallbackExceptionTracker.Event event = tracker.peekNext();
        byte[] expected = JsonLineTraceTransport.encode(event, true, 2);
        assertEquals(1, tracker.deliver(new JsonLineTraceTransport(writer, true, 2), 1));
        assertArrayEquals(expected, stream.toByteArray());
        assertEquals(1, stream.writes);
    }

    @Test public void binderSenderIsSliceCapableAndChecksSlicesBeforeAnyParcel() {
        JsonLineTraceTransport.RecordWriter sender = BinderTrace102.sender(new UnusedBinder(), "trace.jsonl", 8);
        assertTrue(sender instanceof JsonLineTraceTransport.SlicedRecordWriter);
        JsonLineTraceTransport.SlicedRecordWriter sliced = (JsonLineTraceTransport.SlicedRecordWriter) sender;
        byte[] buffer = new byte[16];
        NullPointerException missing = assertThrows(NullPointerException.class, () -> sliced.writeRecord(null, 0, 1));
        assertEquals("record", missing.getMessage());
        assertEquals("record", assertThrows(NullPointerException.class, () -> sender.writeRecord(null)).getMessage());
        assertThrows(IndexOutOfBoundsException.class, () -> sliced.writeRecord(buffer, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> sliced.writeRecord(buffer, 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> sliced.writeRecord(buffer, 10, 7));
        assertThrows(IllegalArgumentException.class, () -> sliced.writeRecord(buffer, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> sliced.writeRecord(buffer, 0, 9));
        assertThrows(IllegalArgumentException.class, () -> sender.writeRecord(new byte[9]));
        assertThrows(IllegalArgumentException.class, () -> sender.writeRecord(new byte[0]));
    }

    /** Never reached: every call above fails its argument checks first. */
    private static final class UnusedBinder implements IBinder {
        @Override public String getInterfaceDescriptor() { throw new AssertionError(); }
        @Override public boolean pingBinder() { throw new AssertionError(); }
        @Override public boolean isBinderAlive() { throw new AssertionError(); }
        @Override public IInterface queryLocalInterface(String descriptor) { throw new AssertionError(); }
        @Override public void dump(FileDescriptor fd, String[] args) { throw new AssertionError(); }
        @Override public void dumpAsync(FileDescriptor fd, String[] args) { throw new AssertionError(); }
        @Override public boolean transact(int code, Parcel data, Parcel reply, int flags) { throw new AssertionError(); }
        @Override public void linkToDeath(DeathRecipient recipient, int flags) { throw new AssertionError(); }
        @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) { throw new AssertionError(); }
    }

    private static final class StubInvocation implements io.github.jichuo1.meguviz.hook.api.HookInvocation {
        @Override public io.github.jichuo1.meguviz.hook.api.HookId id() {
            return io.github.jichuo1.meguviz.hook.api.HookId.of("module.hook");
        }
        @Override public long generation() { return 1L; }
        @Override public java.lang.reflect.Executable target() { throw new UnsupportedOperationException(); }
        @Override public Object thisObject() { throw new UnsupportedOperationException(); }
        @Override public io.github.jichuo1.meguviz.hook.api.ParameterAccess access() {
            return io.github.jichuo1.meguviz.hook.api.ParameterAccess.NO_ARGS;
        }
        @Override public Thread ownerThread() { return Thread.currentThread(); }
        @Override public void setEarlyResult(Object value) { throw new UnsupportedOperationException(); }
        @Override public void setEarlyThrowable(Throwable throwable) { throw new UnsupportedOperationException(); }
        @Override public boolean hasEarlyOutcome() { return false; }
    }
}
