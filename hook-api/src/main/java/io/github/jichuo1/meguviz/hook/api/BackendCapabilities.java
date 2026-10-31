package io.github.jichuo1.meguviz.hook.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable capability snapshot published by a backend at construction time. Missing keys answer
 * {@link CapabilityResult.Status#UNKNOWN}; they are never promoted to supported.
 */
public final class BackendCapabilities {
    private final String backendId;
    private final ChainSemantics chainSemantics;
    private final Map<Capability, CapabilityResult> results;

    private BackendCapabilities(Builder builder) {
        this.backendId = builder.backendId;
        this.chainSemantics = builder.chainSemantics;
        this.results = Collections.unmodifiableMap(new LinkedHashMap<Capability, CapabilityResult>(builder.results));
    }

    public String backendId() {
        return backendId;
    }

    public ChainSemantics chainSemantics() {
        return chainSemantics;
    }

    public CapabilityResult lookup(Capability capability) {
        CapabilityResult result = results.get(capability);
        return result != null ? result : CapabilityResult.unknown(backendId, "capability not declared: " + capability);
    }

    public Map<Capability, CapabilityResult> asMap() {
        return results;
    }

    public static final class Builder {
        private final String backendId;
        private final ChainSemantics chainSemantics;
        private final Map<Capability, CapabilityResult> results = new LinkedHashMap<Capability, CapabilityResult>();

        public Builder(String backendId, ChainSemantics chainSemantics) {
            this.backendId = Objects.requireNonNull(backendId, "backendId");
            this.chainSemantics = Objects.requireNonNull(chainSemantics, "chainSemantics");
        }

        public Builder declare(Capability capability, CapabilityResult result) {
            results.put(Objects.requireNonNull(capability, "capability"), Objects.requireNonNull(result, "result"));
            return this;
        }

        public BackendCapabilities build() {
            return new BackendCapabilities(this);
        }
    }
}
