package io.github.jichuo1.meguviz.hook.ref.fixture;

/** Test-only declaring class with a loader-sensitive signature. */
public class ShadowBase {
    public ShadowParam inherited(ShadowParam value) { return value; }
}
