/**
 * The consolidated metadata document contract: one JSON document per coordinate version at
 * {@code meta/<ecosystem>/<enc(coordinate)>/<version>} ({@link build.jenesis.repository.metadata.MetadataKey}), plus
 * one per coordinate for version-independent facts. Each carries a top-level {@code format} version and per-contributor
 * {@link build.jenesis.repository.metadata.Section} envelopes - {@code schema}, {@code updated}, {@code state},
 * optional {@code error} and {@code signal}, and an opaque {@code data} payload.
 * {@link build.jenesis.repository.metadata.MetadataDocument} owns the total, section-carrying read and the format
 * guard, so an older node never drops a newer writer's section; each subsystem mutates its own section, alone or in a
 * batch, through the discovered {@link build.jenesis.repository.metadata.MetadataProvider}.
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
