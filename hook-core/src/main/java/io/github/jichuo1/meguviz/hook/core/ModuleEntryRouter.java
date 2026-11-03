package io.github.jichuo1.meguviz.hook.core;

import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.core.spi.MemberResolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit package-ready entry routing for a single process and module generation. Framework
 * adapters must supply the real process name, host package, host loader and epoch; this router
 * does not discover a framework, publish an Android component, or receive events by itself.
 *
 * <p>Package name and loader identity, not loader string representations, define each route.
 * Routes retain their loaders and environments until {@link #detachContexts()} is called after
 * lifecycle cleanup. Each route is attempted once; failures can follow partial installs and are
 * retained as uncertain outcomes, never silently retried. No hook is uninstalled or adopted here.
 */
public final class ModuleEntryRouter {
    public enum Status { READY, ALREADY_READY, IN_PROGRESS, FILTERED, FAILED, QUIESCING, CAPACITY_REACHED }

    /** Runtime facts as supplied by the framework entry; they are not authenticated by this class. */
    public static final class EntryInfo {
        private final String processName;
        private final String packageName;
        private final ClassLoader loader;
        private final long epoch;
        private EntryInfo(String processName, String packageName, ClassLoader loader, long epoch) {
            this.processName = processName; this.packageName = packageName; this.loader = loader; this.epoch = epoch;
        }
        public String processName() { return processName; }
        public String packageName() { return packageName; }
        public ClassLoader classLoader() { return loader; }
        public long epoch() { return epoch; }
    }

    public interface PackageFilter { boolean matches(EntryInfo info) throws Exception; }
    public interface BackendFactory { HookBackend create(EntryInfo info) throws Exception; }
    public interface EnvironmentFactory { HookEnvironment create(EntryInfo info) throws Exception; }
    public interface PackageHandler { void onReady(Context context) throws Exception; }

    /**
     * Context retained for the module's own shutdown or reload handling. The environment is never
     * implicitly closed; its caller must inspect and handle each hook registration's close result.
     */
    public static final class Context {
        private final EntryInfo info;
        private final HookEnvironment environment;
        private final ModuleLifecycle lifecycle;
        private Context(EntryInfo info, HookEnvironment environment, ModuleLifecycle lifecycle) {
            this.info = info; this.environment = environment; this.lifecycle = lifecycle;
        }
        public EntryInfo info() { return info; }
        public HookEnvironment environment() { return environment; }
        public ModuleLifecycle lifecycle() { return lifecycle; }
    }

    /** An immutable route result, including retained context after a potentially partial failure. */
    public static final class Result {
        private final Status status;
        private final EntryInfo info;
        private final Context context;
        private final Throwable failure;
        private Result(Status status, EntryInfo info, Context context, Throwable failure) {
            this.status = status; this.info = info; this.context = context; this.failure = failure;
        }
        public Status status() { return status; }
        public EntryInfo info() { return info; }
        public Context context() { return context; }
        public Throwable failure() { return failure; }
    }

    private static final class Route {
        final EntryInfo info;
        Status status = Status.IN_PROGRESS;
        Context context;
        Throwable failure;
        Route(EntryInfo info) { this.info = info; }
        Result result() { return new Result(status, info, context, failure); }
    }

    private final String processName;
    private final long epoch;
    private final int maxRoutes;
    private final ModuleLifecycle lifecycle;
    private final PackageFilter filter;
    private final EnvironmentFactory factory;
    private final PackageHandler handler;
    private final Map<ClassLoader, Map<String, Route>> routes =
            new IdentityHashMap<ClassLoader, Map<String, Route>>();
    private int routeCount;
    private boolean detached;

    public ModuleEntryRouter(String processName, long epoch, int maxRoutes, ModuleLifecycle lifecycle,
                             PackageFilter filter, EnvironmentFactory factory, PackageHandler handler) {
        this.processName = validName(processName, "processName");
        if (epoch < 0) throw new IllegalArgumentException("epoch must be nonnegative");
        if (maxRoutes < 1 || maxRoutes > 65536) throw new IllegalArgumentException("maxRoutes must be 1..65536");
        this.epoch = epoch;
        this.maxRoutes = maxRoutes;
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /**
     * Wraps an existing backend factory with strict process, loader identity and epoch checks.
     * A custom EnvironmentFactory can instead configure its own resolver and diagnostics.
     */
    public static EnvironmentFactory fromBackend(BackendFactory factory, MemberResolver resolver,
                                                  DiagnosticsSink diagnostics) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(diagnostics, "diagnostics");
        return info -> {
            HookBackend backend = Objects.requireNonNull(factory.create(info), "backend");
            BackendContext context = Objects.requireNonNull(backend.context(), "backend context");
            if (!info.processName().equals(context.processName()) || info.classLoader() != context.classLoader()
                    || info.epoch() != context.codeEpoch()) {
                throw new IllegalArgumentException("backend process, loader or epoch does not match package event");
            }
            return HookEnvironment.create(backend, resolver, diagnostics);
        };
    }

    /**
     * Routes one event on the calling thread. An event admitted before quiescence may finish;
     * cleanup will return BUSY until that entry's lease is released. Filter, factory and handler
     * run without the router monitor, so they must not wait on a concurrently blocked caller.
     * An exception is rethrown unchanged and the route remains FAILED for inspection.
     */
    public Result onPackageReady(String packageName, ClassLoader loader) throws Exception {
        EntryInfo info = new EntryInfo(processName, validName(packageName, "packageName"),
                Objects.requireNonNull(loader, "loader"), epoch);
        ModuleLifecycle.Lease lease = lifecycle.tryEnter();
        if (lease == null) return new Result(Status.QUIESCING, info, null, null);
        Route route;
        try {
            synchronized (this) {
                if (detached) return new Result(Status.QUIESCING, info, null, null);
                Map<String, Route> byPackage = routes.get(loader);
                Route old = byPackage == null ? null : byPackage.get(packageName);
                if (old != null) {
                    if (old.status == Status.READY) return new Result(Status.ALREADY_READY, old.info, old.context, null);
                    return old.result();
                }
                if (routeCount == maxRoutes) return new Result(Status.CAPACITY_REACHED, info, null, null);
                if (byPackage == null) { byPackage = new LinkedHashMap<String, Route>(); routes.put(loader, byPackage); }
                route = new Route(info);
                byPackage.put(packageName, route);
                routeCount++;
            }
            try {
                if (!filter.matches(info)) {
                    synchronized (this) {
                        Map<String, Route> byPackage = routes.get(loader);
                        byPackage.remove(packageName);
                        if (byPackage.isEmpty()) routes.remove(loader);
                        routeCount--;
                    }
                    return new Result(Status.FILTERED, info, null, null);
                }
                HookEnvironment environment = Objects.requireNonNull(factory.create(info), "environment");
                Context context = new Context(info, environment, lifecycle);
                synchronized (this) { route.context = context; }
                handler.onReady(context);
                synchronized (this) { route.status = Status.READY; return route.result(); }
            } catch (Exception failure) {
                synchronized (this) { route.status = Status.FAILED; route.failure = failure; }
                throw failure;
            } catch (Error failure) {
                synchronized (this) {
                    route.status = Status.FAILED;
                    // Fatal VM events escape immediately, not as ordinary retained diagnostics.
                    route.failure = TypeChecks.isFatal(failure) ? null : failure;
                }
                throw failure;
            }
        } finally {
            lease.close();
        }
    }

    public synchronized int routeCount() { return routeCount; }

    /** Snapshot all retained routes, including failed routes and any partially installed context. */
    public synchronized List<Result> snapshotContexts() {
        List<Result> result = new ArrayList<Result>(routeCount);
        for (Map<String, Route> byPackage : routes.values()) {
            for (Route route : byPackage.values()) result.add(route.result());
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns and removes retained routes after the lifecycle reached QUIESCED. This does not close
     * their environments or prove hook ownership was transferred: the caller receives every route
     * and remains responsible for installed, failed and potentially ambiguous hooks. Call only
     * after the caller has decided how to handle old-generation handles and retained contexts.
     */
    public List<Result> detachContexts() {
        if (lifecycle.state() != ModuleLifecycle.State.QUIESCED) {
            throw new IllegalStateException("lifecycle must be quiesced before detaching routes");
        }
        synchronized (this) {
            if (detached) return Collections.emptyList();
            List<Result> result = new ArrayList<Result>(routeCount);
            for (Map<String, Route> byPackage : routes.values()) {
                for (Route route : byPackage.values()) result.add(route.result());
            }
            routes.clear();
            routeCount = 0;
            detached = true;
            return Collections.unmodifiableList(result);
        }
    }

    private static String validName(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isEmpty() || value.length() > 255) throw new IllegalArgumentException(label + " length must be 1..255");
        return value;
    }
}
