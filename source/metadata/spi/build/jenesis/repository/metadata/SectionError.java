package build.jenesis.repository.metadata;

import module java.base;

/**
 * Why a contributor's derive failed, carried on a section in {@link State#ERROR}: a short machine-readable {@code kind}
 * ({@code "parse"}) and a human-readable {@code message}. An inspector that cannot parse an artifact records this on
 * its own section, so that subsection degrades in the console and the gate rather than the whole coordinate vanishing.
 */
public record SectionError(String kind, String message) {

    public SectionError {
        kind = Objects.requireNonNull(kind, "kind");
        message = message == null ? "" : message;
    }
}
