package io.github.jichuo1.meguviz.hook.core;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Contracts for bounded session-owned compression scratch; no mutable buffer leaves the codec. */
public final class CompressionScratchReuseTest {
    private static final int RETAIN_LIMIT = 16 * 1024;

    private static BoundedTraceCompression codec(BoundedTraceCompression.Algorithm algorithm, int maxFrame) {
        return new BoundedTraceCompression(65536, maxFrame, 0, 0, algorithm);
    }

    private static byte[] repeated(int size, byte value) {
        byte[] data = new byte[size];
        Arrays.fill(data, value);
        return data;
    }

    private static byte[] random(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static byte[] scratch(BoundedTraceCompression.Session session) throws Exception {
        Field field = BoundedTraceCompression.Session.class.getDeclaredField("retainedScratch");
        field.setAccessible(true);
        return (byte[]) field.get(session);
    }

    private static BoundedTraceCompression.Session delegate(BoundedTraceCompression.SerializedSession session)
            throws Exception {
        Field field = BoundedTraceCompression.SerializedSession.class.getDeclaredField("delegate");
        field.setAccessible(true);
        return (BoundedTraceCompression.Session) field.get(session);
    }

    private static BoundedTraceCompression.Encoding assertSameEncoding(BoundedTraceCompression codec,
            BoundedTraceCompression.Session session, byte[] input) throws Exception {
        BoundedTraceCompression.Encoding expected = codec.encode(input);
        BoundedTraceCompression.Encoding actual = session.encode(input);
        assertEquals(expected.reason(), actual.reason());
        assertEquals(expected.compressed(), actual.compressed());
        assertArrayEquals(expected.bytes(), actual.bytes());
        assertArrayEquals(input, session.decode(actual.bytes()));
        return actual;
    }

    @Test public void reusedScratchKeepsPerCallCandidateLimitAndFrameSemantics() throws Exception {
        for (BoundedTraceCompression.Algorithm algorithm : BoundedTraceCompression.Algorithm.values()) {
            BoundedTraceCompression codec = codec(algorithm, 65556);
            BoundedTraceCompression.Session session = codec.newSession();
            try {
                byte[] large = repeated(12000, (byte) 'a');
                assertSameEncoding(codec, session, large);
                byte[] retained = scratch(session);
                assertNotNull(retained);
                assertTrue(retained.length >= 12000 - BoundedTraceCompression.HEADER_BYTES - 1);
                assertTrue(retained.length <= RETAIN_LIMIT);

                // This call's strict net-savings cap is only 3 bytes. Reusing a larger array must
                // not let the deflater emit beyond that logical cap and choose compression.
                BoundedTraceCompression.Encoding shortResult = assertSameEncoding(codec, session,
                        repeated(24, (byte) 'a'));
                assertEquals(BoundedTraceCompression.Decision.INSUFFICIENT_SAVINGS, shortResult.reason());
                assertSame(retained, scratch(session));

                BoundedTraceCompression.Encoding randomResult = assertSameEncoding(codec, session,
                        random(6000, 537));
                assertEquals(BoundedTraceCompression.Decision.INSUFFICIENT_SAVINGS, randomResult.reason());
                assertSame(retained, scratch(session));
                assertSameEncoding(codec, session, repeated(32000, (byte) 'b'));
                byte[] afterOversizedCandidate = scratch(session);
                assertTrue(afterOversizedCandidate == null || afterOversizedCandidate.length <= RETAIN_LIMIT);
                assertSameEncoding(codec, session, large);
            } finally {
                session.close();
            }
            assertNull(scratch(session));
        }
    }

    @Test public void compressedToFitStillMatchesStatelessAfterScratchReuse() throws Exception {
        for (BoundedTraceCompression.Algorithm algorithm : BoundedTraceCompression.Algorithm.values()) {
            BoundedTraceCompression codec = codec(algorithm, 4096);
            BoundedTraceCompression.Session session = codec.newSession();
            try {
                assertSameEncoding(codec, session, repeated(3000, (byte) 'q'));
                byte[] retained = scratch(session);
                assertNotNull(retained);
                BoundedTraceCompression.Encoding fit = assertSameEncoding(codec, session,
                        repeated(8000, (byte) 'q'));
                assertEquals(BoundedTraceCompression.Decision.COMPRESSED_TO_FIT, fit.reason());
                assertTrue(scratch(session).length <= RETAIN_LIMIT);
                byte[] cannotFit = random(8000, 1401);
                assertThrows(IOException.class, () -> codec.encode(cannotFit));
                assertThrows(IOException.class, () -> session.encode(cannotFit));
                assertSameEncoding(codec, session, repeated(24, (byte) 'q'));
            } finally {
                session.close();
            }
            assertNull(scratch(session));
        }
    }

    @Test public void serializedSessionReusesScratchAcrossSequentialThreadsAndReleasesIt() throws Exception {
        for (BoundedTraceCompression.Algorithm algorithm : BoundedTraceCompression.Algorithm.values()) {
            BoundedTraceCompression codec = codec(algorithm, 65556);
            BoundedTraceCompression.SerializedSession session = codec.newSerializedSession();
            BoundedTraceCompression.Session delegate = delegate(session);
            byte[] large = repeated(10000, (byte) 'x');
            byte[] shortInput = repeated(24, (byte) 'x');
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            AtomicReference<byte[]> firstFrame = new AtomicReference<byte[]>();
            AtomicReference<byte[]> retained = new AtomicReference<byte[]>();
            Thread first = new Thread(() -> {
                try {
                    firstFrame.set(session.encode(large).bytes());
                    retained.set(scratch(delegate));
                } catch (Throwable error) { failure.set(error); }
            }, "compression-scratch-first");
            Thread second = new Thread(() -> {
                try {
                    assertArrayEquals(codec.encode(shortInput).bytes(), session.encode(shortInput).bytes());
                    assertArrayEquals(large, session.decode(firstFrame.get()));
                    assertSame(retained.get(), scratch(delegate));
                } catch (Throwable error) { failure.set(error); }
            }, "compression-scratch-second");
            first.setDaemon(true);
            second.setDaemon(true);
            try {
                first.start();
                first.join(5000);
                assertFalse(first.isAlive());
                assertNull(failure.get());
                assertNotNull(retained.get());
                assertTrue(retained.get().length <= RETAIN_LIMIT);
                assertArrayEquals(codec.encode(large).bytes(), firstFrame.get());
                second.start();
                second.join(5000);
                assertFalse(second.isAlive());
                assertNull(failure.get());
            } finally {
                session.close();
            }
            assertNull(scratch(delegate));
        }
    }
}
