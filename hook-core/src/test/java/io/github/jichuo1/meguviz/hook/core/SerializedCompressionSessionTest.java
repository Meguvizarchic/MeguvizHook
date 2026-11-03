package io.github.jichuo1.meguviz.hook.core;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SerializedCompressionSessionTest {
    private static BoundedTraceCompression codec() {
        return new BoundedTraceCompression(4096, 4116, 1, 1, BoundedTraceCompression.Algorithm.ZLIB);
    }

    private static void setDeflater(BoundedTraceCompression.SerializedSession session, Deflater value) throws Exception {
        Field delegateField = session.getClass().getDeclaredField("delegate");
        delegateField.setAccessible(true);
        Object delegate = delegateField.get(session);
        Field field = delegate.getClass().getDeclaredField("deflater");
        field.setAccessible(true);
        field.set(delegate, value);
    }

    @Test public void overlapAndCloseFailWithoutWaitingThenSessionCanMoveThreads() throws Exception {
        BoundedTraceCompression codec = codec();
        BoundedTraceCompression.SerializedSession session = codec.newSerializedSession();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicReference<byte[]> encoded = new AtomicReference<byte[]>();
        byte[] input = new byte[2048];
        Arrays.fill(input, (byte) 'a');
        Thread first = null;
        Thread second = null;
        try {
            setDeflater(session, new Deflater(Deflater.BEST_SPEED) {
                @Override public int deflate(byte[] out, int offset, int length) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    return super.deflate(out, offset, length);
                }
            });
            first = new Thread(() -> {
                try { encoded.set(session.encode(input).bytes()); } catch (Throwable error) { failure.set(error); }
            }, "serialized-codec-first");
            first.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            second = new Thread(() -> {
                try {
                    assertThrows(IllegalStateException.class, () -> session.encode(input));
                    assertThrows(IllegalStateException.class, () -> session.decode(new byte[20]));
                    assertThrows(IllegalStateException.class, session::close);
                } catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { checked.countDown(); }
            }, "serialized-codec-contender");
            second.start();
            assertTrue("contender must finish before codec is released", checked.await(5, TimeUnit.SECONDS));
            release.countDown();
            first.join(5000);
            second.join(5000);
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertNull(failure.get());
            assertArrayEquals(input, session.decode(encoded.get()));
            session.close();
            session.close();
            assertThrows(IllegalStateException.class, () -> session.encode(input));
        } finally {
            release.countDown();
            if (first != null) first.join(5000);
            if (second != null) second.join(5000);
            session.close();
        }
    }

    @Test public void sameThreadCodecReentryIsRejectedAndOuterEncodingCompletes() throws Exception {
        BoundedTraceCompression codec = codec();
        BoundedTraceCompression.SerializedSession session = codec.newSerializedSession();
        AtomicReference<Throwable> nested = new AtomicReference<Throwable>();
        byte[] input = new byte[1024];
        try {
            setDeflater(session, new Deflater(Deflater.BEST_SPEED) {
                private boolean attempted;
                @Override public int deflate(byte[] out, int offset, int length) {
                    if (!attempted) {
                        attempted = true;
                        try { session.encode(input); } catch (Throwable error) { nested.set(error); }
                    }
                    return super.deflate(out, offset, length);
                }
            });
            byte[] frame = session.encode(input).bytes();
            assertTrue(nested.get() instanceof IllegalStateException);
            assertArrayEquals(input, session.decode(frame));
        } finally { session.close(); }
    }

    @Test public void writerCanReenterAndCloseAfterEncodingWithoutHoldingCodecLock() throws Exception {
        BoundedTraceCompression codec = codec();
        BoundedTraceCompression.SerializedSession session = codec.newSerializedSession();
        byte[] input = new byte[1024];
        AtomicReference<byte[]> decoded = new AtomicReference<byte[]>();
        try {
            JsonLineTraceTransport.RecordWriter writer = session.encodingWriter(frame -> {
                decoded.set(session.decode(frame));
                session.close();
            });
            writer.writeRecord(input);
            assertArrayEquals(input, decoded.get());
            assertThrows(IllegalStateException.class, () -> writer.writeRecord(input));
        } finally { session.close(); }
    }

    @Test public void failedResetPoisonsSessionButCloseStillEndsNativeCodec() throws Exception {
        BoundedTraceCompression codec = codec();
        BoundedTraceCompression.SerializedSession session = codec.newSerializedSession();
        IllegalStateException resetFailure = new IllegalStateException("synthetic reset failure");
        try {
            setDeflater(session, new Deflater(Deflater.BEST_SPEED) {
                @Override public void reset() { throw resetFailure; }
            });
            assertSame(resetFailure, assertThrows(IllegalStateException.class,
                    () -> session.encode(new byte[1024])));
            assertThrows(IllegalStateException.class, () -> session.encode(new byte[1024]));
        } finally { session.close(); }
        session.close();
        assertThrows(IllegalStateException.class, () -> session.decode(new byte[20]));
    }
}
