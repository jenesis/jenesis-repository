package build.jenesis.repository.metadata;

import module java.base;

/**
 * The durable record of why a contributor's derive failed, carried on a section whose {@link State} is
 * {@link State#ERROR}: a short machine-readable {@code kind} (e.g. {@code "parse"}) and a human-readable
 * {@code message}. This is the persistence the derive-failure contract needs - an inspector that catches a
 * "could not parse" writes {@code state:"error"} with this record on its own section, so a broken artifact
 * degrades that one subsection in the GUI and the gate instead of vanishing the whole coordinate.
 */
public record SectionError(String kind, String message) {

    public SectionError {
        kind = Objects.requireNonNull(kind, "kind");
        message = message == null ? "" : message;
    }
}
