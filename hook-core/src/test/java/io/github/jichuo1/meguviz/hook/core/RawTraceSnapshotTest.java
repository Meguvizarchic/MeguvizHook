package io.github.jichuo1.meguviz.hook.core;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public final class RawTraceSnapshotTest {
    private static final int MAX = BoundedTraceCompression.MAX_PLAIN_BYTES;

    private static BoundedTraceCompression codec() {
        return new BoundedTraceCompression(MAX, BoundedTraceCompression.MAX_FRAME_BYTES,
                MAX + 1, 0, BoundedTraceCompression.Algorithm.ZLIB);
    }

    private static byte[] rawFrame(byte[] input) {
        CRC32 crc = new CRC32();
        crc.update(input);
        return ByteBuffer.allocate(BoundedTraceCompression.HEADER_BYTES + input.length)
                .putInt(0x4d485443).put((byte) 1).put((byte) 0).putShort((short) 0)
                .putInt(input.length).putInt(input.length).putInt((int) crc.getValue())
                .put(input).array();
    }

    @Test public void rawFramesMatchWireFormatAndOwnTheirPayload() throws Exception {
        BoundedTraceCompression codec = codec();
        Random random = new Random(31);
        try (BoundedTraceCompression.Session session = codec.newSession()) {
            for (int size : new int[] {0, 1, 127, 4095, 4096, MAX}) {
                byte[] input = new byte[size];
                random.nextBytes(input);
                byte[] expected = rawFrame(input);
                BoundedTraceCompression.Encoding ordinary = codec.encode(input);
                BoundedTraceCompression.Encoding reused = session.encode(input);
                assertEquals(BoundedTraceCompression.Decision.BELOW_THRESHOLD, ordinary.reason());
                assertEquals(ordinary.reason(), reused.reason());
                assertArrayEquals(expected, ordinary.bytes());
                assertArrayEquals(expected, reused.bytes());
                Arrays.fill(input, (byte) 0);
                assertArrayEquals(expected, ordinary.bytes());
                assertArrayEquals(expected, reused.bytes());
            }
        }
    }

    @Test public void concurrentInputChangesCannotSeparatePayloadFromChecksum() throws Exception {
        BoundedTraceCompression codec = codec();
        byte[] input = new byte[4096];
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch started = new CountDownLatch(1);
        Thread mutator = new Thread(() -> {
            started.countDown();
            byte value = 0;
            while (running.get()) Arrays.fill(input, value++);
        });
        mutator.start();
        try {
            started.await();
            for (int i = 0; i < 200; i++) {
                byte[] frame = codec.encode(input).bytes();
                // A concurrent copy need not be atomic, but its exact payload must validate.
                byte[] decoded = codec.decode(frame);
                assertArrayEquals(Arrays.copyOfRange(frame, BoundedTraceCompression.HEADER_BYTES, frame.length), decoded);
            }
        } finally {
            running.set(false);
            mutator.join();
        }
    }
}
