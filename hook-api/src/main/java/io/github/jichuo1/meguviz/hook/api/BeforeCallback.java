package io.github.jichuo1.meguviz.hook.api;

/** Before callback. Failures follow the spec's {@link ErrorPolicy}; see CONTRACT-001 §3. */
public interface BeforeCallback {
    void before(HookInvocation invocation) throws Throwable;
}
