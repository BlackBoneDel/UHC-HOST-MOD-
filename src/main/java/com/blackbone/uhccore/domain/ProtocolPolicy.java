package com.blackbone.uhccore.domain;

/** The native server protocol is queried from Paper at runtime, never guessed from a client version. */
public record ProtocolPolicy(Mode mode, int minimum, int maximum) {
    public static final int OLDEST_SUPPORTED = 755; // Java 1.17: full-height world support

    public enum Mode { ESTRICTO, FLEXIBLE }

    public ProtocolPolicy {
        if (mode == null || minimum < OLDEST_SUPPORTED || minimum > maximum) {
            throw new IllegalArgumentException("Invalid protocol range");
        }
        if (mode == Mode.ESTRICTO && minimum != maximum) {
            throw new IllegalArgumentException("Ranked play must use a single protocol");
        }
    }

    public static ProtocolPolicy resolve(Mode requested, int floor, int nativeProtocol,
                                         boolean viaVersion, boolean viaBackwards) {
        if (nativeProtocol < OLDEST_SUPPORTED) throw new IllegalArgumentException("Unsupported native protocol");
        if (!viaVersion || requested == Mode.ESTRICTO) {
            return new ProtocolPolicy(Mode.ESTRICTO, nativeProtocol, nativeProtocol);
        }
        int effectiveFloor = viaBackwards ? Math.max(OLDEST_SUPPORTED, Math.min(floor, nativeProtocol)) : nativeProtocol;
        return new ProtocolPolicy(Mode.FLEXIBLE, effectiveFloor, nativeProtocol);
    }

    public boolean accepts(int protocol) {
        return protocol >= minimum && protocol <= maximum;
    }
}
