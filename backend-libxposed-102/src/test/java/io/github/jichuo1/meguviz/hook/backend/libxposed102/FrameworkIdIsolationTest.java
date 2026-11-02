package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.api.HookId;
import io.github.jichuo1.meguviz.hook.api.HookOwner;
import io.github.jichuo1.meguviz.hook.api.HookRegistration;
import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.api.RegistrationReceipt;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.libxposed.api.XposedInterface;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Models API102's same-module, same-target non-null ID replacement contract. */
public class FrameworkIdIsolationTest {
    public static final class Target {
        private Target() { }
        public static int add(int left, int right) { return left + right; }
    }

    @Test
    public void frameworkStubReplacesOnlyTheSameNonNullIdOnTheSameTarget() throws Throwable {
        IdAwareFramework framework = new IdAwareFramework();
        XposedInterface api = framework.api();
        Method target = Target.class.getMethod("add", int.class, int.class);
        XposedInterface.HookHandle old = api.hook(target).setId("same").intercept(chain -> 10);
        api.hook(target).setId("same").intercept(chain -> 20);
        assertEquals(1, framework.active.size());
        assertEquals(20, framework.invoke(target, 1, 2));
        try {
            old.replaceHook(chain -> 30);
            fail("the replaced handle must be invalid");
        } catch (IllegalStateException expected) {
            // The stub's positive control matches the framework's documented ID behavior.
        }
    }

    @Test
    public void sameLogicalIdInTwoEnvironmentsStaysIndependentIncludingReplacement() throws Throwable {
        IdAwareFramework framework = new IdAwareFramework();
        XposedInterface api = framework.api();
        Method target = Target.class.getMethod("add", int.class, int.class);
        LibXposed102Context context = new LibXposed102Context("stub.process", getClass().getClassLoader(), 1L);
        HookEnvironment first = HookEnvironment.create(new LibXposed102Backend(api, context));
        HookEnvironment second = HookEnvironment.create(new LibXposed102Backend(api, context));
        AtomicInteger firstOld = new AtomicInteger();
        AtomicInteger firstNew = new AtomicInteger();
        AtomicInteger sibling = new AtomicInteger();
        try {
            HookRegistration old = install(first, spec(target, firstOld));
            HookRegistration other = install(second, spec(target, sibling));
            assertEquals(0, framework.setIdCalls);
            assertEquals(2, framework.active.size());
            assertNull(framework.active.get(0).id);
            assertNull(framework.active.get(1).id);

            assertEquals(3, framework.invoke(target, 1, 2));
            assertEquals(1, firstOld.get());
            assertEquals(1, sibling.get());
            assertEquals(1, framework.originalCalls);

            RegistrationReceipt replacement = first.replace(spec(target, firstNew));
            assertTrue(replacement.toString(), replacement.isInstalled());
            assertEquals(1, framework.replaceCalls);
            assertEquals(2, framework.active.size());
            old.close();
            assertEquals("old close must not unhook the replacement", 0, framework.unhookCalls);
            assertEquals(3, framework.invoke(target, 1, 2));
            assertEquals(1, firstOld.get());
            assertEquals(1, firstNew.get());
            assertEquals(2, sibling.get());
            assertEquals(2, framework.originalCalls);

            replacement.registration().close();
            assertEquals(1, framework.active.size());
            assertEquals(3, framework.invoke(target, 1, 2));
            assertEquals(1, firstNew.get());
            assertEquals(3, sibling.get());
            assertEquals(3, framework.originalCalls);
            other.close();
            assertTrue(framework.active.isEmpty());
            assertEquals(2, framework.unhookCalls);
        } finally {
            first.close();
            second.close();
        }
    }

    private static HookSpec spec(Method target, AtomicInteger counter) {
        return new HookSpec.Builder(HookId.of("same.logical.id"), HookOwner.of("same-owner"),
                TargetSpec.ofMember(target)).before(invocation -> counter.incrementAndGet()).build();
    }

    private static HookRegistration install(HookEnvironment environment, HookSpec spec) {
        RegistrationReceipt receipt = environment.install(spec);
        assertTrue(receipt.toString(), receipt.isInstalled());
        return receipt.registration();
    }

    /** Single-module stub; null IDs append, equal non-null IDs replace at the existing position. */
    private static final class IdAwareFramework {
        final List<Node> active = new ArrayList<Node>();
        int setIdCalls;
        int replaceCalls;
        int unhookCalls;
        int originalCalls;

        XposedInterface api() {
            return (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getApiVersion": return XposedInterface.API_102;
                            case "getFrameworkName": return "IdAwareStub";
                            case "getFrameworkVersion": return "1";
                            case "hook": return builder((Executable) args[0]);
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        private XposedInterface.HookBuilder builder(Executable target) {
            final String[] id = {null};
            return (XposedInterface.HookBuilder) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.HookBuilder.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setPriority":
                            case "setExceptionMode": return proxy;
                            case "setId": setIdCalls++; id[0] = (String) args[0]; return proxy;
                            case "intercept":
                                Node node = new Node(target, id[0], (XposedInterface.Hooker) args[0]);
                                int position = indexOf(target, id[0]);
                                if (position < 0) active.add(node);
                                else {
                                    active.get(position).valid = false;
                                    active.set(position, node);
                                }
                                return handle(node);
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        private int indexOf(Executable target, String id) {
            if (id == null) return -1;
            for (int i = 0; i < active.size(); i++) {
                Node node = active.get(i);
                if (target.equals(node.target) && id.equals(node.id)) return i;
            }
            return -1;
        }

        private XposedInterface.HookHandle handle(Node node) {
            return (XposedInterface.HookHandle) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.HookHandle.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getId": return node.id;
                            case "getExecutable": return node.target;
                            case "unhook":
                                unhookCalls++;
                                active.remove(node);
                                node.valid = false;
                                return null;
                            case "replaceHook":
                                if (!node.valid) throw new IllegalStateException("invalid hook handle");
                                int position = active.indexOf(node);
                                if (position < 0) throw new IllegalStateException("hook not installed");
                                Node replacement = new Node(node.target, node.id, (XposedInterface.Hooker) args[0]);
                                active.set(position, replacement);
                                node.valid = false;
                                replaceCalls++;
                                return handle(replacement);
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        Object invoke(Method target, Object... args) throws Throwable {
            return invokeAt(new ArrayList<Node>(active), 0, target, args);
        }

        private Object invokeAt(List<Node> snapshot, int index, Method target, Object[] args) throws Throwable {
            if (index == snapshot.size()) {
                originalCalls++;
                return (Integer) args[0] + (Integer) args[1];
            }
            Node node = snapshot.get(index);
            assertEquals(target, node.target);
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, forwarded) -> {
                        switch (method.getName()) {
                            case "getExecutable": return target;
                            case "getThisObject": return null;
                            case "getArgs": return Arrays.asList(args);
                            case "proceed":
                                Object[] next = forwarded == null || forwarded.length == 0
                                        ? args : (Object[]) forwarded[0];
                                return invokeAt(snapshot, index + 1, target, next);
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
            return node.hooker.intercept(chain);
        }
    }

    private static final class Node {
        final Executable target;
        final String id;
        final XposedInterface.Hooker hooker;
        boolean valid = true;

        Node(Executable target, String id, XposedInterface.Hooker hooker) {
            this.target = target;
            this.id = id;
            this.hooker = hooker;
        }
    }
}
