package build.jenesis.repository.format;

/**
 * A format that declares the package ecosystem its coordinates belong to - the name its descriptors carry and the
 * advisory feeds and the gate key on (a Maven format's {@code "Maven"}, an npm format's {@code "npm"}, a Cargo
 * format's {@code "crates.io"}). It is the one thing a coordinate-only consumer needs from a format: a browse hit, a
 * finding or a stored release carries an ecosystem and a coordinate, never a request path, and the consumer finds
 * the installed format that owns it by this declaration rather than by guessing from a format id or a namespace.
 *
 * <h2>An ecosystem is a vocabulary, not an owner</h2>
 * <b>Several installed formats may declare the same ecosystem, and every consumer must expect it.</b> The value is
 * the name a vulnerability database uses for a coordinate space, so what it identifies is <em>how a coordinate is
 * spelled and matched</em> - not who serves it and not where the bytes sit. One coordinate space can legitimately be
 * served through more than one layout: an Ivy repository and a Maven one both address {@code org:name:revision} and
 * are both {@code Maven} to OSV, and a deployment may reasonably offer both.
 *
 * <p>So a lookup of the shape "which layout owns this ecosystem" has no single answer, and code that takes a first
 * match silently makes the answer a property of discovery order - which for the lookup an eviction computes its
 * deletes from means one store can be swept differently on two nodes. Such a consumer <b>fans out</b>: it asks every
 * installed format that declares the ecosystem and unions what they say. That union is exact rather than an
 * over-approximation, because every layout of one ecosystem maps the <em>same</em> coordinate and version - so the
 * union is precisely "every place this version lives", which is what an eviction, a reconcile and a browse each
 * want. Where a fan-out is meaningless because the answer must be singular - a console mark - the answer belongs to
 * the ecosystem rather than to whichever format was asked first.
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
