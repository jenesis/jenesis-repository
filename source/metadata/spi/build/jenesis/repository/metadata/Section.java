package build.jenesis.repository.metadata;

import module java.base;
import module tools.jackson.databind;
import java.time.format.DateTimeParseException;

/**
 * One contributor's section envelope in the consolidated document: {@code schema} (the contributor's section version),
 * {@code updated} (this section's own freshness), {@code state} ({@link State#DERIVED}/{@link State#EMPTY}/
 * {@link State#ERROR}), a {@link SectionError} present iff the state is {@code error}, an optional {@link Signal}, and
 * the opaque {@code data} the contributor owns.
 *
 * <p>A reader that does not own a section never parses its {@code data}; it carries the whole section verbatim (see
 * {@code MetadataDocument}). {@code data} may be {@code null}, which serialises as an empty object.
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

    /** An inspected-but-empty section - "present, nothing to declare", distinct from one never derived (absent). */
    /** A payload field's text, or {@code null} when the field is absent, null or blank - how a section reads one it
     *  may never have written. */
    public static String text(JsonNode field) {
        if (field.isMissingNode() || field.isNull()) {
            return null;
        }
        String value = field.asString();
        return value.isBlank() ? null : value;
    }

    /** A payload field's instant, or {@code null} when the field is absent, null or not an ISO-8601 instant. */
    public static Instant instant(JsonNode field) {
        String value = text(field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    public static Section empty(String tag, int schema, Instant updated) {
        return new Section(tag, schema, updated, State.EMPTY, null, Signal.NEUTRAL, null);
    }

    /** A durably failed section, which degrades alone. */
    public static Section error(String tag, int schema, Instant updated, SectionError error) {
        return new Section(tag, schema, updated, State.ERROR, Objects.requireNonNull(error, "error"),
                Signal.NEUTRAL, null);
    }

    /** The payload, absent when the section carries none (an empty or error section, or a derived one without data). */
    public Optional<JsonNode> payload() {
        return data == null || data.isMissingNode() || data.isNull() ? Optional.empty() : Optional.of(data);
    }
}
