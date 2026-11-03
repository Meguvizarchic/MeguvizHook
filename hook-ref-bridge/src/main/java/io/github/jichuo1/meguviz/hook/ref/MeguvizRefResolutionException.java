package io.github.jichuo1.meguviz.hook.ref;

import io.github.jichuo1.meguviz.hook.core.spi.BackendInstallException;
import io.github.jichuo1.meguviz.ref.api.evidence.ResolutionReceipt;
import java.util.Objects;

/**
 * An incomplete MeguvizRef query, with the diagnostic receipt supplied by Ref. The receipt
 * describes its visible universe and does not establish the presence or absence of a member
 * outside it. Ref records a failure type, not the original provider throwable.
 */
public final class MeguvizRefResolutionException extends BackendInstallException {
    private static final long serialVersionUID = 1L;

    // Ref's receipt is not Serializable. Do not make serializing an exception fail or silently
    // claim that deserialization restored the original diagnostic information.
    private final transient ResolutionReceipt receipt;

    MeguvizRefResolutionException(String message, ResolutionReceipt receipt) {
        super(message);
        this.receipt = Objects.requireNonNull(receipt, "receipt");
    }

    /** The Ref receipt, or null after this exception has been deserialized. */
    public ResolutionReceipt receipt() { return receipt; }
}
