package io.github.jichuo1.meguviz.hook.core.spi;

/** Installation failure reported by a backend, including an unsupported requested shape. */
public class BackendInstallException extends Exception {
    private static final long serialVersionUID = 1L;

    public BackendInstallException(String message) {
        super(message);
    }

    public BackendInstallException(String message, Throwable cause) {
        super(message, cause);
    }
}
