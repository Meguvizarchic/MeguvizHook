package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import java.util.Objects;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Small, platform-independent bounds for an explicitly configured trace channel. */
final class TraceRecordPolicy102 {
    static final int MAX_RECORD_BYTES = 64 * 1024;
    static final int MAX_NAME_CHARS = 128;
    // Accounts for the fixed interface token, version, fixed fingerprint and length words.
    static final int PARCEL_OVERHEAD_BYTES = 1024;
    final String name;
    final int maxBytes;
    private final byte[] nameFingerprint;

    TraceRecordPolicy102(String name, int maxBytes) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty() || name.length() > MAX_NAME_CHARS || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("trace name must be a bounded flat filename");
        }
        if (maxBytes < 1 || maxBytes > MAX_RECORD_BYTES) {
            throw new IllegalArgumentException("trace record bound must be 1..65536 bytes");
        }
        this.name = name;
        this.maxBytes = maxBytes;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Encode exact UTF-16 code units in big-endian order, including unpaired surrogates.
            // This fingerprint only checks channel agreement and is not an authentication token.
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                digest.update((byte) (c >>> 8));
                digest.update((byte) c);
            }
            this.nameFingerprint = digest.digest();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    void checkParcelSize(int bytes) {
        if (bytes < 0 || bytes > maxBytes + PARCEL_OVERHEAD_BYTES) {
            throw new IllegalArgumentException("trace parcel exceeds configured bound");
        }
    }

    void checkLength(int bytes) {
        if (bytes < 1 || bytes > maxBytes) throw new IllegalArgumentException("invalid trace record length");
    }

    void checkName(String incoming) {
        if (!name.equals(incoming)) throw new IllegalArgumentException("unexpected trace channel");
    }

    byte[] fingerprint() { return nameFingerprint.clone(); }

    void checkFingerprint(byte[] incoming) {
        if (incoming == null || !MessageDigest.isEqual(nameFingerprint, incoming)) {
            throw new IllegalArgumentException("unexpected trace channel fingerprint");
        }
    }

    /** Validate an inner Parcel byte-array length and its exact padded remainder before allocation. */
    void checkEncodedArray(int declared, int encoded, int remainingBytes) {
        checkLength(declared);
        int padded = (declared + 3) & ~3;
        if (encoded != declared || remainingBytes != padded) {
            throw new IllegalArgumentException("invalid trace array length or padded remainder");
        }
    }
}
