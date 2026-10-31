package io.github.jichuo1.meguviz.hook.api;

/**
 * Shallow snapshot of the argument slots taken when this registration was entered. Later writes to
 * the live slots do not change the snapshot; the referenced objects themselves are shared, not
 * copied, so their internal mutations remain visible.
 */
public interface EntrySnapshotInvocation extends ReadArgsInvocation {
    Object getEntryArgument(int index);

    /** A new array on each call; slot values are the same references captured at entry. */
    Object[] entrySnapshot();
}
