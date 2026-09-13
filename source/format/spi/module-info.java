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
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.50.0 SHA-256/4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a
 * @jenesis.pin org.jspecify/jspecify 1.0.1 SHA-256/070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66
 * @jenesis.pin org.slf4j/slf4j-api 2.0.19 SHA-256/e91ff6d720609e7a194ffe758c3ed5c84e798617ae07b0a0f6a4fe229741b4bb
 */
module build.jenesis.repository.format {
    requires transitive build.jenesis.repository.icon;
    requires transitive build.jenesis.repository.store;
    requires transitive build.jenesis.repository.net;
    exports build.jenesis.repository.format;
    uses build.jenesis.repository.format.FetcherProvider;
    uses build.jenesis.repository.format.RepositoryFormat;
}
