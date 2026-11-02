package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import io.github.jichuo1.meguviz.hook.core.JsonLineTraceTransport;
import java.io.OutputStream;
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
 */
public final class ModuleRemoteTraceWriter102 implements JsonLineTraceTransport.RecordWriter {
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
        Objects.requireNonNull(record, "record");
        if (record.length == 0 || record.length > maxRecordBytes) {
            throw new IllegalArgumentException("record size is outside the configured bound");
        }
        try (OutputStream stream = Objects.requireNonNull(opener.openForAppend(name), "append stream")) {
            stream.write(record);
            stream.flush();
        }
    }
}
