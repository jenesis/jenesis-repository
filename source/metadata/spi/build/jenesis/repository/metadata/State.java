package build.jenesis.repository.metadata;

import module java.base;

/**
 * The tri-state a contributor's section envelope carries - the load-bearing distinction the consolidation
 * preserves and extends. Today's {@code present-but-empty} ("inspected, none declared") versus {@code absent}
 * ("never derived") - the distinction {@code LicenseInventory} depends on - maps to {@link #EMPTY} versus a section
 * that is simply not in the document. The third state, {@link #ERROR}, is the durable half of the derive-failure
 * contract: an inspector that could not parse an artifact records {@code error} on its own section, degrading alone
 * rather than blanking the coordinate.
 */
public enum State {

    /** The contributor derived this section and its {@code data} stands - the normal case. */
    DERIVED("derived"),

    /** Inspected, nothing to declare: present-but-empty, distinct from a section that was never derived (absent).
     *  The "inspected, none declared" half of the tri-state. */
    EMPTY("empty"),

    /** The derive attempt failed durably - the persisted half of the derive-failure contract; the envelope carries
     *  a {@link SectionError} explaining what could not be parsed, so the section degrades alone. */
    ERROR("error");

    private final String wire;

    State(String wire) {
        this.wire = wire;
    }

    /** The lowercase token this state serialises as in the envelope's {@code state} field. */
    public String wire() {
        return wire;
    }

    /** The state a wire token names, defaulting to {@link #DERIVED} for an absent or unrecognised token - a
     *  best-effort typed view: {@code state} is advisory for rendering and gating, and an unmutated section always
     *  round-trips through its raw node regardless of how its state parsed. */
    public static State ofWire(String wire) {
        for (State state : values()) {
            if (state.wire.equals(wire)) {
                return state;
            }
        }
        return DERIVED;
    }
}
