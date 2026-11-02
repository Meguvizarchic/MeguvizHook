package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import org.junit.Test;
import static org.junit.Assert.*;

/** Pure bounds tests only; no Android Binder, identity or cross-process behavior is simulated. */
public final class TraceRecordPolicy102Test {
    @Test public void validatesNamesWithoutAllowingPathOrControlNull() {
        TraceRecordPolicy102 policy = new TraceRecordPolicy102("trace.jsonl", 1024);
        policy.checkName("trace.jsonl");
        assertThrows(IllegalArgumentException.class, () -> policy.checkName("other.jsonl"));
        assertThrows(IllegalArgumentException.class, () -> policy.checkName(null));
        for (String value : new String[]{"", ".", "..", "a/b", "a\\b", "a\0b", new String(new char[129]).replace('\0', 'a')}) {
            assertThrows(IllegalArgumentException.class, () -> new TraceRecordPolicy102(value, 1));
        }
    }

    @Test public void recordAndParcelLimitsRejectBeforeAllocation() {
        assertThrows(IllegalArgumentException.class, () -> new TraceRecordPolicy102("x", 0));
        assertThrows(IllegalArgumentException.class, () -> new TraceRecordPolicy102("x", 65537));
        TraceRecordPolicy102 policy = new TraceRecordPolicy102("x", 65536);
        policy.checkLength(1);
        policy.checkLength(65536);
        policy.checkParcelSize(65536 + TraceRecordPolicy102.PARCEL_OVERHEAD_BYTES);
        assertThrows(IllegalArgumentException.class, () -> policy.checkLength(0));
        assertThrows(IllegalArgumentException.class, () -> policy.checkLength(-1));
        assertThrows(IllegalArgumentException.class, () -> policy.checkLength(65537));
        assertThrows(IllegalArgumentException.class, () -> policy.checkParcelSize(-1));
        assertThrows(IllegalArgumentException.class, () -> policy.checkParcelSize(65536 + TraceRecordPolicy102.PARCEL_OVERHEAD_BYTES + 1));
    }

    @Test public void encodedLengthAndFourBytePaddingAreCheckedBeforeAllocation() {
        TraceRecordPolicy102 policy = new TraceRecordPolicy102("x", 65536);
        for (int length : new int[]{1, 2, 3, 4, 5, 65535, 65536}) {
            int padded = (length + 3) & ~3;
            policy.checkEncodedArray(length, length, padded);
            assertThrows(IllegalArgumentException.class, () -> policy.checkEncodedArray(length, length + 1, padded));
            assertThrows(IllegalArgumentException.class, () -> policy.checkEncodedArray(length, length, padded + 4));
            assertThrows(IllegalArgumentException.class, () -> policy.checkEncodedArray(length, length, padded - 1));
        }
        assertThrows(IllegalArgumentException.class, () -> policy.checkEncodedArray(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> policy.checkEncodedArray(65537, 65537, 65540));
        assertThrows(IllegalArgumentException.class, () -> policy.checkEncodedArray(Integer.MAX_VALUE, Integer.MAX_VALUE, 0));
    }

    @Test public void fingerprintPreservesExactCodeUnitsAndDoesNotExposeMutableState() {
        TraceRecordPolicy102 policy = new TraceRecordPolicy102("trace\ud800", 10);
        byte[] first = policy.fingerprint();
        assertEquals(32, first.length);
        policy.checkFingerprint(first);
        first[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> policy.checkFingerprint(first));
        policy.checkFingerprint(policy.fingerprint());
        assertThrows(IllegalArgumentException.class,
                () -> policy.checkFingerprint(new TraceRecordPolicy102("trace?", 10).fingerprint()));
        assertThrows(IllegalArgumentException.class, () -> policy.checkFingerprint(null));
        assertThrows(IllegalArgumentException.class, () -> policy.checkFingerprint(new byte[31]));
    }
}
