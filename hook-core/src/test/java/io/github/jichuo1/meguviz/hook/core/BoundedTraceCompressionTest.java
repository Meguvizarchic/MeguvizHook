package io.github.jichuo1.meguviz.hook.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.junit.Test;

import static org.junit.Assert.*;

public final class BoundedTraceCompressionTest {
    private static BoundedTraceCompression codec(BoundedTraceCompression.Algorithm algorithm) {
        return new BoundedTraceCompression(32768, 32788, 128, 32, algorithm);
    }

    private static void rejects(BoundedTraceCompression codec, byte[] frame) throws Exception {
        try { codec.decode(frame); fail("malformed frame accepted"); }
        catch (IOException expected) { }
    }

    @Test public void decisionsAndRoundTripsAreBounded() throws Exception {
        byte[] shortValue = "{}\n".getBytes(StandardCharsets.UTF_8);
        byte[] random = new byte[16384];
        new Random(1234).nextBytes(random);
        byte[] repeated = new byte[16384];
        Arrays.fill(repeated, (byte) 'a');
        // Probe-like random regions surrounding compressible data must not suppress full attempts.
        byte[] deceptive = new byte[32768];
        Random rng = new Random(88);
        for (int part = 0; part < 4; part++) {
            byte[] sample = new byte[1024]; rng.nextBytes(sample);
            System.arraycopy(sample, 0, deceptive, (deceptive.length - 1024) * part / 3, 1024);
        }
        for (BoundedTraceCompression.Algorithm algorithm : BoundedTraceCompression.Algorithm.values()) {
            BoundedTraceCompression codec = codec(algorithm);
            BoundedTraceCompression.Encoding shortFrame = codec.encode(shortValue);
            assertEquals(BoundedTraceCompression.Decision.BELOW_THRESHOLD, shortFrame.reason());
            assertEquals(-BoundedTraceCompression.HEADER_BYTES, shortFrame.netSavingBytes());
            assertArrayEquals(shortValue, codec.decode(shortFrame.bytes()));
            BoundedTraceCompression.Encoding randomFrame = codec.encode(random);
            assertEquals(BoundedTraceCompression.Decision.INSUFFICIENT_SAVINGS, randomFrame.reason());
            assertArrayEquals(random, codec.decode(randomFrame.bytes()));
            for (byte[] input : new byte[][] {repeated, deceptive}) {
                BoundedTraceCompression.Encoding frame = codec.encode(input);
                assertTrue(frame.compressed());
                assertTrue(frame.frameBytes() <= input.length - 32);
                assertEquals(input.length - frame.frameBytes(), frame.netSavingBytes());
                assertEquals(input.length, frame.plainBytes());
                assertArrayEquals(input, codec.decode(frame.bytes()));
                byte[] leaked = frame.bytes(); leaked[0] ^= 1;
                assertArrayEquals(input, codec.decode(frame.bytes()));
            }
            assertArrayEquals(new byte[0], codec.decode(codec.encode(new byte[0]).bytes()));
        }
    }

    @Test public void checksBothBoundsAndTriesCompressionWhenRawDoesNotFit() throws Exception {
        BoundedTraceCompression codec = new BoundedTraceCompression(4096, 128, 8192, 8,
                BoundedTraceCompression.Algorithm.ZLIB);
        byte[] input = new byte[4096];
        BoundedTraceCompression.Encoding encoded = codec.encode(input);
        assertTrue(encoded.compressed());
        assertEquals(BoundedTraceCompression.Decision.COMPRESSED_TO_FIT, encoded.reason());
        assertTrue(encoded.frameBytes() <= 128);
        assertArrayEquals(input, codec.decode(encoded.bytes()));
        byte[] random = new byte[4096]; new Random(99).nextBytes(random);
        try { codec.encode(random); fail("oversized raw frame accepted"); }
        catch (IOException expected) { }
        try { codec.encode(new byte[4097]); fail("oversized plain input accepted"); }
        catch (IOException expected) { }
        BoundedTraceCompression lowerPlainLimit = new BoundedTraceCompression(100, 128, 0, 0,
                BoundedTraceCompression.Algorithm.ZLIB);
        rejects(lowerPlainLimit, encoded.bytes());
        BoundedTraceCompression lowerWireLimit = new BoundedTraceCompression(4096, 20, 0, 0,
                BoundedTraceCompression.Algorithm.ZLIB);
        rejects(lowerWireLimit, encoded.bytes());
    }

    @Test public void countsHeaderInNetSavingThreshold() throws Exception {
        byte[] input = new byte[1024];
        BoundedTraceCompression base = new BoundedTraceCompression(1024, 1044, 0, 1,
                BoundedTraceCompression.Algorithm.ZLIB);
        BoundedTraceCompression.Encoding compressed = base.encode(input);
        int actualSaving = compressed.netSavingBytes();
        assertTrue(actualSaving > 1);
        BoundedTraceCompression exact = new BoundedTraceCompression(1024, 1044, 0, actualSaving,
                BoundedTraceCompression.Algorithm.ZLIB);
        assertTrue(exact.encode(input).compressed());
        BoundedTraceCompression justOver = new BoundedTraceCompression(1024, 1044, 0, actualSaving + 1,
                BoundedTraceCompression.Algorithm.ZLIB);
        BoundedTraceCompression.Encoding raw = justOver.encode(input);
        assertEquals(BoundedTraceCompression.Decision.INSUFFICIENT_SAVINGS, raw.reason());
        assertEquals(-BoundedTraceCompression.HEADER_BYTES, raw.netSavingBytes());
        assertArrayEquals(input, justOver.decode(raw.bytes()));
    }

    @Test public void fitOnlyCompressionCanHaveNegativeNetSavings() throws Exception {
        byte[] seed = new byte[128]; new Random(731).nextBytes(seed);
        byte[] input = null;
        int candidateLength = 0;
        for (int repeat = 4; repeat <= 64; repeat++) {
            byte[] candidate = seed.clone();
            Arrays.fill(candidate, candidate.length - repeat, candidate.length, (byte) 0);
            Deflater compressor = new Deflater(Deflater.BEST_SPEED);
            try {
                compressor.setInput(candidate); compressor.finish();
                byte[] compressed = new byte[256];
                int length = compressor.deflate(compressed);
                assertTrue(compressor.finished());
                if (length < candidate.length && length + BoundedTraceCompression.HEADER_BYTES >= candidate.length) {
                    input = candidate; candidateLength = length; break;
                }
            } finally { compressor.end(); }
        }
        assertNotNull("expected a minimally compressible fixture", input);
        BoundedTraceCompression codec = new BoundedTraceCompression(128,
                candidateLength + BoundedTraceCompression.HEADER_BYTES, 1000, 1000,
                BoundedTraceCompression.Algorithm.ZLIB);
        BoundedTraceCompression.Encoding frame = codec.encode(input);
        assertEquals(BoundedTraceCompression.Decision.COMPRESSED_TO_FIT, frame.reason());
        assertTrue(frame.netSavingBytes() <= 0);
        assertArrayEquals(input, codec.decode(frame.bytes()));
    }

    @Test public void rejectsCorruptTruncatedAndTrailingFrames() throws Exception {
        for (BoundedTraceCompression.Algorithm algorithm : BoundedTraceCompression.Algorithm.values()) {
            BoundedTraceCompression codec = codec(algorithm);
            byte[] frame = codec.encode(new byte[1024]).bytes();
            byte[] corrupt = frame.clone(); corrupt[19] ^= 1; rejects(codec, corrupt);
            byte[] version = frame.clone(); version[4] = 2; rejects(codec, version);
            byte[] method = frame.clone(); method[5] = 99; rejects(codec, method);
            byte[] flags = frame.clone(); flags[7] = 1; rejects(codec, flags);
            rejects(codec, Arrays.copyOf(frame, frame.length - 1));
            rejects(codec, Arrays.copyOf(frame, frame.length + 1));
            byte[] nestedTail = Arrays.copyOf(frame, frame.length + 1);
            ByteBuffer.wrap(nestedTail).putInt(12, frame.length + 1 - BoundedTraceCompression.HEADER_BYTES);
            rejects(codec, nestedTail);
            byte[] shortPlain = frame.clone(); ByteBuffer.wrap(shortPlain).putInt(8, 1023);
            rejects(codec, shortPlain);
            byte[] hugePlain = frame.clone(); ByteBuffer.wrap(hugePlain).putInt(8, Integer.MAX_VALUE);
            rejects(codec, hugePlain);
            byte[] negativeLength = frame.clone(); ByteBuffer.wrap(negativeLength).putInt(12, -1);
            rejects(codec, negativeLength);
            rejects(codec, "{\"version\":1}\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test public void explicitPairedWritersForwardOnlyVerifiedPlaintext() throws Exception {
        BoundedTraceCompression codec = codec(BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
        AtomicReference<byte[]> received = new AtomicReference<byte[]>();
        AtomicInteger calls = new AtomicInteger();
        JsonLineTraceTransport.RecordWriter plain = data -> { received.set(data); calls.incrementAndGet(); };
        JsonLineTraceTransport.RecordWriter decoder = codec.decodingWriter(plain);
        JsonLineTraceTransport.RecordWriter encoder = codec.encodingWriter(decoder);
        byte[] input = new byte[1024];
        encoder.writeRecord(input);
        assertArrayEquals(input, received.get());
        try { decoder.writeRecord(new byte[20]); fail("invalid frame accepted"); }
        catch (IOException expected) { }
        assertEquals(1, calls.get());
        IOException failure = new IOException("unavailable");
        JsonLineTraceTransport.RecordWriter failing = codec.encodingWriter(bytes -> { throw failure; });
        try { failing.writeRecord(input); fail("writer failure swallowed"); }
        catch (IOException expected) { assertSame(failure, expected); }
    }

    @Test public void rejectsAnotherDictionaryAndMethodMisrepresentation() throws Exception {
        byte[] input = new byte[1024];
        byte[] otherDictionary = "another-dictionary".getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        byte[] compressed = new byte[2048];
        int length;
        try {
            deflater.setDictionary(otherDictionary);
            deflater.setInput(input);
            deflater.finish();
            length = deflater.deflate(compressed);
            assertTrue(deflater.finished());
        } finally {
            deflater.end();
        }
        Inflater control = new Inflater();
        try {
            control.setInput(compressed, 0, length);
            byte[] decoded = new byte[input.length];
            assertEquals(0, control.inflate(decoded));
            assertTrue(control.needsDictionary());
            control.setDictionary(otherDictionary);
            assertEquals(input.length, control.inflate(decoded));
            assertTrue(control.finished());
            assertArrayEquals(input, decoded);
        } finally { control.end(); }
        CRC32 crc = new CRC32(); crc.update(input);
        byte[] frame = ByteBuffer.allocate(BoundedTraceCompression.HEADER_BYTES + length)
                .putInt(0x4d485443).put((byte) 1).put((byte) 2).putShort((short) 0)
                .putInt(input.length).putInt(length).putInt((int) crc.getValue())
                .put(compressed, 0, length).array();
        BoundedTraceCompression codec = codec(BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
        rejects(codec, frame);
        // Mark a valid ordinary zlib stream as a schema stream: no dictionary request is invalid.
        byte[] mislabelled = codec(BoundedTraceCompression.Algorithm.ZLIB).encode(input).bytes();
        mislabelled[5] = 2;
        rejects(codec, mislabelled);
    }
}
