package build.jenesis.repository.dependency;

import module java.base;

/**
 * A CycloneDX BOM was present but could not be parsed: the document announced itself as JSON or XML (its first
 * non-whitespace token is <code>{</code>, <code>[</code> or <code>&lt;</code>) but did not decode, or its XML
 * dependency graph nests past the safe bound. This is the SBOM subsection's way of DISTINGUISHING "could not derive
 * this SBOM" from "genuinely no SBOM": {@link CycloneDxParser#parse} stays fail-soft (an empty graph for both), the
 * shape the reverse-dependency sweep relies on, while {@link CycloneDxParser#parseStrict} raises this on the served
 * view path so a reader sees a scoped error for the SBOM panel rather than an empty one that reads as "no
 * dependencies". A document that is simply not a BOM (neither JSON nor XML, or valid JSON/XML that carries no
 * components) is a genuine negative, not this failure.
 *
 * <p>It extends {@link IOException} so it rides the SBOM read path's existing {@code throws IOException} without
 * widening any signature.
 */
public class MalformedSbomException extends IOException {

    public MalformedSbomException(String message) {
        super(message);
    }

    public MalformedSbomException(String message, Throwable cause) {
        super(message, cause);
    }
}
