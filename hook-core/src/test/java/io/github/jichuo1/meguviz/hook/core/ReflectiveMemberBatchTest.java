package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.spi.BatchMemberResolver;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** A member batch must return exactly what the stateless resolver returns, and never remember a miss. */
public class ReflectiveMemberBatchTest {
    private static final Class<?>[] OWNERS = {
        String.class, StringBuilder.class, java.util.Collections.class, java.util.ArrayList.class,
        java.util.HashMap.class, Map.Entry.class, ReflectiveMemberBatchTest.class, Initialized.class,
    };

    @Test public void batchResultsEqualStatelessResultsForEveryDeclaredMember() throws Exception {
        ReflectiveMemberResolver resolver = new ReflectiveMemberResolver();
        ClassLoader loader = getClass().getClassLoader();
        List<TargetSpec.DescriptorTarget> targets = new ArrayList<>();
        for (Class<?> owner : OWNERS) {
            Set<String> names = new LinkedHashSet<>();
            for (Method method : owner.getDeclaredMethods()) {
                names.add(method.getName());
                targets.add(described(TargetKind.METHOD, owner, method.getName(), method.getParameterTypes(),
                        method.getReturnType(), false));
                // Canonical-name spelling of the same signature.
                targets.add(canonical(owner, method));
            }
            for (String name : names) {
                targets.add(TargetSpec.descriptor(TargetKind.METHOD, owner.getName(), name).build());
            }
            for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
                targets.add(described(TargetKind.CONSTRUCTOR, owner, "<init>", constructor.getParameterTypes(), void.class, false));
            }
            targets.add(TargetSpec.descriptor(TargetKind.CONSTRUCTOR, owner.getName(), "<init>").build());
            targets.add(TargetSpec.descriptor(TargetKind.METHOD, owner.getName(), "absentMember").build());
        }
        targets.add(TargetSpec.descriptor(TargetKind.METHOD, "absent.Owner", "x").build());
        int nonEmpty = 0;
        try (BatchMemberResolver.Batch batch = resolver.openBatch(loader)) {
            // Two passes: the second one is served entirely from the batch's tables.
            for (int pass = 0; pass < 2; pass++) {
                for (TargetSpec.DescriptorTarget target : targets) {
                    List<Executable> expected = resolver.resolve(target, loader);
                    List<Executable> actual = batch.resolve(target);
                    assertEquals(target.toString(), expected, actual);
                    if (!actual.isEmpty()) nonEmpty++;
                }
            }
        }
        assertTrue("the comparison must cover real matches", nonEmpty > targets.size());
    }

    @Test public void batchReusesOneMemberTablePerClass() throws Exception {
        ReflectiveMemberResolver resolver = new ReflectiveMemberResolver();
        ClassLoader loader = getClass().getClassLoader();
        TargetSpec.DescriptorTarget length = TargetSpec.descriptor(TargetKind.METHOD, "java.lang.String", "length").build();
        TargetSpec.DescriptorTarget init = TargetSpec.descriptor(TargetKind.CONSTRUCTOR, "java.lang.String", "<init>")
                .parameters("java.lang.String").build();
        assertNotSame("the stateless path copies members on every call",
                resolver.resolve(length, loader).get(0), resolver.resolve(length, loader).get(0));
        try (BatchMemberResolver.Batch batch = resolver.openBatch(loader)) {
            assertSame(batch.resolve(length).get(0), batch.resolve(length).get(0));
            assertSame(batch.resolve(init).get(0), batch.resolve(init).get(0));
        }
        try (BatchMemberResolver.Batch first = resolver.openBatch(loader);
             BatchMemberResolver.Batch second = resolver.openBatch(loader)) {
            assertNotSame("batches are independent", first, second);
            assertNotSame(first.resolve(length).get(0), second.resolve(length).get(0));
        }
    }

    @Test public void missingClassIsLookedUpAgainWithinOneBatch() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        ClassLoader flaky = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(Initialized.class.getName()) && requests.incrementAndGet() == 1) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        TargetSpec.DescriptorTarget target = TargetSpec.descriptor(TargetKind.METHOD, Initialized.class.getName(), "value").build();
        try (BatchMemberResolver.Batch batch = new ReflectiveMemberResolver().openBatch(flaky)) {
            assertEquals(Collections.emptyList(), batch.resolve(target));
            assertEquals("a miss is not remembered", 1, batch.resolve(target).size());
            assertEquals(1, batch.resolve(target).size());
            assertEquals("only the miss and the first hit reach the loader", 2, requests.get());
        }
    }

    @Test public void initializeFlagIsHonouredAcrossCachedLookups() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        String name = Lazy.class.getName();
        assertFalse(Lazy.Probe.initialized);
        try (BatchMemberResolver.Batch batch = new ReflectiveMemberResolver().openBatch(loader)) {
            assertEquals(1, batch.resolve(TargetSpec.descriptor(TargetKind.METHOD, name, "value").initializeClass(false).build()).size());
            assertFalse("initializeClass(false) must not initialize", Lazy.Probe.initialized);
            assertEquals(1, batch.resolve(TargetSpec.descriptor(TargetKind.METHOD, name, "value").initializeClass(true).build()).size());
            assertTrue("a later initializeClass(true) still initializes", Lazy.Probe.initialized);
        }
    }

    @Test public void closedBatchRejectsUse() throws Exception {
        BatchMemberResolver.Batch batch = new ReflectiveMemberResolver().openBatch(getClass().getClassLoader());
        batch.close();
        try {
            batch.resolve(TargetSpec.descriptor(TargetKind.METHOD, String.class.getName(), "length").build());
            fail("closed batch must not resolve");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("closed"));
        }
    }

    private static TargetSpec.DescriptorTarget described(TargetKind kind, Class<?> owner, String name, Class<?>[] parameters,
                                                         Class<?> returns, boolean initialize) {
        String[] names = new String[parameters.length];
        for (int i = 0; i < parameters.length; i++) names[i] = parameters[i].getName();
        return TargetSpec.descriptor(kind, owner.getName(), name).parameters(names).returning(returns.getName())
                .initializeClass(initialize).build();
    }

    private static TargetSpec.DescriptorTarget canonical(Class<?> owner, Method method) {
        Class<?>[] parameters = method.getParameterTypes();
        String[] names = new String[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            String canonical = parameters[i].getCanonicalName();
            names[i] = canonical == null ? parameters[i].getName() : canonical;
        }
        String returns = method.getReturnType().getCanonicalName();
        return TargetSpec.descriptor(TargetKind.METHOD, owner.getName(), method.getName()).parameters(names)
                .returning(returns == null ? method.getReturnType().getName() : returns).build();
    }

    static final class Initialized {
        static int value() { return 1; }
    }

    static final class Lazy {
        static final class Probe { static boolean initialized; }
        static { Probe.initialized = true; }
        static int value() { return 2; }
    }
}
