package io.github.jichuo1.meguviz.hook.benchmark;

import io.github.jichuo1.meguviz.hook.api.BackendCapabilities;
import io.github.jichuo1.meguviz.hook.api.CloseReport;
import io.github.jichuo1.meguviz.hook.core.BoundPlan;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.spi.BackendCloseReport;
import io.github.jichuo1.meguviz.hook.core.spi.BackendContext;
import io.github.jichuo1.meguviz.hook.core.spi.BackendHandle;
import io.github.jichuo1.meguviz.hook.core.spi.HookBackend;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;

import java.util.ArrayList;
import java.util.List;

/**
 * Benchmark-only backend: reuses the fake backend's declared capabilities and context, but hands the
 * bound plan and dispatch channel to the benchmark so it can drive the channel the way a framework
 * hooker would, without the fake backend's per-call bookkeeping.
 */
public final class CapturingBackend implements HookBackend {
    private final FakeBackend capabilitiesSource = FakeBackend.create();
    private final List<BoundPlan> plans = new ArrayList<BoundPlan>();
    private final List<DispatchChannel> channels = new ArrayList<DispatchChannel>();

    @Override public String id() { return capabilitiesSource.id(); }

    @Override public BackendCapabilities capabilities() { return capabilitiesSource.capabilities(); }

    @Override public BackendContext context() { return capabilitiesSource.context(); }

    @Override
    public BackendHandle install(BoundPlan plan, DispatchChannel channel) {
        plans.add(plan);
        channels.add(channel);
        return () -> new BackendCloseReport(CloseReport.EntryRestoration.RESTORED,
                CloseReport.PhysicalRelease.NOT_CLAIMED, false, "benchmark capture");
    }

    public BoundPlan lastPlan() { return plans.get(plans.size() - 1); }

    public DispatchChannel lastChannel() { return channels.get(channels.size() - 1); }
}
