package io.github.jichuo1.meguviz.hook.ref;

import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.ref.api.query.AccessPolicy;
import io.github.jichuo1.meguviz.ref.api.query.Hierarchy;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Desktop JVM access behavior only; this does not establish Android or ART behavior. */
public final class CustomScopeAccessPolicyTest {
    private static final ClassLoader LOADER = AccessPolicyFixture.class.getClassLoader();
    private static final TargetSpec.DescriptorTarget TARGET = TargetSpec.descriptor(
            TargetKind.METHOD, AccessPolicyFixture.class.getName(), "privateValue")
            .parameters("java.lang.String").returning("java.lang.String").build();

    private static final class Context implements BackendContext {
        @Override public String processName() { return "scope.access.test"; }
        @Override public ClassLoader classLoader() { return LOADER; }
        @Override public long codeEpoch() { return 1; }
    }

    @Test public void independentScopesHonorPrivateMemberAccessPolicy() throws Exception {
        for (AccessPolicy policy : new AccessPolicy[]{
                AccessPolicy.PRESERVE_JAVA_ACCESS, AccessPolicy.TRY_SUPPRESS_JAVA_CHECKS}) {
            MeguvizRefMemberResolver resolver = MeguvizRefMemberResolver.withScope(
                    loader -> CodeScope.open(loader, policy, 32), Hierarchy.DECLARED);
            assertPrivateAccess(resolver.resolve(TARGET, LOADER), policy);
        }
    }

    @Test public void sharedSessionsHonorPrivateMemberAccessPolicy() throws Exception {
        for (AccessPolicy policy : new AccessPolicy[]{
                AccessPolicy.PRESERVE_JAVA_ACCESS, AccessPolicy.TRY_SUPPRESS_JAVA_CHECKS}) {
            try (MeguvizRefResolverSession session = MeguvizRefResolverSession.withScope(
                    new Context(), loader -> CodeScope.open(loader, policy, 32), Hierarchy.DECLARED)) {
                assertPrivateAccess(session.resolve(TARGET, LOADER), policy);
            }
        }
    }

    private static void assertPrivateAccess(List<Executable> matches, AccessPolicy policy) throws Exception {
        assertEquals(1, matches.size());
        assertTrue(matches.get(0) instanceof Method);
        Method method = (Method) matches.get(0);
        assertSame(AccessPolicyFixture.class, method.getDeclaringClass());
        assertTrue(Modifier.isPrivate(method.getModifiers()));
        // Fixture is a separate top-level class, not a nestmate of this test class.
        assertNotSame(CustomScopeAccessPolicyTest.class.getNestHost(), AccessPolicyFixture.class.getNestHost());
        AccessPolicyFixture receiver = new AccessPolicyFixture();
        if (policy == AccessPolicy.PRESERVE_JAVA_ACCESS) {
            assertFalse(method.canAccess(receiver));
            assertThrows(IllegalAccessException.class, () -> method.invoke(receiver, "value"));
        } else {
            assertEquals(AccessPolicy.TRY_SUPPRESS_JAVA_CHECKS, policy);
            assertTrue(method.canAccess(receiver));
            assertEquals("private:value", method.invoke(receiver, "value"));
        }
    }
}

final class AccessPolicyFixture {
    private String privateValue(String value) { return "private:" + value; }
}
