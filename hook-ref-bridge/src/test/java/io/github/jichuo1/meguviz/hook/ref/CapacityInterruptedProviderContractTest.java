package io.github.jichuo1.meguviz.hook.ref;

import io.github.jichuo1.meguviz.hook.api.TargetKind;
import io.github.jichuo1.meguviz.hook.api.TargetSpec;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberDescriptor;
import io.github.jichuo1.meguviz.ref.api.descriptor.MemberKind;
import io.github.jichuo1.meguviz.ref.api.evidence.CodeIdentity;
import io.github.jichuo1.meguviz.ref.api.query.AccessPolicy;
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.api.query.Selection;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionStatus;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;
import io.github.jichuo1.meguviz.ref.spi.CandidateBatch;
import io.github.jichuo1.meguviz.ref.spi.CandidateProvider;
import io.github.jichuo1.meguviz.ref.spi.CandidateRequest;
import io.github.jichuo1.meguviz.ref.spi.ProviderDescriptor;
import io.github.jichuo1.meguviz.ref.spi.ProviderRegistry;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** Provider execution interruption must never be mistaken for an isolated waiter. */
public final class CapacityInterruptedProviderContractTest {
    public static final class Target {
        public int existing() { return 1; }
        public int target() { return 2; }
    }

    private static final ClassLoader LOADER = CapacityInterruptedProviderContractTest.class.getClassLoader();

    private static final class Context implements BackendContext {
        @Override public String processName() { return "capacity-interrupt-test"; }
        @Override public ClassLoader classLoader() { return LOADER; }
        @Override public long codeEpoch() { return 1; }
    }

    private static final class Provider implements CandidateProvider {
        int discoveries;
        @Override public ProviderDescriptor descriptor() {
            if (discoveries > 0) Thread.currentThread().interrupt();
            return new ProviderDescriptor("p", "1", 1, 0, "", Collections.emptyList());
        }
        @Override public CandidateBatch discover(CandidateRequest request) {
            discoveries++;
            return new CandidateBatch(Collections.singletonList(
                    MemberDescriptor.method(Target.class.getName(), "target", "I")),
                    request.codeFingerprint(), "fixture", true);
        }
        @Override public void close() { }
    }

    private static TargetSpec.DescriptorTarget target(String name) {
        return TargetSpec.descriptor(TargetKind.METHOD, Target.class.getName(), name)
                .parameters().returning("int").build();
    }

    @Test public void interruptedProviderWithFullCapacityDoesNotImpersonateWaiter() throws Exception {
        boolean previouslyInterrupted = Thread.interrupted();
        Provider provider = new Provider();
        MeguvizRefResolverSession session = null;
        try {
            session = MeguvizRefResolverSession.withQueries(new Context(), loader -> {
                CodeScope scope = CodeScope.open(loader, AccessPolicy.PRESERVE_JAVA_ACCESS, 1);
                scope.installProviders(ProviderRegistry.create(Collections.singletonList(provider)),
                        CodeIdentity.strong(Collections.singletonMap("f", "a".repeat(64)),
                                "fixture", QuerySpec.RULE_VERSION));
                return scope;
            }, (requested, owner) -> {
                QuerySpec.Builder query = QuerySpec.builder(owner, MemberKind.METHOD)
                        .name(requested.memberName()).selection(Selection.ALL).resultLimit(16);
                if ("target".equals(requested.memberName())) query.provider("p");
                return query.build();
            }, MeguvizRefResolverSession.WaiterInterruptionPolicy.ISOLATE_VERIFIED_WAITER);

            assertEquals(1, session.resolve(target("existing"), LOADER).size());
            try {
                session.resolve(target("target"), LOADER);
                fail("incomplete resolution was accepted");
            } catch (MeguvizRefResolutionException failure) {
                ResolutionStatus status = failure.receipt().status();
                String reason = failure.receipt().reason();
                assertTrue("unexpected Ref outcome: " + status + "/" + reason,
                        (status == ResolutionStatus.PARTIAL && "binding_capacity".equals(reason))
                                || (status == ResolutionStatus.CANCELLED && "interrupted".equals(reason)));
                assertEquals(MeguvizRefResolverSession.State.CLOSED, session.report().state());
                assertTrue(Thread.currentThread().isInterrupted());
                assertEquals(1, provider.discoveries);
            }
        } finally {
            Thread.interrupted();
            try {
                if (session != null) session.close();
            } finally {
                Thread.interrupted();
                if (previouslyInterrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
