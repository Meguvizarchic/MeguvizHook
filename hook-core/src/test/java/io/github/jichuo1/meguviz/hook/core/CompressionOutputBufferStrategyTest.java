package io.github.jichuo1.meguviz.hook.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Wire and lifecycle contracts for explicitly selected session output buffering. */
public final class CompressionOutputBufferStrategyTest {
    private static final int RETAIN_LIMIT = 16 * 1024;

    private static BoundedTraceCompression codec(BoundedTraceCompression.Algorithm algorithm,
                                                   int maxFrameBytes, int thresholdBytes) {
        return new BoundedTraceCompression(65536, maxFrameBytes, thresholdBytes, 0, algorithm);
    }

    private static byte[] repeated(int size, byte value) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static byte[] random(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static byte[] repeatedRandomHalf() {
        byte[] half = random(20000, 1441);
        byte[] bytes = new byte[half.length * 2];
        System.arraycopy(half, 0, bytes, 0, half.length);
        System.arraycopy(half, 0, bytes, half.length, half.length);
        return bytes;
    }

    private static BoundedTraceCompression.Encoding compare(BoundedTraceCompression codec,
            BoundedTraceCompression.Session standard, BoundedTraceCompression.Session staged,
            byte[] input) throws IOException {
        BoundedTraceCompression.Encoding expected = codec.encode(input);
        BoundedTraceCompression.Encoding ordinary = standard.encode(input);
        BoundedTraceCompression.Encoding candidate = staged.encode(input);
        assertEquals(expected.reason(), ordinary.reason());
        assertEquals(expected.reason(), candidate.reason());
        assertEquals(expected.compressed(), candidate.compressed());
        assertEquals(expected.plainBytes(), candidate.plainBytes());
        assertEquals(expected.frameBytes(), candidate.frameBytes());
        assertArrayEquals(expected.bytes(), ordinary.bytes());
        assertArrayEquals(expected.bytes(), candidate.bytes());
        assertArrayEquals(input, standard.decode(candidate.bytes()));
        assertArrayEquals(input, staged.decode(ordinary.bytes()));
        assertArrayEquals(input, codec.decode(candidate.bytes()));
        return candidate;
    }

    @Test public void strategyMatchesStandardAcrossOutputCapsAndMixedInputs() throws Exception {
        for (BoundedTraceCompression.Algorithm algorithm : BoundedTraceCompression.Algorithm.values()) {
            BoundedTraceCompression codec = codec(algorithm, 65556, 0);
            try (BoundedTraceCompression.Session standard = codec.newSession(BoundedTraceCompression.OutputBufferStrategy.STANDARD);
                 BoundedTraceCompression.Session staged = codec.newSession(BoundedTraceCompression.OutputBufferStrategy.STAGED);
                 BoundedTraceCompression.Session defaultSession = codec.newSession()) {
                // With zero minimum saving, raw-fit maxCandidate = input length - header - 1.
                int below = RETAIN_LIMIT + BoundedTraceCompression.HEADER_BYTES;
                int exact = below + 1;
                int above = exact + 1;
                byte[][] values = {
                        new byte[0], repeated(1, (byte) 'a'), repeated(below, (byte) 'b'),
                        repeated(exact, (byte) 'c'), repeated(above, (byte) 'd'),
                        random(32768, 335), repeatedRandomHalf(), repeated(24, (byte) 'x'),
                        random(4096, 909), repeated(32768, (byte) 'z')
                };
                for (byte[] input : values) {
                    BoundedTraceCompression.Encoding actual = compare(codec, standard, staged, input);
                    BoundedTraceCompression.Encoding defaultResult = defaultSession.encode(input);
                    assertEquals(actual.reason(), defaultResult.reason());
                    assertArrayEquals(actual.bytes(), defaultResult.bytes());
                }
                assertEquals(BoundedTraceCompression.Decision.INSUFFICIENT_SAVINGS,
                        compare(codec, standard, staged, repeated(24, (byte) 'x')).reason());
                assertEquals(BoundedTraceCompression.Decision.INSUFFICIENT_SAVINGS,
                        compare(codec, standard, staged, random(32768, 335)).reason());
                BoundedTraceCompression.Encoding mixed = compare(codec, standard, staged, repeatedRandomHalf());
                assertEquals(BoundedTraceCompression.Decision.COMPRESSED, mixed.reason());
                assertTrue("compressed payload should cross the first staged buffer",
                        mixed.frameBytes() - BoundedTraceCompression.HEADER_BYTES > RETAIN_LIMIT);
            }
        }
    }

    @Test public void toFitFailureRecoveryAndIndependentWriterFramesMatchStandard() throws Exception {
        for (BoundedTraceCompression.Algorithm algorithm : BoundedTraceCompression.Algorithm.values()) {
            BoundedTraceCompression codec = codec(algorithm, 24000, 0);
            try (BoundedTraceCompression.Session standard = codec.newSession(BoundedTraceCompression.OutputBufferStrategy.STANDARD);
                 BoundedTraceCompression.Session staged = codec.newSession(BoundedTraceCompression.OutputBufferStrategy.STAGED)) {
                byte[] fit = repeatedRandomHalf();
                BoundedTraceCompression.Encoding actual = compare(codec, standard, staged, fit);
                assertEquals(BoundedTraceCompression.Decision.COMPRESSED_TO_FIT, actual.reason());
                assertTrue(actual.frameBytes() - BoundedTraceCompression.HEADER_BYTES > RETAIN_LIMIT);
                byte[] cannotFit = random(40000, 190);
                assertThrows(IOException.class, () -> codec.encode(cannotFit));
                assertThrows(IOException.class, () -> standard.encode(cannotFit));
                assertThrows(IOException.class, () -> staged.encode(cannotFit));
                compare(codec, standard, staged, repeated(24, (byte) 'q'));
                compare(codec, standard, staged, fit);

                List<byte[]> frames = new ArrayList<byte[]>();
                JsonLineTraceTransport.RecordWriter writer = staged.encodingWriter(frames::add);
                writer.writeRecord(fit);
                byte[] firstCopy = frames.get(0).clone();
                writer.writeRecord(repeated(2000, (byte) 'r'));
                assertNotSame(frames.get(0), frames.get(1));
                assertArrayEquals(firstCopy, frames.get(0));
                assertArrayEquals(fit, codec.decode(frames.get(0)));
                frames.get(0)[0] ^= 1;
                assertArrayEquals(repeated(2000, (byte) 'r'), codec.decode(frames.get(1)));
            }
        }
    }

    @Test public void thresholdDefaultAndNullPolicyAreExplicit() throws Exception {
        BoundedTraceCompression codec = codec(BoundedTraceCompression.Algorithm.ZLIB, 65556, 4096);
        assertThrows(NullPointerException.class, () -> codec.newSession(null));
        assertThrows(NullPointerException.class, () -> codec.newSerializedSession(null));
        try (BoundedTraceCompression.Session standard = codec.newSession();
             BoundedTraceCompression.Session staged = codec.newSession(BoundedTraceCompression.OutputBufferStrategy.STAGED)) {
            byte[] shortValue = repeated(1024, (byte) 'a');
            assertEquals(BoundedTraceCompression.Decision.BELOW_THRESHOLD,
                    compare(codec, standard, staged, shortValue).reason());
        }
        try (BoundedTraceCompression.SerializedSession defaultSession = codec.newSerializedSession();
             BoundedTraceCompression.SerializedSession explicitStandard = codec.newSerializedSession(
                     BoundedTraceCompression.OutputBufferStrategy.STANDARD)) {
            byte[] input = repeatedRandomHalf();
            BoundedTraceCompression.Encoding expected = explicitStandard.encode(input);
            BoundedTraceCompression.Encoding actual = defaultSession.encode(input);
            assertEquals(expected.reason(), actual.reason());
            assertArrayEquals(expected.bytes(), actual.bytes());
        }
    }

    @Test public void stagedSerializedSessionCanMoveThreadsAndClosesNormally() throws Exception {
        for (BoundedTraceCompression.Algorithm algorithm : BoundedTraceCompression.Algorithm.values()) {
            BoundedTraceCompression codec = codec(algorithm, 65556, 0);
            BoundedTraceCompression.SerializedSession session = codec.newSerializedSession(
                    BoundedTraceCompression.OutputBufferStrategy.STAGED);
            byte[] firstInput = repeatedRandomHalf();
            byte[] secondInput = repeated(24, (byte) 's');
            AtomicReference<byte[]> firstFrame = new AtomicReference<byte[]>();
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            Thread first = new Thread(() -> {
                try { firstFrame.set(session.encode(firstInput).bytes()); }
                catch (Throwable error) { failure.set(error); }
            }, "staged-first-worker");
            Thread second = new Thread(() -> {
                try {
                    assertArrayEquals(firstInput, session.decode(firstFrame.get()));
                    BoundedTraceCompression.Encoding expected = codec.encode(secondInput);
                    BoundedTraceCompression.Encoding encoded = session.encode(secondInput);
                    assertEquals(expected.reason(), encoded.reason());
                    assertArrayEquals(expected.bytes(), encoded.bytes());
                } catch (Throwable error) { failure.set(error); }
            }, "staged-second-worker");
            first.setDaemon(true); second.setDaemon(true);
            try {
                first.start(); first.join(5000);
                assertFalse(first.isAlive()); assertNull(failure.get());
                assertArrayEquals(codec.encode(firstInput).bytes(), firstFrame.get());
                second.start(); second.join(5000);
                assertFalse(second.isAlive()); assertNull(failure.get());
            } finally { session.close(); }
            session.close();
            assertThrows(IllegalStateException.class, () -> session.encode(secondInput));
            assertThrows(IllegalStateException.class, () -> session.decode(firstFrame.get()));
        }
    }
}
