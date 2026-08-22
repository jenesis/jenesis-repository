package build.jenesis.repository.format;

/**
 * A format that declares the package ecosystem its coordinates belong to - the name its descriptors carry and the
 * advisory feeds and the gate key on (a Maven format's {@code "Maven"}, an npm format's {@code "npm"}, a Cargo
 * format's {@code "crates.io"}). It is the one thing a coordinate-only consumer needs from a format: a browse hit, a
 * finding or a stored release carries an ecosystem and a coordinate, never a request path, and the consumer finds
 * the installed format that owns it by this declaration rather than by guessing from a format id or a namespace.
 *
 * <p>It is the seam both layout families share. A format that lays its artifacts out under the published tree
 * declares it through {@link ArtifactLayout}; a format that keeps its artifacts in a blobs namespace of its own
 * declares it through that namespace's layout contract. A consumer that wants "which installed format owns this
 * ecosystem" asks for this interface and gets both, which is what keeps a console from telling an operator that an
 * installed format is not installed merely because it stores differently.
 */
public interface EcosystemLayout {

    /** The package-ecosystem name this format's artifacts report. */
    String ecosystem();
}
