package io.github.jichuo1.meguviz.hook.benchmark102;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded, read-only self-process observations; unavailable is never represented as zero. */
final class B10Resources {
    static final int STATUS_LIMIT = 64 * 1024;
    static final int FD_LIMIT = 4096;
    interface Clock { long wall(); }
    interface Source {
        InputStream openStatus() throws IOException;
        List<String> fdNames() throws IOException;
        void checkFd(String name) throws IOException;
    }
    static final class Snapshot {
        Long startNs, endNs, rssBytes, threadCount, fdCount;
        String rssStatus = "unavailable", threadStatus = "unavailable", fdStatus = "unavailable";
        Throwable failure;
    }

    /** Only these fixed self paths are read. FD targets and their contents are never read. */
    static final class ProcSource implements Source {
        private static final Path STATUS = Paths.get("/proc/self/status");
        private static final Path FDS = Paths.get("/proc/self/fd");
        @Override public InputStream openStatus() throws IOException { return Files.newInputStream(STATUS); }
        @Override public List<String> fdNames() throws IOException {
            List<String> names = new ArrayList<>();
            try (DirectoryStream<Path> directory = Files.newDirectoryStream(FDS)) {
                for (Path entry : directory) {
                    if (names.size() == FD_LIMIT) throw new IOException("self fd limit exceeded");
                    names.add(entry.getFileName().toString());
                }
            }
            return names;
        }
        @Override public void checkFd(String name) throws IOException {
            if (!decimal(name)) throw new IOException("invalid self fd entry");
            Files.readAttributes(FDS.resolve(name), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
    }

    private final long expectedPid;
    private final Source source;
    private final Clock clock;
    B10Resources(long expectedPid, Source source, Clock clock) {
        if (expectedPid <= 0 || source == null || clock == null) throw new IllegalArgumentException("invalid self resource source");
        this.expectedPid = expectedPid; this.source = source; this.clock = clock;
    }

    Snapshot read() {
        Snapshot value = new Snapshot();
        try { value.startNs = clock.wall(); }
        catch (Throwable failure) { value.failure = failure; }
        try { status(value); }
        catch (Exception unavailable) { /* Keep both status metrics unavailable. */ }
        catch (Error failure) { value.failure = B09Runner.combine(value.failure, failure); }
        try { fds(value); }
        catch (Exception unavailable) { /* An absent or untrustworthy count remains null. */ }
        catch (Error failure) { value.failure = B09Runner.combine(value.failure, failure); }
        try { value.endNs = clock.wall(); }
        catch (Throwable failure) { value.failure = B09Runner.combine(value.failure, failure); }
        if (value.startNs == null || value.endNs == null || value.startNs < 0 || value.endNs < 0
                || value.endNs < value.startNs) {
            value.failure = B09Runner.combine(value.failure, new IllegalStateException("B10 resource clock unavailable or backwards"));
        }
        return value;
    }

    private void status(Snapshot value) throws IOException {
        byte[] bytes;
        try (InputStream input = source.openStatus(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer, 0, Math.min(buffer.length, STATUS_LIMIT - output.size()))) != -1) {
                if (count == 0) throw new IOException("self status read made no progress");
                output.write(buffer, 0, count);
                if (output.size() == STATUS_LIMIT) throw new IOException("self status limit reached");
            }
            bytes = output.toByteArray();
        }
        String pid = null, rss = null, threads = null;
        boolean repeatedPid = false, repeatedRss = false, repeatedThreads = false;
        for (String line : new String(bytes, StandardCharsets.US_ASCII).split("\n", -1)) {
            if (line.startsWith("Pid:")) { repeatedPid |= pid != null; pid = line.substring(4).trim(); }
            else if (line.startsWith("VmRSS:")) { repeatedRss |= rss != null; rss = line.substring(6).trim(); }
            else if (line.startsWith("Threads:")) { repeatedThreads |= threads != null; threads = line.substring(8).trim(); }
        }
        Long parsedPid = number(pid);
        if (repeatedPid || parsedPid == null || parsedPid != expectedPid) return;
        if (!repeatedRss && rss != null && rss.matches("[0-9]+[ \\t]+kB")) {
            Long kb = number(rss.substring(0, rss.indexOf('k')).trim());
            if (kb != null && kb <= Long.MAX_VALUE / 1024) {
                value.rssBytes = kb * 1024; value.rssStatus = "observed";
            }
        }
        if (!repeatedThreads) {
            Long count = number(threads);
            if (count != null && count >= 1 && count <= Integer.MAX_VALUE) {
                value.threadCount = count; value.threadStatus = "observed";
            }
        }
    }

    private void fds(Snapshot value) throws IOException {
        List<String> names = source.fdNames();
        if (names == null || names.isEmpty() || names.size() > FD_LIMIT) throw new IOException("invalid self fd list");
        Set<String> seen = new HashSet<>();
        long count = 0;
        for (String name : names) {
            if (!decimal(name) || !seen.add(name)) throw new IOException("invalid self fd entry");
            try { source.checkFd(name); count++; }
            catch (NoSuchFileException disappeared) { /* Includes the now-closed enumeration FD. */ }
        }
        value.fdCount = count; value.fdStatus = "observed";
    }

    private static boolean decimal(String value) { return value != null && value.matches("[0-9]+"); }
    private static Long number(String value) {
        if (!decimal(value)) return null;
        try { return Long.valueOf(value); }
        catch (NumberFormatException malformed) { return null; }
    }
}
