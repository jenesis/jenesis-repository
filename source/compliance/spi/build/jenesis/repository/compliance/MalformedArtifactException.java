package build.jenesis.repository.compliance;

import module java.base;

/**
 * A {@link QualityInspector} could not parse an artifact it claimed: the bytes were supposed to be a readable
 * artifact of its format (a publish frame, an archive carrying a manifest, a binary header) but did not decode -
 * truncated, corrupt, not the archive it names, or a decompression bomb the bounded read rejected. This is the
 * inspector's way of DISTINGUISHING "could not parse" from "parsed fine, declares nothing": an inspector still
 * returns an empty {@code List<Subject>} when the leg carries no coordinate to assess (a proxy index file, a
 * non-publish document that parsed cleanly), and throws this only when a genuine parse of the artifact failed.
 *
 * <p>It extends {@link IOException} so it rides the SPI's existing {@code throws IOException} without widening any
 * signature; the compliance screen catches it distinctly and records an <em>inspection-failed</em> finding on the
 * coordinate (never a silent clean), rather than treating the empty result as "screened, nothing found". A source
 * whose coordinate is read purely from the request path (Go, Conan, Hugging Face) cannot raise this - there is
 * nothing to parse - and a content scanner whose empty means genuinely-clean (secret, attestation) does not either.
 */
public class MalformedArtifactException extends IOException {

    public MalformedArtifactException(String message) {
        super(message);
    }

    public MalformedArtifactException(String message, Throwable cause) {
        super(message, cause);
    }
}
