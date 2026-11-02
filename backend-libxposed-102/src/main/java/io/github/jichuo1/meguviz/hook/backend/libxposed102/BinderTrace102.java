package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import io.github.jichuo1.meguviz.hook.core.JsonLineTraceTransport;
import java.util.Objects;

/**
 * Optional record uplink over an already connected Binder. No component, manifest entry, service
 * discovery, threads, retry or file is created. Invoke the synchronous sender only on a caller-owned
 * background worker; it can block and is never suitable for a hook callback or application UI.
 *
 * <p>Each accepted transaction consults the authorizer with Binder's current calling UID before
 * decoding the payload. Local calls or nested Binder contexts may inherit a surrounding calling
 * identity; the publisher must account for this. UID validation identifies an app/user, not this
 * SDK: any code sharing that UID can send records.
 * In local calls and nested Binder transactions, getCallingUid reflects the current thread's
 * Binder calling context; it is not an independent proof of a remote SDK sender.
 * A caller must provide a fail-closed authorization policy appropriate for its own module.
 * Every received record is untrusted bytes: the receiver's writer must validate its format and
 * enforce rate, storage, concurrency, privacy and lifecycle limits before any persistence.
 *
 * <p>A normal return means only that the supplied RecordWriter returned normally. A rejected,
 * failed or interrupted transaction can have completed its side effect, including a partial write;
 * neither delivery nor persistence nor uniqueness is implied. No automatic retry is performed.
 * Protocol v2 sends a fixed SHA-256 fingerprint of the channel name's UTF-16 big-endian code units,
 * then the declared record length and Parcel byte array. The fingerprint checks only channel
 * agreement, never authorization. Both encoded length and exact four-byte-padded remainder are
 * checked before allocating the record; this layout is not yet verified on an Android runtime.
 */
public final class BinderTrace102 {
    private static final String DESCRIPTOR = "io.github.jichuo1.meguviz.hook.trace.record.v2";
    private static final int SUBMIT = IBinder.FIRST_CALL_TRANSACTION;
    private static final int VERSION = 2;

    private BinderTrace102() { }

    /** Must return false when UID identity or policy cannot be determined. */
    public interface UidAuthorizer {
        boolean isAllowed(int callingUid);
    }

    /** Synchronous sender to a Binder supplied by the caller; no connection is established here. */
    public static JsonLineTraceTransport.RecordWriter sender(IBinder endpoint, String name, int maxRecordBytes) {
        Objects.requireNonNull(endpoint, "endpoint");
        TraceRecordPolicy102 policy = new TraceRecordPolicy102(name, maxRecordBytes);
        byte[] fingerprint = policy.fingerprint();
        return record -> {
            Objects.requireNonNull(record, "record");
            policy.checkLength(record.length);
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(VERSION);
                data.writeByteArray(fingerprint);
                // Explicit outer length is checked before the receiver allocates its array;
                // readByteArray also checks the inner length written by writeByteArray.
                data.writeInt(record.length);
                data.writeByteArray(record);
                if (!endpoint.transact(SUBMIT, data, reply, 0)) {
                    throw new RemoteException("trace transaction rejected or unavailable; outcome unknown");
                }
                if (reply.dataSize() > TraceRecordPolicy102.PARCEL_OVERHEAD_BYTES) {
                    throw new RemoteException("oversized trace acknowledgement; outcome unknown");
                }
                reply.readException();
                if (reply.readInt() != 1 || reply.dataAvail() != 0) {
                    throw new RemoteException("invalid trace acknowledgement; outcome unknown");
                }
            } finally {
                reply.recycle();
                data.recycle();
            }
        };
    }

    /**
     * Creates an endpoint to be published by a caller-owned component. The writer is called on a
     * Binder thread and should only accept into a bounded queue; it must not block on file I/O.
     * Endpoint publication, endpoint identity and consent are the caller's responsibility. Local
     * JVM stub tests do not exercise Android Binder identity, parcel parsing or IPC; those require
     * a separately qualified Android runtime test.
     */
    public static IBinder receiver(String name, int maxRecordBytes, UidAuthorizer authorizer,
                                   JsonLineTraceTransport.RecordWriter writer) {
        TraceRecordPolicy102 policy = new TraceRecordPolicy102(name, maxRecordBytes);
        Objects.requireNonNull(authorizer, "authorizer");
        Objects.requireNonNull(writer, "writer");
        return new Binder() {
            @Override
            @SuppressWarnings("removal")
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code != SUBMIT || (flags & IBinder.FLAG_ONEWAY) != 0 || reply == null) return false;
                int uid = Binder.getCallingUid();
                if (uid < 0) return false;
                try {
                    if (!authorizer.isAllowed(uid)) return false;
                } catch (Throwable failure) {
                    if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
                    if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
                    return false;
                }
                policy.checkParcelSize(data.dataSize());
                data.enforceInterface(DESCRIPTOR);
                if (data.readInt() != VERSION) throw new IllegalArgumentException("unsupported trace protocol");
                // Fixed-size fingerprint is not an authentication token; UID policy is mandatory.
                if (data.dataAvail() < 36) throw new IllegalArgumentException("missing trace fingerprint");
                byte[] fingerprint = new byte[32];
                data.readByteArray(fingerprint);
                policy.checkFingerprint(fingerprint);
                if (data.dataAvail() < 4) throw new IllegalArgumentException("missing trace length");
                int length = data.readInt();
                policy.checkLength(length);
                if (data.dataAvail() < 4) throw new IllegalArgumentException("missing encoded trace length");
                int encodedPosition = data.dataPosition();
                int encoded = data.readInt();
                policy.checkEncodedArray(length, encoded, data.dataAvail());
                data.setDataPosition(encodedPosition);
                byte[] record = new byte[length];
                data.readByteArray(record);
                if (data.dataAvail() != 0) throw new IllegalArgumentException("trailing trace payload");
                try {
                    writer.writeRecord(record);
                } catch (Throwable failure) {
                    if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
                    if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
                    RemoteException remote = new RemoteException("trace writer failed; side effects unknown");
                    remote.initCause(failure);
                    throw remote;
                }
                reply.writeNoException();
                reply.writeInt(1);
                return true;
            }
        };
    }
}
