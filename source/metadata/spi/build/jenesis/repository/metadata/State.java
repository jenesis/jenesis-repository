package build.jenesis.repository.metadata;

import module java.base;

/**
 * The state a section envelope carries. "Inspected, none declared" is {@link #EMPTY}, distinct from a section absent
 * from the document ("never derived"), which licence inventory depends on; {@link #ERROR} records that an inspector
 * could not parse an artifact, so its section degrades alone.
 */
public enum State {

    /** The contributor derived this section and its {@code data} stands. */
    DERIVED("derived"),

    /** Inspected, nothing to declare - distinct from a section never derived (absent). */
    EMPTY("empty"),

    /** The derive failed durably; the envelope carries a {@link SectionError}, so the section degrades alone. */
    ERROR("error");

    private final String wire;

    State(String wire) {
        this.wire = wire;
    }

    /** The lowercase token this state serialises as in the envelope's {@code state} field. */
    public String wire() {
        return wire;
    }

    /** The state a wire token names, {@link #DERIVED} for an absent or unrecognised token. Best-effort: {@code state}
     *  is advisory for rendering and gating, and an unmutated section round-trips through its raw node whatever it
     *  parsed as. */
    public static State ofWire(String wire) {
        for (State state : values()) {
            if (state.wire.equals(wire)) {
                return state;
            }
        }
        return DERIVED;
    }
}
