# Kotlin Hook DSL

The DSL delegates to the same `HookSpec` and `HookEnvironment` contracts as the Java API. It does
not create a backend, choose a framework, hide an installation failure, or automatically own a
registration's lifecycle.

When several hooks belong to one module, reuse an explicit owner and pass an already resolved
method directly:

```kotlin
val hooks = environment.forOwner("my-module")
val receipt = hooks.install("feature.title", resolvedMethod) {
    replaceConstant("")
}
when {
    receipt.isInstalled -> {
        val owned = requireNotNull(receipt.registration())
        // Retain owned for eventual close.
    }
    receipt.isSatisfied -> {
        // Identical registration already existed; retain the handle owned from its installation.
    }
    else -> {
        // Inspect status; a partial registration can still need cleanup.
    }
}
```

`isSatisfied` reports the installation outcome at its check, not current liveness. An
`ALREADY_INSTALLED` receipt does not carry a registration, create a generation, or force a
framework replacement; do not use it as proof of new handle ownership or fresh replacement.
This helper is in the current development source, not the released 1.0.0 API. Consumers remaining
on 1.0.0 can check for `INSTALLED` or `ALREADY_INSTALLED` explicitly, with the same ownership rule.

When a new generation is required even for an equivalent single-target spec, use
`hooks.forceReplace(id, resolvedMethod) { ... }` and inspect its receipt. It
retains the ordinary replacement checks and never falls back to close-and-install; the new
registration owns the replacement. This entry point is also in development source only.

Use `hooks.replace(id, target) { ... }` for an explicit single-target replacement, or
`hooks.spec(id, target) { ... }` to construct a spec without installing it. These operations return
the same specs and receipts as the Java API; `HookOwnerScope` does not close the environment or
automatically retry uncertain operations. Preserve and reuse callback objects when relying on
idempotent installation, including any `MatchObserver` used by a conditional replacement. Constant
and conditional result values are also compared by object identity; reuse the same result object
when repeating an equivalent installation. The scope keeps a strong reference to its environment; release the scope
when it is no longer needed.

If a caller already holds a `HookId`, `HookOwner`, and resolved member, the typed overload can
construct a spec without converting those identities to strings and rebuilding them:

```kotlin
val spec = hook(hookId, hookOwner, resolvedMethod) {
    replaceConstant("")
}
val receipt = environment.install(spec)
```

The target may also be a `TargetSpec`. Both overloads use the normal Java builder, retain the
supplied callback objects, and leave installation, replacement and receipt handling explicit.

When an after callback only needs the result, the default `NO_ARGS` shape needs no argument access:

```kotlin
val receipt = hooks.install("feature.label", resolvedStringMethod) {
    after { _, result ->
        if (result.hasReturned() && result.getResult() == "old") result.setResult("new")
    }
}
```

`NO_ARGS` prevents the SDK from requesting argument data from its backend; it does not promise that
the framework or runtime makes no allocations. Select an appropriate readable shape explicitly if
the callback needs arguments, and continue to inspect the installation receipt and close report.

`READ_ARGS_VIEW` captures a borrowed view at entry whose slots the adapter must keep stable during
the invocation. The view does not deep-copy argument objects, and it is not a promise that the
framework allocates nothing: for example, the examined Vector implementation copies its argument
slots when producing its immutable list. A later per-index framework read can observe a different
value if the underlying slots change, so it is not an equivalent replacement for this shape.

For descriptor-based lookup, types can be written as class tokens:

```kotlin
val target = method(ExampleTarget::class, "compute") {
    parameterTypes(Int::class, String::class)
    returning(Int::class)
}
val receipt = hooks.install("feature.compute", target) {
    replaceResultOnSuccess(42)
}
```

`Class` / `KClass` tokens contribute **names** only: the actual owner and parameter types are
resolved or matched against the backend's current loader at installation, so the tokens do not
pin class identity. Use a resolved `Method` or `Constructor` when exact reflective identity is
required. Omitting `parameterTypes` matches any parameter list; `parameterTypes()` explicitly
matches zero arguments. Kotlin `Int::class` matches primitive `int`; to match boxed
`java.lang.Integer`, use `parameterTypes(Int::class.javaObjectType)` or
`returning(Int::class.javaObjectType)`. In the Class-based overload, all type arguments must be
`Class` values. `returning(Unit::class)` denotes `kotlin.Unit`, not JVM `void`; use
`returning(Void.TYPE)` for `void`.

Owner reuse and descriptor conversion happen during spec construction, not once per intercepted
call. The DSL does not adapt or wrap the `BeforeCallback`, `AfterCallback` or `AroundCallback`
supplied by the caller (the typed variants below add one narrowing adapter, created once at spec
construction); the framework and core may still allocate in their own paths. No relative
performance improvement or device compatibility follows from this syntax alone.

### Typed argument callbacks (development source)

`beforeArgs`, `beforeMutableArgs` and `afterArgs` hand the callback a `ReadArgsInvocation` or
`MutableArgsInvocation` directly, so no cast and no separate `access(...)` call is needed:

```kotlin
hooks.install("feature.title", resolvedMethod) {
    beforeArgs { log(it.getArgument(0)) }                 // selects READ_ARGS_VIEW
    beforeMutableArgs { it.setArgument(0, "rewritten") }  // widens the shape to MUTABLE_ARGS
    afterArgs { invocation, result -> audit(invocation.getArgument(0), result.result) }
}
```

Without an explicit `access(...)`, read-only typed callbacks select `READ_ARGS_VIEW` and any
`beforeMutableArgs` selects `MUTABLE_ARGS`. An explicit `access(...)` (in any order) is kept, and
`build` fails when it cannot serve the typed callbacks: read callbacks need `READ_ARGS`,
`READ_ARGS_VIEW`, `ENTRY_SNAPSHOT` or `MUTABLE_ARGS`; mutable callbacks need `MUTABLE_ARGS`.
These methods wrap the supplied callback in a small adapter that only narrows the invocation type;
callback order, borrowing and exception rules are unchanged. Reusing the same typed callback object
still counts as the same callback for idempotent installation. The Java builder offers the same
three methods. They are in the current development source, not in the released 1.0.0 API.

For callbacks that read optional slots, `argumentOrNull` avoids repetitive bounds checks:

```kotlin
val receipt = hooks.install("feature.key", resolvedMethod) {
    beforeArgs { invocation ->
        val key = invocation.argumentOrNull(0) as? String
        // Use key inside this callback; retain neither the borrowed invocation nor its view.
    }
}
```

Import `argumentOrNull` from `io.github.jichuo1.meguviz.hook.dsl`. The helper checks the declared parameter count and returns
null for a negative or out-of-range index; a null value in an existing slot is also returned as
null. It still checks the borrowed invocation on invalid indexes, and exceptions while reading an
in-range slot propagate. It does not create an argument snapshot or adapt the callback. As with all
borrowed views, use it on the callback's owner thread and only before that callback returns.
