package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** JVM proxy mapping only; not a real framework or Android runtime test. */
public class ConstructorReceiver102Test {
    public static final class Target { public Target() { } }

    @Test public void constructorReceiverIsReadOnlyWhenRequested() throws Throwable {
        Constructor<?> target = Target.class.getConstructor();
        Object receiver = new Target();
        AtomicReference<XposedInterface.Hooker> hooker = new AtomicReference<XposedInterface.Hooker>();
        AtomicBoolean read = new AtomicBoolean();
        AtomicInteger receiverReads = new AtomicInteger();
        AtomicInteger proceeds = new AtomicInteger();
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
                case "getApiVersion": return 102;
                case "getFrameworkName": return "proxy-102";
                case "getFrameworkVersion": return "1";
                case "getFrameworkVersionCode": return 1L;
                case "hook": return builder;
                default: throw new UnsupportedOperationException(method.getName());
            }
        });
        XposedInterface.Chain chain = proxy(XposedInterface.Chain.class, (object, method, args) -> {
            switch (method.getName()) {
                case "getThisObject": receiverReads.incrementAndGet(); return receiver;
                case "proceed": proceeds.incrementAndGet(); return null;
                default: throw new UnsupportedOperationException(method.getName());
            }
        });
        HookEnvironment env = HookEnvironment.create(new LibXposed102Backend(api,
                new LibXposed102Context("constructor.102", getClass().getClassLoader(), 1L)));
        try {
            RegistrationReceipt receipt = env.install(HookSpec.forMember("constructor.102", "test", target)
                    .before(i -> { assertNull(i.thisObject()); if (read.get()) assertSame(receiver, i.constructorReceiver()); })
                    .after((i, r) -> { assertNull(i.thisObject()); if (read.get()) assertSame(receiver, i.constructorReceiver()); })
                    .build());
            assertTrue(receipt.toString(), receipt.isInstalled());
            assertNull(hooker.get().intercept(chain));
            assertEquals(0, receiverReads.get());
            read.set(true);
            assertNull(hooker.get().intercept(chain));
            assertEquals(2, receiverReads.get());
            assertEquals(2, proceeds.get());
        } finally { env.close(); }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
