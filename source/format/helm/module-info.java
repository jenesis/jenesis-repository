/**
 * The classic Helm chart repository: a {@link build.jenesis.repository.format.RepositoryFormat} for the plain HTTP
 * protocol {@code helm repo add} speaks, with a pull-through proxy leg. Charts pushed as OCI artifacts resolve through
 * the {@code oci} format, so this is {@code index.yaml} plus {@code .tgz} charts under {@code /helm/<repo>/...},
 * published by the ChartMuseum-compatible {@code POST api/charts} ({@code helm cm-push}) or a direct {@code PUT}.
 *
 * <p>{@code index.yaml} is a {@link build.jenesis.repository.store.StoredListing}: a read streams it and a publish
 * rewrites one chart's entry; its {@link build.jenesis.repository.store.StoredListing.Generator} is the repair path,
 * and a {@code PublicationObserver} keeps it in step with holds, releases, marks and removals. It carries no
 * {@code generated:} stamp, since it is amended per write.
 *
 * <p>The index {@code digest} is the SHA-256 the store computed, so Helm verifies the bytes this repository serves; a
 * chart's metadata, including {@code deprecated}, is read from the {@code Chart.yaml} inside the archive with
 * SnakeYAML's {@code SafeConstructor}. It is also an {@link build.jenesis.repository.format.ArtifactLayout} for the
 * {@code "Helm"} ecosystem and a {@link build.jenesis.repository.format.RepositoryImporter}.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.format.helm {
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.format.lifecycle;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.walk;
    requires build.jenesis.repository.blobs;
    requires org.apache.commons.compress;
    requires org.slf4j;
    requires org.yaml.snakeyaml;
    // Exported for the suites that drive the format directly.
    exports build.jenesis.repository.format.helm;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.helm.HelmFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.helm.HelmListingObserver;
}
