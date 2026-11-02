package io.github.jichuo1.meguviz.hook.core.spi;

/** Installation or resolution failure. From {@link HookBackend#install}, no entry was installed. */
public class BackendInstallException extends Exception {
    private static final long serialVersionUID = 1L;

    public BackendInstallException(String message) {
        super(message);
    }

    public BackendInstallException(String message, Throwable cause) {
        super(message, cause);
    }
}
