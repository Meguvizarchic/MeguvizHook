package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.MutableArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DirectAfterDispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.DispatchResult;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInvocation;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
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
 * JVM proxy checks of the API102 argument adapter: slot-by-slot materialization with a list
 * fallback, the index-backed read-only view, proceed() versus proceed(args), the direct value
 * transport for generic plans, and the cached bridge lookup. Not evidence of framework behavior.
 */
public class ChainArguments102Test {
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
                    case "getApiVersion": return XposedInterface.API_102;
                    case "getFrameworkName": return "proxy-102";
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
        HookEnvironment env = HookEnvironment.create(new LibXposed102Backend(framework.api(),
                new LibXposed102Context("args.102", getClass().getClassLoader(), 1L)));
        try {
            RegistrationReceipt receipt = env.install(spec.build());
            assertTrue(receipt.toString(), receipt.isInstalled());
            assertEquals("generic plans use the flyweight chain transport", "chain", transport(framework.hooker.get()));
            return framework.hooker.get().intercept(chain.chain());
        } finally {
            env.close();
        }
    }

    /** 102 binds one hooker per channel; the captured binding type names its transport. */
    static String transport(XposedInterface.Hooker hooker) {
        String transport = "dispatch";
        for (java.lang.reflect.Field field : hooker.getClass().getDeclaredFields()) {
            String type = field.getType().getSimpleName();
            if (type.equals("ChainBinding")) return "chain";
            if (type.equals("AfterBinding")) transport = "direct";
        }
        return transport;
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
        List<?> view = new LibXposed102Backend.Chain102Invocation(null, recording.chain(), 2).readOnlyArguments();
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
        Object[] materialized = new LibXposed102Backend.Chain102Invocation(null, recording.chain(), 2).arguments();
        assertArrayEquals(new Object[]{3, null}, materialized);
        Object[] empty = new LibXposed102Backend.Chain102Invocation(null, new RecordingChain(0).chain(), 0).arguments();
        assertEquals(0, empty.length);
    }

    @Test public void flyweightAccessorReadsBoundedSlotsWithoutAnAdapter() throws Throwable {
        RecordingChain recording = new RecordingChain(2, 3, null);
        LibXposed102Backend.ChainAccessor102 accessor = new LibXposed102Backend.ChainAccessor102(2);
        XposedInterface.Chain chain = recording.chain();
        assertNull(accessor.thisObject(chain));
        assertEquals(3, accessor.argument(chain, 0));
        assertNull(accessor.argument(chain, 1));
        assertTrue(captureThrowable(() -> accessor.argument(chain, 2)) instanceof IndexOutOfBoundsException);
        assertTrue(captureThrowable(() -> accessor.argument(chain, -1)) instanceof IndexOutOfBoundsException);
        assertArrayEquals(new Object[]{3, null}, accessor.arguments(chain));
        assertFalse("single reads never copy through getArgs()", recording.calls.contains("getArgs"));
        RecordingChain shorter = new RecordingChain(1, 3, 4);
        assertArrayEquals("fewer chain slots fall back to the list form", new Object[]{3},
                accessor.arguments(shorter.chain()));
        RecordingChain proceeding = new RecordingChain(2, 3, 4);
        assertEquals(7, accessor.proceed(proceeding.chain(), null));
        assertEquals(11, accessor.proceed(proceeding.chain(), new Object[]{5, 6}));
        assertEquals(Arrays.asList("proceed()", "proceed(args)"), proceeding.calls);
    }

    @Test public void flyweightViewReadsAreBoundedByTheDeclaredCount() throws Throwable {
        RecordingChain chain = new RecordingChain(3, 3, 4, 5);
        AtomicReference<Throwable> beyond = new AtomicReference<Throwable>();
        AtomicReference<Throwable> negative = new AtomicReference<Throwable>();
        assertEquals(7, run(spec("args.view.bounds").access(ParameterAccess.READ_ARGS_VIEW).before(i -> {
            ReadArgsInvocation args = (ReadArgsInvocation) i;
            beyond.set(captureThrowable(() -> args.getArgument(2)));
            negative.set(captureThrowable(() -> args.getArgument(-1)));
        }), chain));
        assertTrue(beyond.get() instanceof IndexOutOfBoundsException);
        assertTrue(negative.get() instanceof IndexOutOfBoundsException);
        assertEquals("out-of-range reads never reach the chain", Collections.singletonList("proceed()"), chain.calls);
    }

    @Test public void channelWithoutTheChainTransportKeepsTheDirectAfterBinding() throws Throwable {
        Framework framework = new Framework();
        LibXposed102Backend real = new LibXposed102Backend(framework.api(),
                new LibXposed102Context("args.fallback.102", getClass().getClassLoader(), 1L));
        HookBackend directOnly = new HookBackend() {
            @Override public String id() { return real.id(); }
            @Override public io.github.jichuo1.meguviz.hook.api.BackendCapabilities capabilities() { return real.capabilities(); }
            @Override public io.github.jichuo1.meguviz.hook.core.spi.BackendContext context() { return real.context(); }
            @Override public BackendHandle install(BoundPlan plan, DispatchChannel channel) throws BackendInstallException {
                return real.install(plan, new DirectOnlyChannel((DirectAfterDispatchChannel) channel));
            }
        };
        HookEnvironment env = HookEnvironment.create(directOnly);
        try {
            RegistrationReceipt receipt = env.install(spec("args.fallback.direct").access(ParameterAccess.READ_ARGS)
                    .before(i -> assertEquals(3, ((ReadArgsInvocation) i).getArgument(0))).build());
            assertTrue(receipt.toString(), receipt.isInstalled());
            assertEquals("direct", transport(framework.hooker.get()));
            RecordingChain chain = new RecordingChain(2, 3, 4);
            assertEquals(7, framework.hooker.get().intercept(chain.chain()));
            assertEquals(Arrays.asList("getArg0", "getArg1", "proceed(args)"), chain.calls);
        } finally {
            env.close();
        }
    }

    /** A third-party channel offering only the direct-after transport. */
    private static final class DirectOnlyChannel implements DirectAfterDispatchChannel {
        private final DirectAfterDispatchChannel delegate;

        DirectOnlyChannel(DirectAfterDispatchChannel delegate) {
            this.delegate = delegate;
        }

        @Override public Object dispatchDirect(BackendInvocation invocation) throws Throwable {
            return delegate.dispatchDirect(invocation);
        }

        @Override public DispatchResult dispatch(BackendInvocation invocation) {
            return delegate.dispatch(invocation);
        }
    }

    @Test public void chainBridgeLookupIsCachedAndDistinguishesOldAndInconsistentCores() throws Exception {
        LibXposed102Backend.ChainBridgeLookup first = LibXposed102Backend.chainBridgeLookup();
        assertSame("resolved once per backend class", first, LibXposed102Backend.chainBridgeLookup());
        assertNotNull(first.bind);
        assertNull(first.failure);

        ClassLoader own = getClass().getClassLoader();
        LibXposed102Backend.ChainBridgeLookup older = LibXposed102Backend.ChainBridgeLookup.resolve(new HidingLoader(own,
                LibXposed102Backend.ChainBridgeLookup.BRIDGE, LibXposed102Backend.ChainBridgeLookup.CHANNEL,
                LibXposed102Backend.ChainBridgeLookup.ACCESSOR));
        assertNull("a round-1 core has no chain transport", older.bind);
        assertNull(older.failure);

        LibXposed102Backend.ChainBridgeLookup missingBridge = LibXposed102Backend.ChainBridgeLookup.resolve(
                new HidingLoader(own, LibXposed102Backend.ChainBridgeLookup.BRIDGE));
        assertNull(missingBridge.bind);
        assertEquals("core advertises the chain transport but its bridge is missing", missingBridge.failure);
        assertTrue(missingBridge.cause instanceof ClassNotFoundException);

        LibXposed102Backend.ChainBridgeLookup missingAccessor = LibXposed102Backend.ChainBridgeLookup.resolve(
                new HidingLoader(own, LibXposed102Backend.ChainBridgeLookup.ACCESSOR));
        assertNull(missingAccessor.bind);
        assertEquals("chain bridge is incomplete", missingAccessor.failure);
    }

    @Test public void bridgeLookupIsCachedAndDistinguishesOldAndInconsistentCores() throws Exception {
        LibXposed102Backend.BridgeLookup first = LibXposed102Backend.bridgeLookup();
        assertSame("resolved once per backend class", first, LibXposed102Backend.bridgeLookup());
        assertNotNull(first.bind);
        assertNull(first.failure);

        ClassLoader own = getClass().getClassLoader();
        LibXposed102Backend.BridgeLookup oldCore = LibXposed102Backend.BridgeLookup.resolve(
                new HidingLoader(own, LibXposed102Backend.BridgeLookup.BRIDGE, LibXposed102Backend.BridgeLookup.DIRECT));
        assertNull(oldCore.bind);
        assertNull(oldCore.failure);

        LibXposed102Backend.BridgeLookup inconsistent = LibXposed102Backend.BridgeLookup.resolve(
                new HidingLoader(own, LibXposed102Backend.BridgeLookup.BRIDGE));
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
