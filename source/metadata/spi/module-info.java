/**
 * The consolidated metadata document contract: the versioned, tagged, per-coordinate document that
 * consolidates the derived per-coordinate(-version) metadata the audit found scattered across ~9-11 sidecars in
 * three incompatible key encodings and two serialization families. One JSON document per coordinate version keyed
 * {@code meta/<ecosystem>/<enc(coordinate)>/<version>} ({@link build.jenesis.repository.metadata.MetadataKey}, the
 * single canonical codec) carries a top-level {@code format} version and per-contributor
 * {@link build.jenesis.repository.metadata.Section} envelopes - {@code schema}, {@code updated}, {@code state}
 * ({@code derived}/{@code empty}/{@code error}), optional {@code error} and {@code signal}, and an opaque
 * {@code data} payload. {@link build.jenesis.repository.metadata.MetadataDocument} owns the total, section-carrying
 * read and the format guard, generalising {@code StoreFindings}' carried-rows model to the section level so an
 * older node never drops a newer writer's section; any subsystem mutates its own tagged section, in one or a
 * batch, through the discovered {@link build.jenesis.repository.metadata.MetadataProvider}. This is the
 * library/foundation - no production path reads or writes the document yet; the per-subsystem cutovers land later.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.metadata {
    requires transitive build.jenesis.repository.store;
    requires transitive build.jenesis.repository.compliance;
    requires transitive tools.jackson.databind;
    requires org.slf4j;
    exports build.jenesis.repository.metadata;
    uses build.jenesis.repository.metadata.MetadataProvider;
}
