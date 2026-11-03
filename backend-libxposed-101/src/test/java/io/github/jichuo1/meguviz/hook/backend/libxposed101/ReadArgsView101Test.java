package io.github.jichuo1.meguviz.hook.backend.libxposed101;

import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.ParameterAccess;
import io.github.jichuo1.meguviz.hook.api.ReadArgsInvocation;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.libxposed.api.XposedInterface;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.AbstractList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** JVM proxy check of API101 mapping; does not prove a real framework's allocation behavior. */
public class ReadArgsView101Test {
    public static int add(int left, int right) { return left + right; }

    @Test public void viewReadsSingleSlotsWithoutListCopyAndProceedsWithIncomingArguments() throws Throwable {
        Method target = getClass().getMethod("add", int.class, int.class);
        AtomicReference<XposedInterface.Hooker> hooker = new AtomicReference<XposedInterface.Hooker>();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger proceeds = new AtomicInteger();
        List<Object> values = new AbstractList<Object>() {
            @Override public Object get(int index) { return index == 0 ? 3 : 4; }
            @Override public int size() { return 2; }
            @Override public Object[] toArray() { throw new AssertionError("unexpected argument array"); }
            @Override public <T> T[] toArray(T[] array) { throw new AssertionError("unexpected argument array"); }
        };
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
        XposedInterface api = proxy(XposedInterface.class, (object, method, args) -> {
            switch (method.getName()) {
                case "getApiVersion": return 101;
                case "getFrameworkName": return "proxy-101";
                case "getFrameworkVersion": return "1";
                case "hook": return builder;
                default: throw new UnsupportedOperationException(method.getName());
            }
        });
        XposedInterface.Chain chain = proxy(XposedInterface.Chain.class, (object, method, args) -> {
            switch (method.getName()) {
                case "getArg": reads.incrementAndGet(); return values.get((Integer) args[0]);
                case "getArgs": throw new AssertionError("the view must not copy through getArgs()");
                case "proceed": assertEquals("view must not pass an argument array", 0, method.getParameterCount());
                    proceeds.incrementAndGet(); return 7;
                default: throw new UnsupportedOperationException(method.getName());
            }
        });
        HookEnvironment env = HookEnvironment.create(new LibXposed101Backend(api,
                new LibXposed101Context("view.101", getClass().getClassLoader(), 1L)));
        try {
            RegistrationReceipt receipt = env.install(new HookSpec.Builder(HookId.of("view.101"),
                    HookOwner.of("test"), TargetSpec.ofMember(target)).access(ParameterAccess.READ_ARGS_VIEW)
                    .before(i -> assertEquals(3, ((ReadArgsInvocation) i).getArgument(0)))
                    .after((i, r) -> assertEquals(4, ((ReadArgsInvocation) i).getArgument(1))).build());
            assertTrue(receipt.toString(), receipt.isInstalled());
            assertEquals(7, hooker.get().intercept(chain));
            assertEquals("one slot read per getArgument", 2, reads.get());
            assertEquals(1, proceeds.get());
        } finally { env.close(); }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
