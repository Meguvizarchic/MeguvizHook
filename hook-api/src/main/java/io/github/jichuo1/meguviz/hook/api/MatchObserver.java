package io.github.jichuo1.meguviz.hook.api;

/** A conditional replacement's optional match notification; it cannot mutate the hook outcome. */
@FunctionalInterface
public interface MatchObserver {
    void onMatch() throws Throwable;
}
