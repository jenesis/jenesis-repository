package build.jenesis.repository.dependency;

import module java.base;

/**
 * A BOM was present but could not be parsed: the document announced JSON or XML (first non-whitespace token
 * <code>{</code>, <code>[</code> or <code>&lt;</code>) but did not decode, or its XML dependency graph nests past the bound. {@link CycloneDxParser#parseStrict} raises it on the served view so the SBOM panel shows a scoped error rather than "no dependencies"; the fail-soft {@link CycloneDxParser#parse} the sweep uses yields an empty graph. A document that is not a BOM at all is a genuine negative, not this failure.
 *
 * <p>An {@link IOException}, so it rides the read path's existing {@code throws IOException}.
 */
public class MalformedSbomException extends IOException {

    public MalformedSbomException(String message) {
        super(message);
    }

    public MalformedSbomException(String message, Throwable cause) {
        super(message, cause);
    }
}
