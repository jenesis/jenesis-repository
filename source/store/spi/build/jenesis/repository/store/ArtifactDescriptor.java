package build.jenesis.repository.store;

import module java.base;

/**
 * A format-neutral description of one artifact at a request path: the ecosystem-canonical coordinate a format parsed
 * out of its own layout, plus the content-addressed identity {@link Publication} assigns it once the blob is stored.
 * Formats emit it; upload post-processors (a compliance gate, quarantine audit, inventory, download tracking) and
 * neutral cleanup consume it, so no code outside a format ever re-parses a layout. The {@code ecosystem} /
 * {@code coordinate} / {@code version} triple is the neutral identity every such concern keys on.
 */
public record ArtifactDescriptor(String ecosystem,
                                 String coordinate,
                                 String version,
                                 String path,
                                 String contentType,
                                 boolean prerelease,
                                 String hash,
                                 long size,
                                 String replaced) {

    /**
     * The eight-component form, for every caller that is not describing a replacement.
     *
     * <p>It delegates rather than being the canonical shape because {@code replaced} is information only the
     * publish choreography holds, and only at the moment it overwrites a pointer. Twenty-two construction sites
     * across the two trees describe an artifact without ever being in that position, and making them all say
     * {@code null} would be noise that hides the two places where the value is real.
     */
    public ArtifactDescriptor(String ecosystem, String coordinate, String version, String path,
                              String contentType, boolean prerelease, String hash, long size) {
        this(ecosystem, coordinate, version, path, contentType, prerelease, hash, size, null);
    }

    /**
     * The blob this publish overwrote at the same request path, when it overwrote one, and {@code null} otherwise -
     * including when the caller is not in a position to know.
     *
     * <p>An after-commit observer that keeps a running total needs it. The pointer already names the new blob by
     * the time the observer is called, so nothing in the store says what the path used to contribute, and an
     * observer folding {@code +size} per delivery double-counts a re-publish at the same path - byte-identical or
     * not - until the next full re-derivation sweeps the drift away. Nothing else can supply it: the value exists
     * for one instant, between reading the pointer and overwriting it.
     *
     * <p>{@code null} is deliberately not "there was nothing there". It means <em>this descriptor does not say</em>,
     * which is the honest answer for the two ingress edges that lay an artifact out themselves and then call
     * {@link Publication#published}: by then they have already overwritten the pointer too. A consumer must treat
     * an absent value as no information rather than as a first publish, or it trades a double-count for a
     * subtraction that never happened.
     */
    /** A descriptor for a path that carries no coordinate - a checksum, a raw file, a generated index: the owning
     *  ecosystem and the path, with no coordinate, version, content type, prerelease flag or blob identity. */
    public static ArtifactDescriptor at(String ecosystem, String path) {
        return new ArtifactDescriptor(ecosystem, null, null, path, null, false, null, -1L);
    }

    /** The same descriptor with the content-addressed identity {@link Publication} assigns once the blob is stored -
     *  the SHA-256 it landed under ({@code blobs/<hash>}) and its stored byte length - which is what an interceptor
     *  sees, and what an ingress edge stamps onto the descriptor it hands {@link Publication#published} so an
     *  after-commit observer gets the accepted blob's identity. */
    public ArtifactDescriptor withBlob(String hash, long size) {
        return new ArtifactDescriptor(ecosystem, coordinate, version, path, contentType, prerelease, hash, size);
    }
}
