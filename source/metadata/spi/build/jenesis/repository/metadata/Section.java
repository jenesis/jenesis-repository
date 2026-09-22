package build.jenesis.repository.metadata;

import module java.base;
import module tools.jackson.databind;

/**
 * One contributor's tagged section envelope inside the consolidated document (§5.1): the uniform wrapper every
 * subsection shares - {@code schema} (the contributor-owned section version), {@code updated} (this section's own
 * freshness instant, replacing the scattered per-subsystem {@code lastScanned} stamps), {@code state}
 * ({@link State#DERIVED}/{@link State#EMPTY}/{@link State#ERROR}), an optional {@link SectionError} (present iff
 * {@code state} is {@code error}), an optional {@link Signal} (severity or neutral), and the opaque {@code data}
 * payload the contributor owns.
 *
 * <p>The {@code data} is a raw {@link JsonNode}: the envelope is uniform and reader-tolerant, but the payload is
 * whatever the owning subsystem serialises (the findings section's {@code data} is exactly what the findings
 * ledger writes today), and a reader that does not own a section never parses its {@code data} - it carries the
 * whole section verbatim (see {@code MetadataDocument}). {@code data} may be {@code null}, which serialises as an
 * empty object.
 */
public record Section(String tag, int schema, Instant updated, State state, SectionError error, Signal signal,
                      JsonNode data) {

    public Section {
        if (tag == null || tag.isEmpty()) {
            throw new IllegalArgumentException("A section tag must be a non-empty string");
        }
        updated = Objects.requireNonNull(updated, "updated");
        state = Objects.requireNonNull(state, "state");
        signal = signal == null ? Signal.NEUTRAL : signal;
        if (state == State.ERROR && error == null) {
            throw new IllegalArgumentException("An error section must carry a SectionError: " + tag);
        }
        if (state != State.ERROR && error != null) {
            throw new IllegalArgumentException("Only an error section may carry a SectionError: " + tag);
        }
    }

    /** A derived section carrying a payload and an optional {@code signal} (neutral when {@code null}). */
    public static Section derived(String tag, int schema, Instant updated, Signal signal, JsonNode data) {
        return new Section(tag, schema, updated, State.DERIVED, null, signal, data);
    }

    /** An inspected-but-empty section - "present, nothing to declare", distinct from a section that was never
     *  derived (absent from the document). */
    public static Section empty(String tag, int schema, Instant updated) {
        return new Section(tag, schema, updated, State.EMPTY, null, Signal.NEUTRAL, null);
    }

    /** A durably-failed section - the persisted derive-failure, degrading alone. */
    public static Section error(String tag, int schema, Instant updated, SectionError error) {
        return new Section(tag, schema, updated, State.ERROR, Objects.requireNonNull(error, "error"),
                Signal.NEUTRAL, null);
    }

    /** The payload, absent when this section carries none (a {@link State#EMPTY} or {@link State#ERROR} section, or
     *  a derived one written without data). */
    public Optional<JsonNode> payload() {
        return data == null || data.isMissingNode() || data.isNull() ? Optional.empty() : Optional.of(data);
    }
}
