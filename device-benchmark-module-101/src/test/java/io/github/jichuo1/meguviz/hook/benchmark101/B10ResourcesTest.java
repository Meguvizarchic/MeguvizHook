package io.github.jichuo1.meguviz.hook.benchmark101;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** Runs the production parser and bounded reader against injectable process files and clocks. */
public class B10ResourcesTest {
    static final class Source implements B10Resources.Source, B10Resources.Clock {
        byte[] status = bytes("Pid:\t123\nVmRSS:\t17 kB\nThreads:\t3\n");
        List<String> names = Arrays.asList("0", "1", "2", "7");
        final List<String> checked = new ArrayList<>();
        String vanished = "7", badFd;
        boolean statusUnavailable, listUnavailable, closed, closeFailure, noProgress;
        int bytesRead, clocks;
        long start = 10, end = 20;
        Throwable clockFailure, sourceFatal;
        @Override public InputStream openStatus() throws IOException {
            if (sourceFatal instanceof Error) throw (Error) sourceFatal;
            if (statusUnavailable) throw new IOException("status unavailable");
            return new ByteArrayInputStream(status) {
                @Override public synchronized int read(byte[] buffer, int offset, int length) {
                    if (noProgress) return 0;
                    int read = super.read(buffer, offset, length);
                    if (read > 0) bytesRead += read;
                    return read;
                }
                @Override public void close() throws IOException { closed = true; if (closeFailure) throw new IOException("close"); }
            };
        }
        @Override public List<String> fdNames() throws IOException {
            if (listUnavailable) throw new IOException("list unavailable");
            return names;
        }
        @Override public void checkFd(String name) throws IOException {
            checked.add(name);
            if (name.equals(vanished)) throw new NoSuchFileException(name);
            if (name.equals(badFd)) throw new IOException("attribute unavailable");
        }
        @Override public long wall() {
            clocks++;
            if (clocks == 1 && clockFailure != null) {
                if (clockFailure instanceof Error) throw (Error) clockFailure;
                throw (RuntimeException) clockFailure;
            }
            return clocks == 1 ? start : end;
        }
        B10Resources.Snapshot read() { return new B10Resources(123, this, this).read(); }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

    @Test public void readsPidBoundRssInBytesAndChecksEveryFdWithoutBlindSubtraction() {
        Source s = new Source(); B10Resources.Snapshot value = s.read();
        assertEquals(Long.valueOf(17 * 1024), value.rssBytes); assertEquals("observed", value.rssStatus);
        assertEquals(Long.valueOf(3), value.threadCount); assertEquals("observed", value.threadStatus);
        assertEquals(Long.valueOf(3), value.fdCount); assertEquals("observed", value.fdStatus);
        assertEquals(s.names, s.checked); assertTrue(s.closed); assertNull(value.failure);
        s = new Source(); s.vanished = null; assertEquals(Long.valueOf(4), s.read().fdCount);
    }
    @Test public void pidMismatchMissingOrDuplicatePidInvalidatesOnlyStatusMetrics() {
        for (String status : new String[]{"Pid: 124\nVmRSS: 1 kB\nThreads: 2\n", "VmRSS: 1 kB\nThreads: 2\n",
                "Pid: 123\nPid: 123\nVmRSS: 1 kB\nThreads: 2\n", "Pid: -123\nVmRSS: 1 kB\nThreads: 2\n"}) {
            Source s = new Source(); s.status = bytes(status); B10Resources.Snapshot value = s.read();
            assertNull(value.rssBytes); assertNull(value.threadCount); assertEquals("unavailable", value.rssStatus);
            assertEquals("unavailable", value.threadStatus); assertEquals("observed", value.fdStatus); assertNull(value.failure);
        }
    }
    @Test public void malformedMissingDuplicateOverflowAndWrongUnitsRemainUnavailablePerMetric() {
        for (String rss : new String[]{"", "VmRSS: -1 kB\n", "VmRSS: 4 KB\n", "VmRSS: 4 B\n",
                "VmRSS: 4 kB\nVmRSS: 4 kB\n", "VmRSS: 9007199254740992 kB\n", "VmRSS: 9223372036854775808 kB\n"}) {
            Source s = new Source(); s.status = bytes("Pid: 123\nThreads: 2\n" + rss);
            B10Resources.Snapshot value = s.read(); assertNull(value.rssBytes); assertEquals("unavailable", value.rssStatus);
            assertEquals(Long.valueOf(2), value.threadCount);
        }
        for (String threads : new String[]{"", "Threads: -1\n", "Threads: 0\n", "Threads: 2147483648\n", "Threads: 1.0\n", "Threads: 2\nThreads: 2\n", "Threads: 9223372036854775808\n"}) {
            Source s = new Source(); s.status = bytes("Pid: 123\nVmRSS: 1 kB\n" + threads);
            B10Resources.Snapshot value = s.read(); assertNull(value.threadCount); assertEquals("unavailable", value.threadStatus);
            assertEquals(Long.valueOf(1024), value.rssBytes);
        }
    }
    @Test public void actualZerosRemainObservedAndAreDistinguishableFromMissingReads() {
        Source s = new Source(); s.status = bytes("Pid: 123\nVmRSS: 0 kB\nThreads: 1\n"); s.names = Arrays.asList("7");
        B10Resources.Snapshot value = s.read(); assertEquals(Long.valueOf(0), value.rssBytes);
        assertEquals(Long.valueOf(1), value.threadCount); assertEquals(Long.valueOf(0), value.fdCount);
        s = new Source(); s.statusUnavailable = true; s.listUnavailable = true; value = s.read();
        assertNull(value.rssBytes); assertNull(value.threadCount); assertNull(value.fdCount); assertNull(value.failure);
    }
    @Test public void boundedStatusReaderClosesOnOversizeNoProgressAndCloseFailure() {
        for (String fault : new String[]{"oversize", "no_progress", "close"}) {
            Source s = new Source();
            if (fault.equals("oversize")) s.status = new byte[B10Resources.STATUS_LIMIT + 100];
            s.noProgress = fault.equals("no_progress"); s.closeFailure = fault.equals("close");
            B10Resources.Snapshot value = s.read(); assertTrue(s.closed); assertTrue(s.bytesRead <= B10Resources.STATUS_LIMIT);
            assertNull(value.rssBytes); assertNull(value.threadCount); assertNull(value.failure); assertEquals("observed", value.fdStatus);
        }
    }
    @Test public void statusJustBelowLimitIsAcceptedAndExactLimitIsUnavailableWithoutAnExtraRead() {
        Source s = new Source(); byte[] full = new byte[B10Resources.STATUS_LIMIT - 1]; Arrays.fill(full, (byte)' ');
        System.arraycopy(s.status, 0, full, 0, s.status.length); s.status = full;
        B10Resources.Snapshot value = s.read(); assertEquals(Long.valueOf(17 * 1024), value.rssBytes);
        assertTrue(s.closed); assertEquals(B10Resources.STATUS_LIMIT - 1, s.bytesRead);
        s = new Source(); s.status = Arrays.copyOf(full, B10Resources.STATUS_LIMIT); value = s.read();
        assertNull(value.rssBytes); assertNull(value.threadCount); assertTrue(s.closed); assertEquals(B10Resources.STATUS_LIMIT, s.bytesRead);
    }
    @Test public void fdAttributeErrorsDuplicatesMalformedNamesAndOversizeInvalidateWholeCount() {
        for (String fault : new String[]{"attribute", "duplicate", "path", "oversize", "empty", "null"}) {
            Source s = new Source();
            if (fault.equals("attribute")) s.badFd = "1";
            if (fault.equals("duplicate")) s.names = Arrays.asList("0", "0");
            if (fault.equals("path")) s.names = Arrays.asList("../1");
            if (fault.equals("empty")) s.names = new ArrayList<>();
            if (fault.equals("null")) s.names = null;
            if (fault.equals("oversize")) { s.names = new ArrayList<>(); for (int i = 0; i <= B10Resources.FD_LIMIT; i++) s.names.add(String.valueOf(i)); }
            B10Resources.Snapshot value = s.read(); assertNull(value.fdCount); assertEquals("unavailable", value.fdStatus);
            assertNull(value.failure); assertEquals("observed", value.rssStatus);
            if (fault.equals("oversize") || fault.equals("path")) assertTrue(s.checked.isEmpty());
        }
    }
    @Test public void fdBoundIncludesExactly4096AndDisappearedEntriesAreIndividuallyExcluded() {
        Source s = new Source(); s.names = new ArrayList<>(); for (int i = 0; i < B10Resources.FD_LIMIT; i++) s.names.add(String.valueOf(i));
        assertEquals(Long.valueOf(B10Resources.FD_LIMIT - 1), s.read().fdCount);
        assertEquals(B10Resources.FD_LIMIT, s.checked.size());
    }
    @Test public void failedNegativeAndBackwardsClocksPreserveEndpointsAndMetrics() {
        for (String fault : new String[]{"missing", "negative", "backwards"}) {
            Source s = new Source();
            if (fault.equals("missing")) s.clockFailure = new IllegalStateException("clock");
            if (fault.equals("negative")) s.start = -1;
            if (fault.equals("backwards")) s.end = 9;
            B10Resources.Snapshot value = s.read(); assertNotNull(value.failure); assertEquals(Long.valueOf(s.end), value.endNs);
            if (fault.equals("missing")) assertNull(value.startNs); else assertEquals(Long.valueOf(s.start), value.startNs);
            assertEquals("observed", value.rssStatus); assertTrue(s.closed);
        }
        Source s = new Source(); s.start = s.end = 0; assertNull(s.read().failure);
    }
    @Test public void fatalResourceOrClockFailuresAreRetainedForRunnerPropagation() {
        for (boolean resource : new boolean[]{false, true}) {
            Source s = new Source(); OutOfMemoryError fatal = new OutOfMemoryError("controlled resource fixture");
            if (resource) s.sourceFatal = fatal; else s.clockFailure = fatal;
            B10Resources.Snapshot value = s.read(); assertSame(fatal, value.failure); assertEquals(Long.valueOf(20), value.endNs);
        }
    }
}
