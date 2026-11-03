package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.core.BatchTraceTransport;
import io.github.jichuo1.meguviz.hook.core.JsonLineTraceTransport;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;

/**
 * Explicit module-service-side writer backed by a caller-supplied, append-positioned stream.
 * The caller may adapt an already-connected XposedService; this class does not depend on, obtain,
 * reconnect or discover that service, and does not provide an injected-process upload channel.
 * Constructing it does not open or mutate a file. Invoke only on the caller's background worker.
 *
 * <p>The opener owns append positioning and cross-process coordination. Neither opening nor
 * write/flush/close proves atomic append or durable storage. A failed write can have partially
 * committed; an explicit retry may duplicate bytes. The opener must not truncate existing data.
 *
 * <p>As a {@link BatchTraceTransport.BatchWriter} (for example behind
 * {@link BatchTraceTransport#receiver}) one batch uses one opened stream instead of one per record.
 * As a {@link JsonLineTraceTransport.SlicedRecordWriter} (for example behind a module-side
 * {@link JsonLineTraceTransport}) a record slice is written straight from the sender's buffer.
 */
public final class ModuleRemoteTraceWriter102
        implements JsonLineTraceTransport.SlicedRecordWriter, BatchTraceTransport.BatchWriter {
    /** Open a fresh stream already positioned for append; the writer will close the returned stream. */
    public interface AppendStreamOpener {
        OutputStream openForAppend(String name) throws Exception;
    }

    private final String name;
    private final int maxRecordBytes;
    private final AppendStreamOpener opener;

    public ModuleRemoteTraceWriter102(String name, int maxRecordBytes, AppendStreamOpener opener) {
        RemoteFiles102.validateName(name);
        if (maxRecordBytes < 1) throw new IllegalArgumentException("maxRecordBytes must be positive");
        this.name = name;
        this.maxRecordBytes = maxRecordBytes;
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    @Override
    public void writeRecord(byte[] record) throws Exception {
        checkRecord(record);
        try (OutputStream stream = Objects.requireNonNull(opener.openForAppend(name), "append stream")) {
            stream.write(record);
            stream.flush();
        }
    }

    /**
     * Same as {@link #writeRecord(byte[])} for the bytes {@code buffer[offset, offset + length)},
     * written with {@code OutputStream.write(buffer, offset, length)}. The slice is checked
     * against the buffer and the same bound before anything is opened; the buffer is not retained.
     */
    @Override
    public void writeRecord(byte[] buffer, int offset, int length) throws Exception {
        Objects.requireNonNull(buffer, "record");
        if (offset < 0 || length < 0 || offset > buffer.length - length) {
            throw new IndexOutOfBoundsException("record slice outside the buffer");
        }
        checkLength(length);
        try (OutputStream stream = Objects.requireNonNull(opener.openForAppend(name), "append stream")) {
            stream.write(buffer, offset, length);
            stream.flush();
        }
    }

    /**
     * Append every record of one batch, in order, through a single opened stream with one flush.
     * Every record is checked against the same bound first; if any is invalid nothing is opened.
     * The records are written as given (no concatenation copy). A failure after opening can leave a
     * prefix, possibly ending in a partial record, appended; retrying the batch may duplicate it.
     * Normal return does not prove atomic append or durable storage.
     */
    @Override
    public void writeBatch(List<byte[]> records) throws Exception {
        Objects.requireNonNull(records, "records");
        // One snapshot of the list so validation and writing see the same arrays.
        byte[][] batch = records.toArray(new byte[0][]);
        if (batch.length == 0) throw new IllegalArgumentException("record batch is empty");
        for (byte[] record : batch) checkRecord(record);
        try (OutputStream stream = Objects.requireNonNull(opener.openForAppend(name), "append stream")) {
            for (byte[] record : batch) stream.write(record);
            stream.flush();
        }
    }

    private void checkRecord(byte[] record) {
        Objects.requireNonNull(record, "record");
        checkLength(record.length);
    }

    private void checkLength(int length) {
        if (length == 0 || length > maxRecordBytes) {
            throw new IllegalArgumentException("record size is outside the configured bound");
        }
    }
}
