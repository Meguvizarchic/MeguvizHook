package consumer.refbridge;

import io.github.jichuo1.meguviz.hook.core.spi.MemberResolver;
import io.github.jichuo1.meguviz.hook.ref.MeguvizRefMemberResolver;

/** Compile-time Android consumer; never installed or launched by this verification. */
public final class Consumer {
    private Consumer() { }

    public static MemberResolver resolver() {
        return new MeguvizRefMemberResolver();
    }
}
