package io.github.jichuo1.meguviz.hook.core;

import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * The single-record emitter writes a non-ASCII tail in chunks that fit its capacity and grows to
 * a corrected record estimate, instead of the bounded emitter's three-bytes-per-unit budget with
 * top-ups. Both must produce identical bytes for every string, at every capacity.
 */
public final class JsonSingleRecordTailTest {
    private static String randomText(Random random, int length) {
        StringBuilder text = new StringBuilder(length);
        int profile = random.nextInt(8);
        while (text.length() < length) {
            int kind = profile == 0 ? 0 : profile == 7 ? 3 + random.nextInt(2) * 5 : random.nextInt(profile == 1 ? 3 : 10);
            switch (kind) {
                case 0: case 1: text.append((char) (0x20 + random.nextInt(0x60))); break; // includes 0x7f
                case 2: text.append(random.nextBoolean() ? '"' : '\\'); break;
                case 3: text.append((char) random.nextInt(0x20)); break;
                case 4: text.append((char) (0x80 + random.nextInt(0x780))); break;
                case 5: {
                    char c = (char) (0x800 + random.nextInt(0xf800));
                    text.append(Character.isSurrogate(c) ? '\u4e2d' : c);
                    break;
                }
                case 6: case 7: text.appendCodePoint(0x10000 + random.nextInt(0xfffff)); break;
                case 8: text.append((char) (0xd800 + random.nextInt(0x400))); break; // lone high
                default: text.append((char) (0xdc00 + random.nextInt(0x400))); break; // lone low
            }
        }
        return text.length() > length ? text.substring(0, length) : text.toString();
    }

    private static byte[] emit(JsonLineTraceTransport.Utf8 text, String value, int limit, int estimate) {
        text.reserveRecord(estimate);
        text.string(value, limit);
        return text.finish();
    }

    @Test public void singleRecordAndBoundedEmittersWriteIdenticalBytes() {
        Random random = new Random(0x7a11_51e5L);
        int[] lengths = {0, 1, 2, 3, 5, 17, 31, 32, 33, 127, 128, 129, 400, 511, 512, 513, 700};
        for (int round = 0; round < 60000; round++) {
            String value = randomText(random, lengths[random.nextInt(lengths.length)]);
            // Cut anywhere, including between the units of a surrogate pair.
            int limit = value.isEmpty() ? 0 : random.nextInt(value.length() + 1);
            byte[] budgeted = emit(JsonLineTraceTransport.Utf8.bounded(16, 65536), value, limit, limit + 16);
            // Tight, roomy and random ASCII estimates exercise the growth, chunk and headroom paths.
            int[] estimates = {limit, limit + 16, limit * 6 + 16, random.nextInt(limit * 7 + 40)};
            for (int estimate : estimates) {
                byte[] single = emit(new JsonLineTraceTransport.Utf8(), value, limit, estimate);
                assertArrayEquals("round " + round + " estimate " + estimate, budgeted, single);
            }
        }
    }

    @Test public void escapeHeavyAndPairHeavyTails() {
        StringBuilder controls = new StringBuilder();
        StringBuilder pairs = new StringBuilder();
        StringBuilder lone = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            controls.append((char) (i % 0x20));
            pairs.appendCodePoint(0x1f600 + i % 64);
            lone.append(i % 2 == 0 ? '\udc00' : '\ud800');
        }
        for (String value : new String[] {controls.toString(), pairs.toString(), lone.toString(),
                "\u4e2d" + controls, "a\ud83d", "\ud83d\ude00\ud83d"}) {
            for (int limit = 0; limit <= Math.min(value.length(), 520); limit += limit < 40 ? 1 : 37) {
                byte[] budgeted = emit(JsonLineTraceTransport.Utf8.bounded(16, 65536), value, limit, 0);
                for (int estimate : new int[] {0, 1, limit, 3 * limit, 6 * limit + 12}) {
                    assertArrayEquals(budgeted, emit(new JsonLineTraceTransport.Utf8(), value, limit, estimate));
                }
            }
        }
    }

    @Test public void manyNonAsciiFieldsKeepIdenticalRecordBytes() {
        // Every field of a record grows past the estimate: bytes must not depend on growth policy.
        Random random = new Random(42);
        for (int round = 0; round < 2000; round++) {
            StringBuilder longText = new StringBuilder();
            while (longText.length() < 3000) longText.append(randomText(random, 64));
            String value = longText.toString();
            JsonLineTraceTransport.Utf8 single = new JsonLineTraceTransport.Utf8();
            JsonLineTraceTransport.Utf8 frame = JsonLineTraceTransport.Utf8.bounded(16, 65536);
            single.reserveRecord(40);
            frame.reserveRecord(40);
            for (int part = 0; part + 128 <= value.length(); part += 128) {
                String piece = value.substring(part, part + 128);
                single.string(piece, 128);
                frame.string(piece, 128);
                single.ascii(',');
                frame.ascii(',');
            }
            assertArrayEquals(frame.finish(), single.finish());
        }
    }
}
