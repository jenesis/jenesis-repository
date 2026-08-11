/**
 * The repository-format SPI: {@link build.jenesis.repository.format.RepositoryFormat} and the framework-neutral
 * {@link build.jenesis.repository.format.FormatExchange} it speaks through. A format (Maven, OCI, npm, PyPI,
 * NuGet) is a separate module that requires only this SPI and the storage SPI and {@code provides
 * RepositoryFormat}; the dispatcher discovers them with {@link java.util.ServiceLoader}, so layouts plug in
 * without the core, the dispatcher or the other formats knowing about them. The optional role sub-interfaces a
 * neutral consumer detects with {@code instanceof} live here beside it - {@code ArtifactLayout} (coordinate/path
 * mapping), {@code ProxyFormat} (pull-through) and {@code BlobReferences} (which content blobs a format still
 * serves, the answer garbage collection must never get wrong) - so a format opts into each without a core edit and
 * one {@code uses RepositoryFormat} clause discovers them all.
 *
 * <p>{@code RepositoryFormat} additionally <em>extends</em> the family-neutral
 * {@link build.jenesis.repository.icon.IconContributor}, which is where its {@code name()} and its optional console
 * mark come from - the same interface the plug-ins that contribute findings extend, so both families resolve a mark
 * through one {@code Marks} rather than through a copy each. The requirement is transitive because a format
 * declaring a mark needs {@code IconResource} on its own compile path.
 * {@link build.jenesis.repository.format.FormatMarks} is this family's half of that: only the mapping from a
 * storage namespace or an ecosystem to the format that owns it, over the shared resolution.
 *
 * @jenesis.release 25
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.format {
    requires transitive build.jenesis.repository.icon;
    requires transitive build.jenesis.repository.store;
    exports build.jenesis.repository.format;
    uses build.jenesis.repository.format.FetcherProvider;
    uses build.jenesis.repository.format.RepositoryFormat;
}
