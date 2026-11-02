package io.github.jichuo1.meguviz.hook.backend.libxposed102;

import android.os.ParcelFileDescriptor;
import io.github.libxposed.api.XposedInterface;
import java.io.FileNotFoundException;
import java.util.Objects;

/**
 * Explicit injected-process access to the framework's remote file reader. Construction checks the
 * runtime API version only; it does not probe remote capability, connect, list or open files. The returned descriptor is read-only according
 * to the libxposed contract and is owned by the caller, who must close it.
 * Callers must construct and use it only after the framework has attached the module and invoked
 * {@code onModuleLoaded}; API version alone does not establish {@code PROP_CAP_REMOTE}.
 *
 * <p>The module application/service side is separate. This class provides no write, upload,
 * forwarding, service discovery, reconnection, retry or file lifecycle operation.
 */
public final class RemoteFiles102 {
    private final XposedInterface framework;

    public RemoteFiles102(XposedInterface framework) {
        this.framework = Objects.requireNonNull(framework, "framework");
        if (framework.getApiVersion() < XposedInterface.API_102) {
            throw new IllegalArgumentException("libxposed API 102 required");
        }
    }

    /** Capability only; exceptions from the framework are propagated rather than treated as false. */
    public boolean isSupported() {
        return (framework.getFrameworkProperties() & XposedInterface.PROP_CAP_REMOTE) != 0;
    }

    /** The returned array is a private snapshot; unsupported or framework errors propagate. */
    public String[] list() {
        requireSupported();
        String[] names = framework.listRemoteFiles();
        if (names == null) throw new IllegalStateException("framework returned a null file list");
        return names.clone();
    }

    /** Opens one flat filename for reading; never opens or creates a writable target-side stream. */
    public ParcelFileDescriptor openReadOnly(String name) throws FileNotFoundException {
        validateName(name);
        requireSupported();
        ParcelFileDescriptor descriptor = framework.openRemoteFile(name);
        if (descriptor == null) throw new IllegalStateException("framework returned a null descriptor");
        return descriptor;
    }

    private void requireSupported() {
        if (!isSupported()) throw new UnsupportedOperationException("framework has no remote files capability");
    }

    static void validateName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("remote filename must be a non-empty flat filename");
        }
    }
}
