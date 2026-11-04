package io.github.jichuo1.meguviz.hook.benchmark.dispatch;

import org.openjdk.jol.info.ClassLayout;

/**
 * Prints HotSpot object layouts (JOL) of the per-call objects behind the B/op tables:
 * {@code ./gradlew :benchmark-jvm:layouts}. Layouts are host HotSpot facts (compressed oops and
 * class pointers by default); ART uses an 8-byte header and its own field order.
 */
public final class LayoutReport {
    private LayoutReport() {
    }

    private static final String[] CLASSES = {
            "io.github.jichuo1.meguviz.hook.core.InvocationFrame",
            "io.github.jichuo1.meguviz.hook.core.InvocationFrame$BorrowedInvocation",
            "io.github.jichuo1.meguviz.hook.core.InvocationFrame$AfterBorrow",
            "io.github.jichuo1.meguviz.hook.core.InvocationFrame$ResultSlot",
            "io.github.jichuo1.meguviz.hook.core.InFlightCounter",
            "io.github.jichuo1.meguviz.hook.core.DispatchResult",
            "io.github.jichuo1.meguviz.hook.api.CallOutcome",
            "io.github.jichuo1.meguviz.hook.benchmark.dispatch.ChainAdapter$GetArgInvocation",
            "io.github.jichuo1.meguviz.hook.benchmark.dispatch.ChainAdapter$ArgumentView",
            "io.github.jichuo1.meguviz.hook.benchmark.dispatch.VectorLikeChain",
    };

    public static void main(String[] args) throws Exception {
        for (String name : CLASSES) {
            Class<?> type;
            try {
                type = Class.forName(name);
            } catch (ClassNotFoundException absent) {
                System.out.println(name + ": not present");
                continue;
            }
            System.out.println(ClassLayout.parseClass(type).toPrintable());
        }
    }
}
