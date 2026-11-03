package io.github.jichuo1.meguviz.hook.backend.libxposed101;

import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.libxposed.api.XposedInterface;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.RandomAccess;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * JVM proxy checks of the API101 argument adapter: slot-by-slot materialization with a list
 * fallback, the index-backed read-only view, proceed() versus proceed(args), the direct value
 * transport for generic plans, and the cached bridge lookup. Not evidence of framework behavior.
 */
public class ChainArguments101Test {
    public static int add(int left, int right) { return left + right; }

    /** Chain stub recording which accessors the adapter used. */
    private static final class RecordingChain implements InvocationHandler {
        final Object[] slots;
        final int exposed;
        final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        Object[] proceededWith;

        RecordingChain(int exposed, Object... slots) {
            this.slots = slots;
            this.exposed = exposed;
        }

        XposedInterface.Chain chain() {
            return proxy(XposedInterface.Chain.class, this);
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getThisObject": return null;
                case "getArg": {
                    int index = (Integer) args[0];
                    calls.add("getArg" + index);
                    if (index >= exposed) throw new IndexOutOfBoundsException("slot " + index);
                    return slots[index];
                }
                case "getArgs":
                    calls.add("getArgs");
                    return Collections.unmodifiableList(Arrays.asList(Arrays.copyOf(slots, exposed)));
                case "proceed":
                    if (args == null) {
                        calls.add("proceed()");
                        return (Integer) slots[0] + (Integer) slots[1];
                    }
                    calls.add("proceed(args)");
                    proceededWith = (Object[]) args[0];
                    return (Integer) proceededWith[0] + (Integer) proceededWith[1];
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        }
    }

    private static final class Framework {
        final AtomicReference<XposedInterface.Hooker> hooker = new AtomicReference<XposedInterface.Hooker>();

        XposedInterface api() {
            XposedInterface.HookHandle handle = proxy(XposedInterface.HookHandle.class, (object, method, args) -> {
                if ("unhook".equals(method.getName())) return null;
                throw new UnsupportedOperationException(method.getName());
            });
            XposedInterface.HookBuilder builder = proxy(XposedInterface.HookBuilder.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "setPriority": case "setExceptionMode": return object;
                    case "intercept": hooker.set((XposedInterface.Hooker) args[0]); return handle;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
            return proxy(XposedInterface.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "getApiVersion": return LibXposed101Backend.API_LEVEL;
                    case "getFrameworkName": return "proxy-101";
                    case "getFrameworkVersion": return "1";
                    case "hook": return builder;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private Object run(HookSpec.Builder spec, RecordingChain chain) throws Throwable {
        Framework framework = new Framework();
        HookEnvironment env = HookEnvironment.create(new LibXposed101Backend(framework.api(),
                new LibXposed101Context("args.101", getClass().getClassLoader(), 1L)));
        try {
            RegistrationReceipt receipt = env.install(spec.build());
            assertTrue(receipt.toString(), receipt.isInstalled());
            assertTrue("generic plans use the direct value transport", hasDirectBinding(framework.hooker.get()));
            return framework.hooker.get().intercept(chain.chain());
        } finally {
            env.close();
        }
    }

    /** 101 keeps one switching hooker; the selected route carries the optional direct binding. */
    private static boolean hasDirectBinding(XposedInterface.Hooker hooker) throws Exception {
        java.lang.reflect.Field route = hooker.getClass().getDeclaredField("route");
        route.setAccessible(true);
        Object selected = route.get(hooker);
        java.lang.reflect.Field direct = selected.getClass().getDeclaredField("directAfter");
        direct.setAccessible(true);
        return direct.get(selected) != null;
    }

    private HookSpec.Builder spec(String id) throws NoSuchMethodException {
        return new HookSpec.Builder(HookId.of(id), HookOwner.of("test"),
                TargetSpec.ofMember(getClass().getMethod("add", int.class, int.class)));
    }

    @Test public void readArgsMaterializeSlotBySlotWithoutTheListCopy() throws Throwable {
        RecordingChain chain = new RecordingChain(2, 3, 4);
        assertEquals(7, run(spec("args.read").access(ParameterAccess.READ_ARGS)
                .before(i -> assertEquals(4, ((ReadArgsInvocation) i).getArgument(1)))
                .after((i, r) -> assertEquals(3, ((ReadArgsInvocation) i).getArgument(0))), chain));
        assertEquals(Arrays.asList("getArg0", "getArg1", "proceed(args)"), chain.calls);
        assertArrayEquals(new Object[]{3, 4}, chain.proceededWith);
        assertNotSame(chain.slots, chain.proceededWith);
    }

    @Test public void fewerChainSlotsFallBackToTheListForm() throws Throwable {
        RecordingChain chain = new RecordingChain(1, 3, 4);
        AtomicReference<Object> count = new AtomicReference<Object>();
        AtomicReference<Throwable> missing = new AtomicReference<Throwable>();
        Object value = run(spec("args.fallback").access(ParameterAccess.MUTABLE_ARGS).before(i -> {
            MutableArgsInvocation args = (MutableArgsInvocation) i;
            count.set(args.argumentCount());
            assertEquals(3, args.getArgument(0));
            missing.set(captureThrowable(() -> args.getArgument(1)));
            args.setArgument(0, 5);
            args.setEarlyResult(1);
        }), chain);
        assertEquals(1, value);
        assertEquals("count is the declared parameter count", 2, count.get());
        assertTrue(missing.get() instanceof IndexOutOfBoundsException);
        assertEquals(Arrays.asList("getArg0", "getArg1", "getArgs"), chain.calls);
    }

    @Test public void readOnlyViewReadsSingleSlotsAndKeepsIncomingArguments() throws Throwable {
        RecordingChain chain = new RecordingChain(2, 3, 4);
        assertEquals(7, run(spec("args.view").access(ParameterAccess.READ_ARGS_VIEW)
                .before(i -> assertEquals(4, ((ReadArgsInvocation) i).getArgument(1))), chain));
        assertEquals(Arrays.asList("getArg1", "proceed()"), chain.calls);
    }

    @Test public void untouchedSlotsProceedWithoutAnArrayAndWritesProceedWithOne() throws Throwable {
        RecordingChain untouched = new RecordingChain(2, 3, 4);
        assertEquals(7, run(spec("args.mutable.untouched").access(ParameterAccess.MUTABLE_ARGS)
                .before(i -> assertEquals(2, ((ReadArgsInvocation) i).argumentCount())), untouched));
        assertEquals(Collections.singletonList("proceed()"), untouched.calls);

        RecordingChain around = new RecordingChain(2, 3, 4);
        assertEquals(7, run(spec("args.around.untouched").around(i -> i.proceed()), around));
        assertEquals(Collections.singletonList("proceed()"), around.calls);

        RecordingChain written = new RecordingChain(2, 3, 4);
        assertEquals(14, run(spec("args.mutable.written").access(ParameterAccess.MUTABLE_ARGS)
                .before(i -> ((MutableArgsInvocation) i).setArgument(0, 10)), written));
        assertEquals(Arrays.asList("getArg0", "getArg1", "proceed(args)"), written.calls);
        assertArrayEquals(new Object[]{10, 4}, written.proceededWith);
        assertArrayEquals("framework slots are never written", new Object[]{3, 4}, written.slots);
    }

    @Test public void argumentViewIsRandomAccessUnmodifiableAndBounded() {
        RecordingChain recording = new RecordingChain(2, 3, null);
        List<?> view = new LibXposed101Backend.Chain101Invocation(null, recording.chain(), 2).readOnlyArguments();
        assertTrue(view instanceof RandomAccess);
        assertEquals(2, view.size());
        assertEquals(Arrays.asList(3, null), view);
        assertNull(view.get(1));
        assertTrue(captureThrowable(() -> view.get(2)) instanceof IndexOutOfBoundsException);
        assertTrue(captureThrowable(() -> view.get(-1)) instanceof IndexOutOfBoundsException);
        @SuppressWarnings("unchecked") List<Object> writable = (List<Object>) view;
        assertTrue(captureThrowable(() -> writable.set(0, 1)) instanceof UnsupportedOperationException);
        assertTrue(captureThrowable(() -> writable.add(1)) instanceof UnsupportedOperationException);
        assertFalse("the view never copies through getArgs()", recording.calls.contains("getArgs"));
        Object[] materialized = new LibXposed101Backend.Chain101Invocation(null, recording.chain(), 2).arguments();
        assertArrayEquals(new Object[]{3, null}, materialized);
        Object[] empty = new LibXposed101Backend.Chain101Invocation(null, new RecordingChain(0).chain(), 0).arguments();
        assertEquals(0, empty.length);
    }

    @Test public void bridgeLookupIsCachedAndDistinguishesOldAndInconsistentCores() throws Exception {
        LibXposed101Backend.BridgeLookup first = LibXposed101Backend.bridgeLookup();
        assertSame("resolved once per backend class", first, LibXposed101Backend.bridgeLookup());
        assertNotNull(first.bind);
        assertNull(first.failure);

        ClassLoader own = getClass().getClassLoader();
        LibXposed101Backend.BridgeLookup oldCore = LibXposed101Backend.BridgeLookup.resolve(
                new HidingLoader(own, LibXposed101Backend.BridgeLookup.BRIDGE, LibXposed101Backend.BridgeLookup.DIRECT));
        assertNull(oldCore.bind);
        assertNull(oldCore.failure);

        LibXposed101Backend.BridgeLookup inconsistent = LibXposed101Backend.BridgeLookup.resolve(
                new HidingLoader(own, LibXposed101Backend.BridgeLookup.BRIDGE));
        assertNull(inconsistent.bind);
        assertEquals("core advertises direct after but bridge is missing", inconsistent.failure);
        assertTrue(inconsistent.cause instanceof ClassNotFoundException);
    }

    /** Hides selected core classes by name to model an older or inconsistent core. */
    private static final class HidingLoader extends ClassLoader {
        private final List<String> hidden;

        HidingLoader(ClassLoader parent, String... hidden) {
            super(parent);
            this.hidden = Arrays.asList(hidden);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (hidden.contains(name)) throw new ClassNotFoundException(name);
            return super.loadClass(name, resolve);
        }
    }

    private interface ThrowingCall {
        void run() throws Throwable;
    }

    private static Throwable captureThrowable(ThrowingCall call) {
        try {
            call.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }
}
