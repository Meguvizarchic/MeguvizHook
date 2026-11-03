package io.github.jichuo1.meguviz.hook.ref;

import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.ref.api.query.AccessPolicy;
import io.github.jichuo1.meguviz.ref.api.query.Hierarchy;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CustomScopeDescriptorContractTest {
    public static class Parent {
        public String inherited(String value) { return value; }
    }

    public static final class Host extends Parent {
        public String value(String value) { return value; }
        public String value(Integer value) { return String.valueOf(value); }
        public int unrelated(String value) { return value.length(); }
    }

    private static final ClassLoader LOADER = Host.class.getClassLoader();

    private static TargetSpec.DescriptorTarget method(String name, String parameter, String result) {
        return TargetSpec.descriptor(TargetKind.METHOD, Host.class.getName(), name)
                .parameters(parameter).returning(result).build();
    }

    private static final class Context implements BackendContext {
        final AtomicReference<ClassLoader> loader = new AtomicReference<ClassLoader>(LOADER);
        final AtomicLong epoch = new AtomicLong(3);
        @Override public String processName() { return "test.process"; }
        @Override public ClassLoader classLoader() { return loader.get(); }
        @Override public long codeEpoch() { return epoch.get(); }
    }

    private static CodeScope customScope(ClassLoader loader) {
        return CodeScope.open(loader, AccessPolicy.PRESERVE_JAVA_ACCESS, 128);
    }

    @Test public void customScopeRetainsDescriptorFiltersHierarchyAndIndependentOwnership() throws Exception {
        List<CodeScope> opened = new ArrayList<CodeScope>();
        MeguvizRefMemberResolver resolver = MeguvizRefMemberResolver.withScope(loader -> {
            CodeScope scope = customScope(loader);
            opened.add(scope);
            return scope;
        }, Hierarchy.SUPERCLASSES);

        List<Executable> value = resolver.resolve(method("value", "java.lang.String", "java.lang.String"), LOADER);
        assertEquals(Collections.singletonList(Host.class.getDeclaredMethod("value", String.class)), value);
        assertTrue(resolver.resolve(method("absent", "java.lang.String", "java.lang.String"), LOADER).isEmpty());
        assertTrue(resolver.resolve(method("value", "java.lang.Long", "java.lang.String"), LOADER).isEmpty());
        assertTrue(resolver.resolve(method("value", "java.lang.String", "int"), LOADER).isEmpty());
        assertEquals(Collections.singletonList(Parent.class.getDeclaredMethod("inherited", String.class)),
                resolver.resolve(method("inherited", "java.lang.String", "java.lang.String"), LOADER));
        assertEquals(5, opened.size());
        for (CodeScope scope : opened) assertEquals(CodeScope.State.CLOSED, scope.state());
    }

    @Test public void customSharedScopeRemainsOpenUntilCloseAndRetiresOnEpochChange() throws Exception {
        Context context = new Context();
        AtomicReference<CodeScope> opened = new AtomicReference<CodeScope>();
        MeguvizRefResolverSession session = MeguvizRefResolverSession.withScope(context, loader -> {
            CodeScope scope = customScope(loader);
            assertTrue(opened.compareAndSet(null, scope));
            return scope;
        }, Hierarchy.SUPERCLASSES);
        try {
            assertEquals(Collections.singletonList(Host.class.getDeclaredMethod("value", String.class)),
                    session.resolve(method("value", "java.lang.String", "java.lang.String"), LOADER));
            assertTrue(session.resolve(method("value", "java.lang.String", "int"), LOADER).isEmpty());
            assertEquals(Collections.singletonList(Parent.class.getDeclaredMethod("inherited", String.class)),
                    session.resolve(method("inherited", "java.lang.String", "java.lang.String"), LOADER));
            assertEquals(CodeScope.State.OPEN, opened.get().state());
            context.epoch.incrementAndGet();
            assertThrows(BackendInstallException.class,
                    () -> session.resolve(method("value", "java.lang.String", "java.lang.String"), LOADER));
            assertEquals(MeguvizRefResolverSession.State.CLOSED, session.report().state());
            assertEquals(CodeScope.State.CLOSED, opened.get().state());
        } finally {
            session.close();
        }
    }

    @Test public void customScopeExplicitPolicyAndLoaderMismatchKeepExistingSessionContract() throws Exception {
        Context context = new Context();
        AtomicReference<CodeScope> opened = new AtomicReference<CodeScope>();
        MeguvizRefResolverSession session = MeguvizRefResolverSession.withScope(context, loader -> {
            CodeScope scope = customScope(loader);
            opened.set(scope);
            return scope;
        }, Hierarchy.DECLARED, MeguvizRefResolverSession.WaiterInterruptionPolicy.ISOLATE_VERIFIED_WAITER);
        try {
            assertTrue(session.resolve(method("inherited", "java.lang.String", "java.lang.String"), LOADER).isEmpty());
            context.loader.set(new ClassLoader(LOADER) { });
            assertThrows(BackendInstallException.class,
                    () -> session.resolve(method("value", "java.lang.String", "java.lang.String"), LOADER));
            assertEquals(MeguvizRefResolverSession.State.CLOSED, session.report().state());
            assertEquals(CodeScope.State.CLOSED, opened.get().state());
        } finally {
            session.close();
        }
    }
}
