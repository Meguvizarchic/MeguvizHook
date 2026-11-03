package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.AfterCallback;
import io.github.jichuo1.meguviz.hook.api.BeforeCallback;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;

import static org.junit.Assert.*;

/** Bind-time facts are copied once; signature-dependent check data stays lazy and keeps messages. */
public final class BoundPlanPrecomputeTest {
    public static int primitive(int value) { return value; }
    public static void nothing() { }
    public CharSequence instance(String text) { return text; }

    public static final class Built {
        public Built(long value) { }
    }

    private static BoundPlan plan(HookSpec spec, java.lang.reflect.Executable target) {
        return new BoundPlan(spec, target, 1, BoundPlanPrecomputeTest.class.getClassLoader(), 1);
    }

    @Test public void receiverKindCountAndCallbackArraysAreFrozenAtBind() throws Exception {
        Method staticMethod = getClass().getMethod("primitive", int.class);
        Method instanceMethod = getClass().getMethod("instance", String.class);
        Constructor<Built> constructor = Built.class.getConstructor(long.class);
        BeforeCallback first = invocation -> { };
        BeforeCallback second = invocation -> { };
        AfterCallback after = (invocation, result) -> { };
        HookSpec spec = HookSpec.forMember("plan.flags", "test", instanceMethod)
                .access(ParameterAccess.READ_ARGS).before(first).before(second).after(after).build();

        BoundPlan instance = plan(spec, instanceMethod);
        assertTrue(instance.isInstanceMethod());
        assertFalse(instance.isConstructor());
        assertEquals(1, instance.parameterCount());
        assertSame(ParameterAccess.READ_ARGS, instance.access());
        assertArrayEquals(new Object[]{first, second}, instance.befores());
        assertArrayEquals(new Object[]{after}, instance.afters());
        assertNull(instance.singleBeforeOnly());
        assertNull(instance.singleAfterOnly());

        HookSpec staticSpec = HookSpec.forMember("plan.static", "test", staticMethod).before(first).build();
        assertFalse(plan(staticSpec, staticMethod).isInstanceMethod());
        assertEquals(0, plan(staticSpec, staticMethod).afters().length);
        HookSpec ctorSpec = HookSpec.forMember("plan.ctor", "test", constructor).after(after).build();
        BoundPlan ctor = plan(ctorSpec, constructor);
        assertFalse(ctor.isInstanceMethod());
        assertTrue(ctor.isConstructor());
        assertEquals(0, ctor.befores().length);
    }

    @Test public void resultCheckDataIsLazyAndKeepsMessages() throws Exception {
        Method primitive = getClass().getMethod("primitive", int.class);
        BoundPlan plan = plan(HookSpec.forMember("plan.result", "test", primitive)
                .after((invocation, result) -> { }).build(), primitive);
        Field resultType = BoundPlan.class.getDeclaredField("resultType");
        resultType.setAccessible(true);
        assertNull("return type is not resolved at bind", resultType.get(plan));
        TypeChecks.checkResultValue(plan, 3);
        Object cached = resultType.get(plan);
        assertNotNull(cached);
        TypeChecks.checkResultValue(plan, Integer.valueOf(4));
        assertSame("resolved once", cached, resultType.get(plan));
        assertEquals("result of " + primitive + " is primitive int, null is not allowed",
                assertThrows(IllegalArgumentException.class, () -> TypeChecks.checkResultValue(plan, null)).getMessage());
        assertEquals("result of " + primitive + " expects Integer for primitive int but got java.lang.Long",
                assertThrows(IllegalArgumentException.class, () -> TypeChecks.checkResultValue(plan, 4L)).getMessage());

        Method reference = getClass().getMethod("instance", String.class);
        BoundPlan referencePlan = plan(HookSpec.forMember("plan.ref", "test", reference)
                .after((invocation, result) -> { }).build(), reference);
        TypeChecks.checkResultValue(referencePlan, null);
        TypeChecks.checkResultValue(referencePlan, new StringBuilder("ok"));
        assertEquals("result of " + reference + " expects java.lang.CharSequence but got java.lang.Integer",
                assertThrows(IllegalArgumentException.class, () -> TypeChecks.checkResultValue(referencePlan, 1)).getMessage());

        Method nothing = getClass().getMethod("nothing");
        BoundPlan voidPlan = plan(HookSpec.forMember("plan.void", "test", nothing)
                .after((invocation, result) -> { }).build(), nothing);
        TypeChecks.checkResultValue(voidPlan, null);
        assertEquals("void/constructor target cannot return a value: " + nothing,
                assertThrows(IllegalArgumentException.class, () -> TypeChecks.checkResultValue(voidPlan, "x")).getMessage());
        Constructor<Built> constructor = Built.class.getConstructor(long.class);
        BoundPlan ctorPlan = plan(HookSpec.forMember("plan.ctor.result", "test", constructor)
                .after((invocation, result) -> { }).build(), constructor);
        assertThrows(IllegalArgumentException.class, () -> TypeChecks.checkResultValue(ctorPlan, 1L));
    }

    @Test public void planAndExecutableResultChecksAgree() throws Exception {
        Object[] values = {null, 1, 1L, "s", new Object(), Boolean.TRUE};
        for (Method method : new Method[]{getClass().getMethod("primitive", int.class),
                getClass().getMethod("instance", String.class), getClass().getMethod("nothing")}) {
            BoundPlan plan = plan(HookSpec.forMember("plan.agree." + method.getName(), "test", method)
                    .after((invocation, result) -> { }).build(), method);
            for (Object value : values) {
                Throwable byTarget = capture(() -> TypeChecks.checkResultValue(method, value));
                Throwable byPlan = capture(() -> TypeChecks.checkResultValue(plan, value));
                assertEquals(method + " / " + value, byTarget == null ? null : byTarget.getMessage(),
                        byPlan == null ? null : byPlan.getMessage());
            }
        }
    }

    private static Throwable capture(Runnable call) {
        try {
            call.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }
}
