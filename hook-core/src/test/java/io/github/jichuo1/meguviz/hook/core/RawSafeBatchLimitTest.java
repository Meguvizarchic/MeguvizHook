package io.github.jichuo1.meguviz.hook.core;

import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.*;

public final class RawSafeBatchLimitTest {
    private static BoundedTraceCompression codec(int plainLimit, int frameLimit) {
        return new BoundedTraceCompression(plainLimit, frameLimit, 0, 0,
                BoundedTraceCompression.Algorithm.ZLIB);
    }

    @Test public void choosesSmallestApplicableLimit() {
        assertEquals(65536, codec(70000, 70020).rawSafeBatchLimit(Integer.MAX_VALUE));
        assertEquals(1234, codec(1234, 5000).rawSafeBatchLimit(5000));
        assertEquals(1980, codec(5000, 2000).rawSafeBatchLimit(5000));
        assertEquals(2980, codec(5000, 5000).rawSafeBatchLimit(3000));
        assertEquals(13, codec(13, 33).rawSafeBatchLimit(33));
    }

    @Test public void rejectsBudgetsTooSmallForOneBatchRecord() {
        assertThrows(IllegalArgumentException.class, () -> codec(12, 100).rawSafeBatchLimit(100));
        assertThrows(IllegalArgumentException.class, () -> codec(100, 32).rawSafeBatchLimit(100));
        assertThrows(IllegalArgumentException.class, () -> codec(100, 100).rawSafeBatchLimit(32));
        assertThrows(IllegalArgumentException.class, () -> codec(100, 100).rawSafeBatchLimit(0));
        assertThrows(IllegalArgumentException.class, () -> codec(100, 100).rawSafeBatchLimit(-1));
        assertThrows(IllegalArgumentException.class, () -> codec(100, 100).rawSafeBatchLimit(Integer.MIN_VALUE));
    }

    @Test public void incompressibleBytesAtTheReturnedBoundFitTheWireAndRoundTrip() throws Exception {
        for (BoundedTraceCompression.Algorithm algorithm : BoundedTraceCompression.Algorithm.values()) {
            BoundedTraceCompression codec = new BoundedTraceCompression(4096, 3100, 0, 0, algorithm);
            int wireLimit = 2500;
            byte[] plain = new byte[codec.rawSafeBatchLimit(wireLimit)];
            new Random(1234).nextBytes(plain);
            BoundedTraceCompression.Encoding encoded = codec.encode(plain);
            assertFalse(encoded.compressed());
            assertEquals(wireLimit, encoded.frameBytes());
            assertArrayEquals(plain, codec.decode(encoded.bytes()));
            BoundedTraceCompression.Encoding beyond = codec.encode(Arrays.copyOf(plain, plain.length + 1));
            assertFalse(beyond.compressed());
            assertTrue(beyond.frameBytes() > wireLimit);
        }
    }
}
