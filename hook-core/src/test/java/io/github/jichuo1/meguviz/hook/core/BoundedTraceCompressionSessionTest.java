package io.github.jichuo1.meguviz.hook.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.junit.Test;
import static org.junit.Assert.*;

/** Independent-frame, failure-recovery and ownership contracts for the reusable codec. */
public final class BoundedTraceCompressionSessionTest {
    private static BoundedTraceCompression codec(BoundedTraceCompression.Algorithm algorithm) {
        return new BoundedTraceCompression(16384, 16404, 128, 32, algorithm);
    }

    @Test public void repeatedFramesMatchStatelessEncodingAndCrossDecode() throws Exception {
        byte[] empty = new byte[0];
        byte[] shortValue = { '{', '}', '\n' };
        byte[] random = new byte[8192]; new Random(702).nextBytes(random);
        byte[] repetitive = new byte[8192]; Arrays.fill(repetitive, (byte) 'a');
        byte[] different = new byte[8192]; Arrays.fill(different, (byte) 'b');
        byte[][] inputs = { empty, shortValue, random, repetitive, different, random, repetitive };
        BoundedTraceCompression ordinary = codec(BoundedTraceCompression.Algorithm.ZLIB);
        BoundedTraceCompression schema = codec(BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
        try (BoundedTraceCompression.Session ordinarySession = ordinary.newSession();
             BoundedTraceCompression.Session schemaSession = schema.newSession()) {
            for (byte[] input : inputs) {
                BoundedTraceCompression.Encoding ordinaryExpected = ordinary.encode(input);
                BoundedTraceCompression.Encoding schemaExpected = schema.encode(input);
                BoundedTraceCompression.Encoding ordinaryActual = ordinarySession.encode(input);
                BoundedTraceCompression.Encoding schemaActual = schemaSession.encode(input);
                assertEquals(ordinaryExpected.reason(), ordinaryActual.reason());
                assertEquals(schemaExpected.reason(), schemaActual.reason());
                assertArrayEquals(ordinaryExpected.bytes(), ordinaryActual.bytes());
                assertArrayEquals(schemaExpected.bytes(), schemaActual.bytes());
                assertArrayEquals(input, ordinary.decode(ordinaryActual.bytes()));
                assertArrayEquals(input, schema.decode(schemaActual.bytes()));
                // Decoding is keyed by the frame's method, not the encoder algorithm of this session.
                assertArrayEquals(input, schemaSession.decode(ordinaryExpected.bytes()));
                assertArrayEquals(input, ordinarySession.decode(schemaExpected.bytes()));
            }
        }
    }

    @Test public void malformedCompressedPayloadAndChecksumDoNotContaminateNextFrame() throws Exception {
        BoundedTraceCompression codec = codec(BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
        byte[] input = new byte[8192]; Arrays.fill(input, (byte) 5);
        byte[] valid = codec.encode(input).bytes();
        assertTrue(codec.encode(input).compressed());
        byte[] extra = Arrays.copyOf(valid, valid.length + 1);
        ByteBuffer.wrap(extra).putInt(12, extra.length - BoundedTraceCompression.HEADER_BYTES);
        extra[extra.length - 1] = 1;
        byte[] badChecksum = valid.clone(); badChecksum[19] ^= 1;
        try (BoundedTraceCompression.Session session = codec.newSession()) {
            assertThrows(IOException.class, () -> session.decode(extra));
            assertArrayEquals(input, session.decode(valid));
            assertThrows(IOException.class, () -> session.decode(badChecksum));
            assertArrayEquals(input, session.decode(valid));
        }
    }

    @Test public void failedFitAttemptDoesNotAffectFollowingCompression() throws Exception {
        BoundedTraceCompression codec = new BoundedTraceCompression(4096, 128, 0, 8,
                BoundedTraceCompression.Algorithm.ZLIB);
        byte[] random = new byte[4096]; new Random(992).nextBytes(random);
        byte[] zeros = new byte[4096];
        try (BoundedTraceCompression.Session session = codec.newSession()) {
            assertThrows(IOException.class, () -> session.encode(random));
            BoundedTraceCompression.Encoding encoded = session.encode(zeros);
            assertEquals(BoundedTraceCompression.Decision.COMPRESSED_TO_FIT, encoded.reason());
            assertArrayEquals(codec.encode(zeros).bytes(), encoded.bytes());
            assertArrayEquals(zeros, codec.decode(encoded.bytes()));
        }
    }

    @Test public void oversizedExpansionIsRejectedAndSessionRemainsUsable() throws Exception {
        for (BoundedTraceCompression.Algorithm algorithm : BoundedTraceCompression.Algorithm.values()) {
            BoundedTraceCompression codec = codec(algorithm);
            byte[] input = new byte[1024];
            byte[] valid = codec.encode(input).bytes();
            byte[] tooShort = valid.clone();
            ByteBuffer.wrap(tooShort).putInt(8, input.length - 1);
            try (BoundedTraceCompression.Session session = codec.newSession()) {
                IOException failure = assertThrows(IOException.class, () -> session.decode(tooShort));
                assertEquals("trace expansion overflow", failure.getMessage());
                assertArrayEquals(input, session.decode(valid));
            }
        }
    }

    @Test public void wrongPresetDictionaryDoesNotPoisonLaterValidFrame() throws Exception {
        byte[] input = new byte[1024];
        byte[] otherDictionary = "foreign-schema-for-negative-test".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = new byte[2048];
        int length;
        Deflater foreign = new Deflater(Deflater.BEST_SPEED);
        try {
            foreign.setDictionary(otherDictionary);
            foreign.setInput(input);
            foreign.finish();
            length = foreign.deflate(compressed);
            assertTrue(foreign.finished());
        } finally { foreign.end(); }
        CRC32 crc = new CRC32(); crc.update(input);
        byte[] invalid = ByteBuffer.allocate(BoundedTraceCompression.HEADER_BYTES + length)
                .putInt(0x4d485443).put((byte) 1).put((byte) 2).putShort((short) 0)
                .putInt(input.length).putInt(length).putInt((int) crc.getValue())
                .put(compressed, 0, length).array();
        BoundedTraceCompression codec = codec(BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
        byte[] valid = codec.encode(input).bytes();
        try (BoundedTraceCompression.Session session = codec.newSession()) {
            assertThrows(IOException.class, () -> session.decode(invalid));
            assertArrayEquals(input, session.decode(valid));
        }
    }

    @Test public void crossThreadUseIsRejectedWithoutDamagingOwnerSession() throws Exception {
        BoundedTraceCompression codec = codec(BoundedTraceCompression.Algorithm.ZLIB);
        BoundedTraceCompression.Session session = codec.newSession();
        byte[] input = new byte[1024];
        byte[] frame = session.encode(input).bytes();
        AtomicReference<Throwable> encodeFailure = new AtomicReference<Throwable>();
        AtomicReference<Throwable> decodeFailure = new AtomicReference<Throwable>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<Throwable>();
        Thread other = new Thread(() -> {
            try { session.encode(input); } catch (Throwable failure) { encodeFailure.set(failure); }
            try { session.decode(frame); } catch (Throwable failure) { decodeFailure.set(failure); }
            try { session.close(); } catch (Throwable failure) { closeFailure.set(failure); }
        }, "foreign-codec-session-thread");
        other.setDaemon(true);
        other.start(); other.join(5000);
        assertFalse("foreign thread did not finish", other.isAlive());
        assertTrue(encodeFailure.get() instanceof IllegalStateException);
        assertTrue(decodeFailure.get() instanceof IllegalStateException);
        assertTrue(closeFailure.get() instanceof IllegalStateException);
        assertArrayEquals(input, session.decode(frame));
        session.close();
        session.close();
        assertThrows(IllegalStateException.class, () -> session.encode(input));
        assertThrows(IllegalStateException.class, () -> session.decode(frame));
        assertThrows(IllegalStateException.class, () -> session.encodingWriter(bytes -> { }));
    }

    @Test public void pairedWritersKeepFailureAndClosedSessionVisible() throws Exception {
        BoundedTraceCompression codec = codec(BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
        BoundedTraceCompression.Session session = codec.newSession();
        byte[] input = new byte[1024]; Arrays.fill(input, (byte) 7);
        AtomicReference<byte[]> received = new AtomicReference<byte[]>();
        JsonLineTraceTransport.RecordWriter paired = session.encodingWriter(session.decodingWriter(received::set));
        paired.writeRecord(input);
        assertArrayEquals(input, received.get());
        IOException failure = new IOException("receiver unavailable");
        JsonLineTraceTransport.RecordWriter failing = session.encodingWriter(bytes -> { throw failure; });
        assertSame(failure, assertThrows(IOException.class, () -> failing.writeRecord(input)));
        paired.writeRecord(input);
        assertArrayEquals(input, received.get());
        session.close();
        assertThrows(IllegalStateException.class, () -> paired.writeRecord(input));
    }

    /** Fault injection is needed because real zlib reset failures are not reliably reproducible. */
    @Test public void fatalDuringCompressionSurvivesResetFailureAndRequiresClose() throws Exception {
        BoundedTraceCompression codec = codec(BoundedTraceCompression.Algorithm.ZLIB);
        BoundedTraceCompression.Session session = codec.newSession();
        SyntheticFatal fatal = new SyntheticFatal();
        IllegalStateException reset = new IllegalStateException("synthetic reset failure");
        FaultyDeflater deflater = new FaultyDeflater(fatal, reset);
        inject(session, "deflater", deflater);
        assertSame(fatal, assertThrows(SyntheticFatal.class, () -> session.encode(new byte[1024])));
        assertArrayEquals(new Throwable[] { reset }, fatal.getSuppressed());
        assertThrows(IllegalStateException.class, () -> session.encode(new byte[1]));
        session.close();
        assertTrue(deflater.ended);
    }

    @Test public void malformedInflateSurvivesResetFailureAndClosesBothCodecs() throws Exception {
        BoundedTraceCompression codec = codec(BoundedTraceCompression.Algorithm.ZLIB);
        byte[] input = new byte[1024];
        byte[] frame = codec.encode(input).bytes();
        BoundedTraceCompression.Session session = codec.newSession();
        FaultyInflater inflater = new FaultyInflater();
        TrackingDeflater deflater = new TrackingDeflater();
        inject(session, "inflater", inflater);
        inject(session, "deflater", deflater);
        IOException failure = assertThrows(IOException.class, () -> session.decode(frame));
        assertTrue(failure.getCause() instanceof DataFormatException);
        assertSame(inflater.resetFailure, failure.getSuppressed()[0]);
        assertThrows(IllegalStateException.class, () -> session.decode(frame));
        session.close();
        assertTrue(inflater.ended);
        assertTrue(deflater.ended);
    }

    private static void inject(BoundedTraceCompression.Session session, String name, Object value) throws Exception {
        Field field = BoundedTraceCompression.Session.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(session, value);
    }

    private static final class SyntheticFatal extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }

    private static final class FaultyDeflater extends Deflater {
        private final SyntheticFatal fatal;
        private final RuntimeException resetFailure;
        private boolean ended;
        FaultyDeflater(SyntheticFatal fatal, RuntimeException resetFailure) {
            this.fatal = fatal; this.resetFailure = resetFailure;
        }
        @Override public int deflate(byte[] output, int offset, int length) { throw fatal; }
        @Override public void reset() { throw resetFailure; }
        @Override public void end() { ended = true; super.end(); }
    }

    private static final class TrackingDeflater extends Deflater {
        private boolean ended;
        @Override public void end() { ended = true; super.end(); }
    }

    private static final class FaultyInflater extends Inflater {
        private final IllegalStateException resetFailure = new IllegalStateException("synthetic reset failure");
        private boolean ended;
        @Override public int inflate(byte[] output, int offset, int length) throws DataFormatException {
            throw new DataFormatException("synthetic corrupt input");
        }
        @Override public void reset() { throw resetFailure; }
        @Override public void end() { ended = true; super.end(); }
    }
}
