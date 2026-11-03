package io.github.jichuo1.meguviz.hook.benchmark.transport;

import io.github.jichuo1.meguviz.hook.core.BatchTraceTransport;
import io.github.jichuo1.meguviz.hook.core.CallbackExceptionTracker;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Round-2 same-run A/B of batch frame formation plus the hand-off to the record writer: round-1
 * sender copy against the current sender. records = events per frame (one stack frame each, no
 * message). The writer only consumes what it is given. Host JVM only; not ART evidence.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
public class AbBatchSendBenchmark {
    private static final int LIMIT = 65536;

    @Param({"round1", "current"})
    public String impl;

    @Param({"1", "16", "128"})
    public int records;

    private boolean round1;
    private List<CallbackExceptionTracker.Event> events;
    private Round1Transport.RecordWriter round1Writer;
    private CallbackExceptionTracker.BatchTransport sender;

    @Setup(Level.Trial)
    public void setUp(Blackhole sink) throws Exception {
        round1 = "round1".equals(impl);
        events = Collections.unmodifiableList(TraceFixtures.events(records, null, 6));
        round1Writer = sink::consume;
        sender = BatchTraceTransport.sender(sink::consume, false, 1, LIMIT, 1024);
        List<byte[]> a = new ArrayList<byte[]>();
        List<byte[]> b = new ArrayList<byte[]>();
        Round1Transport.sendBatch(a::add, events, false, 1, LIMIT, 1024);
        BatchTraceTransport.sender(b::add, false, 1, LIMIT, 1024).sendBatch(events);
        if (!Arrays.equals(a.get(0), b.get(0))) throw new IllegalStateException("senders disagree");
    }

    @Benchmark
    public int sendBatch() throws Exception {
        return round1 ? Round1Transport.sendBatch(round1Writer, events, false, 1, LIMIT, 1024)
                : sender.sendBatch(events);
    }
}
