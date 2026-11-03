package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.HookSpec;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ArgumentTypeCheckTest {
    public static void target(int value, String text) { }

    @Test public void boundsAndTypesPreserveLazyResolutionAndErrorDetails() throws Exception {
        Method method = ArgumentTypeCheckTest.class.getMethod("target", int.class, String.class);
        HookSpec spec = HookSpec.forMember("test.types", "test", method)
                .before(invocation -> { }).build();
        BoundPlan plan = new BoundPlan(spec, method, 1, getClass().getClassLoader(), 1);
        Field types = BoundPlan.class.getDeclaredField("signature");
        types.setAccessible(true);
        try {
            TypeChecks.checkArgumentValue(plan, -1, 1);
            fail("negative index accepted");
        } catch (IndexOutOfBoundsException expected) {
            assertEquals("argument index -1 of " + method, expected.getMessage());
        }
        assertNull("negative index must not resolve parameter types", types.get(plan));
        try {
            TypeChecks.checkArgumentValue(plan, 2, null);
            fail("out-of-range index accepted");
        } catch (IndexOutOfBoundsException expected) {
            assertEquals("argument index 2 of " + method, expected.getMessage());
        }
        assertNotNull("nonnegative index resolves parameter types", types.get(plan));
        TypeChecks.checkArgumentValue(plan, 0, Integer.valueOf(3));
        TypeChecks.checkArgumentValue(plan, 1, null);
        try {
            TypeChecks.checkArgumentValue(plan, 0, "wrong");
            fail("incorrect primitive wrapper accepted");
        } catch (IllegalArgumentException expected) {
            assertEquals("argument 0 of " + method + " expects Integer for primitive int but got java.lang.String",
                    expected.getMessage());
        }
    }
}
