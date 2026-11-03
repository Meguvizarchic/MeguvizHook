package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.core.BatchTraceTransport;
import io.github.jichuo1.meguviz.hook.core.JsonLineTraceTransport;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Batch appends through one caller-provided stream; no service, device or filesystem is used. */
public final class ModuleRemoteTraceBatchWriterTest {
    private static final class Opened extends ByteArrayOutputStream {
        int flushes;
        int closes;
        int writes;
        IOException failOnWrite;
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            writes++;
            if (failOnWrite != null && writes == 2) throw new IllegalStateException(failOnWrite);
            super.write(bytes, offset, length);
        }
        @Override public void flush() { flushes++; }
        @Override public void close() { closes++; }
    }

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Test public void oneBatchUsesOneStreamOneFlushAndKeepsOrder() throws Exception {
        List<Opened> opened = new ArrayList<Opened>();
        ModuleRemoteTraceWriter102 writer = new ModuleRemoteTraceWriter102("trace.jsonl", 16, name -> {
            assertEquals("trace.jsonl", name);
            Opened stream = new Opened();
            opened.add(stream);
            return stream;
        });
        writer.writeBatch(Arrays.asList(utf8("a\n"), utf8("bb\n"), utf8("ccc\n")));
        assertEquals(1, opened.size());
        assertEquals("a\nbb\nccc\n", new String(opened.get(0).toByteArray(), StandardCharsets.UTF_8));
        assertEquals(3, opened.get(0).writes);
        assertEquals(1, opened.get(0).flushes);
        assertEquals(1, opened.get(0).closes);
        // The single-record path is unchanged: one stream per record.
        writer.writeRecord(utf8("d\n"));
        assertEquals(2, opened.size());
    }

    @Test public void invalidRecordsAreRejectedBeforeOpening() {
        int[] opens = {0};
        ModuleRemoteTraceWriter102 writer = new ModuleRemoteTraceWriter102("trace.jsonl", 4, name -> {
            opens[0]++;
            return new Opened();
        });
        assertThrows(IllegalArgumentException.class, () -> writer.writeBatch(Arrays.asList(utf8("ok"), new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> writer.writeBatch(Arrays.asList(utf8("ok"), utf8("12345"))));
        assertThrows(NullPointerException.class, () -> writer.writeBatch(Arrays.asList(utf8("ok"), null)));
        assertThrows(IllegalArgumentException.class, () -> writer.writeBatch(Collections.<byte[]>emptyList()));
        assertThrows(NullPointerException.class, () -> writer.writeBatch(null));
        assertEquals(0, opens[0]);
    }

    @Test public void failureAfterOpeningClosesTheStreamAndPropagates() {
        Opened stream = new Opened();
        stream.failOnWrite = new IOException("disk full");
        ModuleRemoteTraceWriter102 writer = new ModuleRemoteTraceWriter102("trace.jsonl", 16, name -> stream);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> writer.writeBatch(Arrays.asList(utf8("a\n"), utf8("b\n"), utf8("c\n"))));
        assertSame(stream.failOnWrite, failure.getCause());
        // The first record may already be appended: a retry can duplicate it.
        assertEquals("a\n", new String(stream.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(1, stream.closes);
        assertEquals(0, stream.flushes);
    }

    @Test public void batchReceiverAppendsAFrameThroughOneStream() throws Exception {
        List<Opened> opened = new ArrayList<Opened>();
        ModuleRemoteTraceWriter102 writer = new ModuleRemoteTraceWriter102("trace.jsonl", 64, name -> {
            Opened stream = new Opened();
            opened.add(stream);
            return stream;
        });
        JsonLineTraceTransport.RecordWriter receiver = BatchTraceTransport.receiver(writer, 4096, 32);
        byte[] first = utf8("{\"n\":1}\n");
        byte[] second = utf8("{\"n\":2}\n");
        byte[] frame = ByteBuffer.allocate(8 + 8 + first.length + second.length).putInt(0x4d484231).putInt(2)
                .putInt(first.length).put(first).putInt(second.length).put(second).array();
        receiver.writeRecord(frame);
        assertEquals(1, opened.size());
        assertEquals("{\"n\":1}\n{\"n\":2}\n", new String(opened.get(0).toByteArray(), StandardCharsets.UTF_8));
    }
}
