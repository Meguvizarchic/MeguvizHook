package io.github.jichuo1.meguviz.hook.api;

/**
 * Parameter shape requested at install time and frozen into the bound plan. The dispatcher never
 * re-resolves the shape inside a hot callback.
 *
 * <ul>
 *   <li>{@link #NO_ARGS}: the SDK does not materialize or read arguments. This says nothing about
 *       arrays or boxing the underlying framework may perform internally.</li>
 *   <li>{@link #READ_ARGS}: borrowed read access, valid on the invoking thread during the callback.</li>
 *   <li>{@link #ENTRY_SNAPSHOT}: shallow slot snapshot taken at entry; elements are shared, never
 *       deep-copied, and internal mutations of argument objects are not rolled back.</li>
 *   <li>{@link #MUTABLE_ARGS}: validated writes that become visible downstream.</li>
 *   <li>{@link #AROUND}: continuation access; exclusive with before/after callbacks in this version.</li>
 *   <li>{@link #READ_ARGS_VIEW}: borrowed read-only argument view captured at entry. The SDK does
 *       not request an argument array from an adapter that provides a view. It is not an entry
 *       snapshot; the original arguments are passed downstream without array substitution.</li>
 * </ul>
 */
public enum ParameterAccess {
    NO_ARGS,
    READ_ARGS,
    ENTRY_SNAPSHOT,
    MUTABLE_ARGS,
    AROUND,
    READ_ARGS_VIEW
}
