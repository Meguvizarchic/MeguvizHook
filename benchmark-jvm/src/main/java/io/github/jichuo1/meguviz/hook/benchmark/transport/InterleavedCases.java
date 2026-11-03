package io.github.jichuo1.meguviz.hook.benchmark.transport;

import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;
import io.github.jichuo1.meguviz.hook.core.JsonLineTraceTransport;

import java.util.Map;

/** Cases for {@link InterleavedAb}: A is the round-1 copy, B the current implementation. */
final class InterleavedCases {
    private InterleavedCases() { }

    static Map<String, InterleavedAb.Op[]> all() {
        Map<String, InterleavedAb.Op[]> cases = InterleavedAb.newCases();
        for (String shape : new String[] {"none0", "ascii0", "ascii8", "unicode0", "unicode8", "unicode32", "cjk0"}) {
            Shape s = new Shape(shape);
            cases.put("encode:" + shape, new InterleavedAb.Op[] {
                () -> Round1Transport.encode(s.event, s.includeMessage, s.maxFrames).length,
                () -> JsonLineTraceTransport.encode(s.event, s.includeMessage, s.maxFrames).length,
            });
        }
        // Harness calibration: both sides run the very same code, so B/A should be about 1.
        Shape calibration = new Shape("ascii0");
        cases.put("calibrate:round1-vs-round1", new InterleavedAb.Op[] {
            () -> Round1Transport.encode(calibration.event, true, 0).length,
            () -> Round1Transport.encode(calibration.event, true, 0).length,
        });
        cases.put("calibrate:current-vs-current", new InterleavedAb.Op[] {
            () -> JsonLineTraceTransport.encode(calibration.event, true, 0).length,
            () -> JsonLineTraceTransport.encode(calibration.event, true, 0).length,
        });
        return cases;
    }

    /** Same shapes as AbRound2EncodeBenchmark: message kind + maxFrames out of a 40-frame trace. */
    static final class Shape {
        final CallbackExceptionTracker.Event event;
        final boolean includeMessage;
        final int maxFrames;

        Shape(String shape) {
            int digits = shape.length();
            while (Character.isDigit(shape.charAt(digits - 1))) digits--;
            String kind = shape.substring(0, digits);
            maxFrames = Integer.parseInt(shape.substring(digits));
            includeMessage = !"none".equals(kind);
            String message = "ascii".equals(kind) ? TraceFixtures.asciiMessage(900)
                    : "unicode".equals(kind) ? TraceFixtures.unicodeMessage(400)
                    : "cjk".equals(kind) ? TraceFixtures.cjkMessage(600) : null;
            event = TraceFixtures.event("bench.ab2.hook", new TraceFixtures.SyntheticFailure(message, 40));
        }
    }
}
