package io.github.jichuo1.meguviz.hook.core;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The RAW decode path now reads the header once and copies the payload once instead of cloning
 * the whole frame first. This compares every outcome with a verbatim copy of the round-0 decoder.
 */
public final class CompressionDecodeDifferentialTest {
    private static final int HEADER_BYTES = 20;
    private static final byte[] DICTIONARY = ("\"version\":1,\"source\":\"target-process\",\"hook\":\""
            + "\",\"generation\":,\"timeNanos\":,\"stage\":\"BEFORE\",\"stage\":\"AFTER\","
            + "\"stage\":\"AROUND\",\"stage\":\"OBSERVED_THROWABLE\",\"type\":\"java.lang."
            + "RuntimeException\",\"message\":\"\",\"frames\":[{\"class\":\"com.example."
            + "\",\"method\":\"invoke\",\"file\":\"Example.java\",\"line\":}],"
            + "\"framesCollected\":true,\"framesTruncated\":false}\n").getBytes(StandardCharsets.UTF_8);

    /** Round-0 decodeInternal for the stateless codec (verbatim apart from parameters). */
    private static byte[] legacyDecode(byte[] frame, int maxPlainBytes, int maxFrameBytes) throws IOException {
        if (frame == null || frame.length < HEADER_BYTES || frame.length > maxFrameBytes) {
            throw new IOException("invalid trace frame size");
        }
        frame = frame.clone();
        ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        if (header.getInt() != 0x4d485443 || (header.get() & 255) != 1) throw new IOException("trace frame version");
        int method = header.get() & 255;
        if (header.getShort() != 0 || (method != 0 && method != 1 && method != 2)) {
            throw new IOException("unsupported trace frame method");
        }
        int plainLength = header.getInt();
        int payloadLength = header.getInt();
        int expectedCrc = header.getInt();
        if (plainLength < 0 || plainLength > maxPlainBytes || payloadLength < 0
                || payloadLength != frame.length - HEADER_BYTES) throw new IOException("invalid trace lengths");
        if (method == 0 && plainLength != payloadLength) throw new IOException("raw trace length mismatch");
        if (method != 0 && (plainLength == 0 || payloadLength == 0)) throw new IOException("empty compressed trace");
        byte[] plain = new byte[plainLength];
        if (method == 0) {
            System.arraycopy(frame, HEADER_BYTES, plain, 0, plainLength);
        } else {
            Inflater inflater = new Inflater();
            try { legacyInflate(frame, method, plain, inflater); }
            finally { inflater.end(); }
        }
        CRC32 crc = new CRC32();
        crc.update(plain);
        if ((int) crc.getValue() != expectedCrc) throw new IOException("trace checksum mismatch");
        return plain;
    }

    private static void legacyInflate(byte[] frame, int method, byte[] plain, Inflater inflater) throws IOException {
        boolean dictionarySet = false;
        try {
            inflater.setInput(frame, HEADER_BYTES, frame.length - HEADER_BYTES);
            int used = 0;
            while (used < plain.length) {
                int count = inflater.inflate(plain, used, plain.length - used);
                if (count > 0) { used += count; continue; }
                if (inflater.needsDictionary() && method == 2 && !dictionarySet) {
                    try { inflater.setDictionary(DICTIONARY); }
                    catch (IllegalArgumentException wrong) { throw new IOException("trace dictionary mismatch", wrong); }
                    dictionarySet = true;
                    continue;
                }
                throw new IOException("short or invalid compressed trace");
            }
            byte[] extra = new byte[1];
            if (!inflater.finished() && inflater.inflate(extra) != 0) throw new IOException("trace expansion overflow");
            if (!inflater.finished() || inflater.getRemaining() != 0 || (method == 2 && !dictionarySet)) {
                throw new IOException("incomplete or trailing compressed trace");
            }
        } catch (DataFormatException invalid) {
            throw new IOException("invalid compressed trace", invalid);
        }
    }

    private static String outcome(Exception failure) {
        return failure.getClass().getName() + ": " + failure.getMessage();
    }

    @Test public void decodeMatchesLegacyOnRawCompressedAndCorruptFrames() throws Exception {
        Random random = new Random(0xdec0deL);
        int raw = 0;
        int compressed = 0;
        for (int i = 0; i < 4000; i++) {
            int maxPlain = random.nextBoolean() ? 4096 : 65536;
            BoundedTraceCompression.Algorithm algorithm = random.nextBoolean()
                    ? BoundedTraceCompression.Algorithm.ZLIB : BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1;
            int threshold = random.nextBoolean() ? 0 : maxPlain + 1;
            BoundedTraceCompression codec = new BoundedTraceCompression(maxPlain, maxPlain + HEADER_BYTES,
                    threshold, 0, algorithm);
            byte[] input = new byte[random.nextInt(random.nextBoolean() ? 64 : maxPlain + 1)];
            if (random.nextBoolean()) {
                random.nextBytes(input);
            } else {
                for (int b = 0; b < input.length; b++) input[b] = (byte) ("{\"frames\":[]}".charAt(b % 13));
            }
            byte[] frame = codec.encode(input).bytes();
            if (frame[5] == 0) raw++; else compressed++;
            byte[] mutated = mutate(random, frame);
            byte[] snapshot = mutated.clone();
            String expected;
            byte[] expectedPlain = null;
            try {
                expectedPlain = legacyDecode(mutated, maxPlain, maxPlain + HEADER_BYTES);
                expected = "ok";
            } catch (IOException failure) {
                expected = outcome(failure);
            }
            String actual;
            byte[] actualPlain = null;
            try (BoundedTraceCompression.Session session = codec.newSession()) {
                try {
                    actualPlain = codec.decode(mutated);
                    actual = "ok";
                } catch (IOException failure) {
                    actual = outcome(failure);
                }
                String sessionOutcome;
                try {
                    byte[] sessionPlain = session.decode(mutated);
                    sessionOutcome = "ok";
                    assertArrayEquals(actualPlain, sessionPlain);
                } catch (IOException failure) {
                    sessionOutcome = outcome(failure);
                }
                assertEquals("session case " + i, expected, sessionOutcome);
            }
            assertEquals("case " + i, expected, actual);
            assertArrayEquals("decode must not modify its input", snapshot, mutated);
            if (expectedPlain != null) {
                assertArrayEquals(expectedPlain, actualPlain);
                if (mutated.length > HEADER_BYTES) {
                    mutated[HEADER_BYTES] ^= 1;
                    assertArrayEquals("output must not alias the input", expectedPlain, actualPlain);
                }
            }
        }
        assertTrue(raw > 500);
        assertTrue(compressed > 500);
    }

    @Test public void rawDecodeOutputAlwaysMatchesTheChecksumUnderConcurrentMutation() throws Exception {
        BoundedTraceCompression codec = new BoundedTraceCompression(4096, 4116, 4097, 0,
                BoundedTraceCompression.Algorithm.ZLIB);
        byte[] input = new byte[2048];
        new Random(5).nextBytes(input);
        byte[] frame = codec.encode(input).bytes();
        byte[] original = frame.clone();
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread mutator = new Thread(() -> {
            Random noise = new Random(9);
            while (running.get()) {
                int at = HEADER_BYTES + noise.nextInt(frame.length - HEADER_BYTES);
                frame[at] = noise.nextBoolean() ? original[at] : (byte) noise.nextInt(256);
            }
        }, "raw-frame-mutator");
        mutator.start();
        try {
            for (int i = 0; i < 5000; i++) {
                byte[] plain;
                try {
                    plain = codec.decode(frame);
                } catch (IOException mismatch) {
                    assertEquals("trace checksum mismatch", mismatch.getMessage());
                    continue;
                }
                // The header (and its checksum) is never mutated here, so any returned payload
                // must be exactly the original one.
                assertArrayEquals(input, plain);
            }
        } finally {
            running.set(false);
            mutator.join();
        }
    }

    /** Every RAW frame below the threshold now takes the single-copy path, whatever its size. */
    @Test public void belowThresholdRawEncodingKeepsTheWireFormatForEverySize() throws Exception {
        Random random = new Random(0x5a1L);
        BoundedTraceCompression codec = new BoundedTraceCompression(8192, 8192 + HEADER_BYTES, 8193, 0,
                BoundedTraceCompression.Algorithm.TRACE_SCHEMA_V1);
        try (BoundedTraceCompression.Session session = codec.newSession();
             BoundedTraceCompression.SerializedSession serialized = codec.newSerializedSession()) {
            for (int i = 0; i < 2000; i++) {
                byte[] input = new byte[random.nextInt(random.nextBoolean() ? 300 : 8193)];
                random.nextBytes(input);
                CRC32 crc = new CRC32();
                crc.update(input);
                byte[] expected = ByteBuffer.allocate(HEADER_BYTES + input.length)
                        .putInt(0x4d485443).put((byte) 1).put((byte) 0).putShort((short) 0)
                        .putInt(input.length).putInt(input.length).putInt((int) crc.getValue())
                        .put(input).array();
                for (BoundedTraceCompression.Encoding encoding : new BoundedTraceCompression.Encoding[] {
                        codec.encode(input), session.encode(input), serialized.encode(input)}) {
                    assertEquals(BoundedTraceCompression.Decision.BELOW_THRESHOLD, encoding.reason());
                    assertArrayEquals(expected, encoding.bytes());
                }
            }
        }
    }

    @Test public void smallRawEncodingStaysSelfConsistentUnderConcurrentMutation() throws Exception {
        BoundedTraceCompression codec = new BoundedTraceCompression(4096, 4116, 4097, 0,
                BoundedTraceCompression.Algorithm.ZLIB);
        byte[] input = new byte[200];
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread mutator = new Thread(() -> {
            byte value = 0;
            while (running.get()) Arrays.fill(input, value++);
        }, "small-raw-mutator");
        mutator.start();
        try {
            for (int i = 0; i < 5000; i++) {
                byte[] frame = codec.encode(input).bytes();
                assertArrayEquals(Arrays.copyOfRange(frame, HEADER_BYTES, frame.length), codec.decode(frame));
            }
        } finally {
            running.set(false);
            mutator.join();
        }
    }

    private static byte[] mutate(Random random, byte[] frame) {
        byte[] copy = frame.clone();
        switch (random.nextInt(8)) {
            case 0: case 1: case 2: return copy;
            case 3: copy[random.nextInt(HEADER_BYTES)] ^= (byte) (1 << random.nextInt(8)); return copy;
            case 4:
                if (copy.length > HEADER_BYTES) copy[HEADER_BYTES + random.nextInt(copy.length - HEADER_BYTES)] ^= 1;
                return copy;
            case 5: return Arrays.copyOf(copy, random.nextInt(copy.length + 1));
            case 6: return Arrays.copyOf(copy, copy.length + 1 + random.nextInt(4));
            default: {
                byte[] garbage = new byte[random.nextInt(64)];
                random.nextBytes(garbage);
                return garbage;
            }
        }
    }
}
