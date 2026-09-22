/**
 * The classic Helm chart repository as a plugin module: it provides
 * {@link build.jenesis.repository.format.RepositoryFormat} for the plain HTTP protocol {@code helm repo add} speaks,
 * so {@code helm install}, {@code helm pull} and {@code helm dependency update} resolve charts over the shared store.
 *
 * <p><b>Scope, deliberately narrow.</b> Only the classic repository is here. Charts pushed as OCI artifacts already
 * resolve through the {@code oci} format - that is the same registry protocol, not a second implementation of it - so
 * this module is {@code index.yaml} plus {@code .tgz} charts and nothing else. It owns {@code /helm/<repo>/...}: the
 * index at {@code index.yaml}, a chart at {@code charts/<name>-<version>.tgz}, and two publish routes - the
 * ChartMuseum-compatible {@code POST api/charts} with the archive as the body, which is what the {@code helm cm-push}
 * plugin sends, and a direct {@code PUT charts/<name>-<version>.tgz}, which is the path the importer replays through.
 *
 * <p><b>The index is maintained, not generated.</b> {@code index.yaml} is a
 * {@link build.jenesis.repository.store.StoredListing} whose entry each publish re-decides and whose
 * {@link build.jenesis.repository.store.StoredListing.Generator} is the repair path, which is the shape
 * {@code StoredListing} exists for: a read streams the stored document as it is, and a publish rewrites one chart's
 * entry rather than folding over every chart in the repository. A {@code PublicationObserver} keeps it in step with a
 * hold, a release, a lifecycle mark and a removal.
 *
 * <p><b>Why the document carries no {@code generated:} stamp.</b> Helm's own index files record when the index was
 * generated, because they are produced in one pass by {@code helm repo index}. This one is not produced in a pass at
 * all - it is amended by each write - so there is no single moment it was generated and any timestamp would be a
 * fiction that a reader would take for a fact. The field is informational: Helm's index loader requires
 * {@code apiVersion} and reads {@code generated} only to display it.
 *
 * <p><b>The digest is the store's, not the publisher's.</b> Helm verifies a downloaded chart against the
 * {@code digest} in the index entry it read. That digest is written from the SHA-256 the content-addressed store
 * computed as the archive streamed in, so it describes the bytes this repository will actually serve. The chart's
 * metadata - its name, version, description, appVersion and {@code deprecated} flag - is read from the
 * {@code Chart.yaml} <i>inside</i> the archive, materialised alone through the product's shared archive bounds while
 * the chart's templates and values stream past, and parsed with SnakeYAML's {@code SafeConstructor}, which
 * instantiates nothing.
 *
 * <p>It also provides {@link build.jenesis.repository.format.ArtifactLayout}, declaring the {@code "Helm"} ecosystem,
 * and a {@link build.jenesis.repository.format.RepositoryImporter} that replays an exported chart repository through
 * the format's own publish path. {@code Chart.yaml} carries {@code deprecated}, so a deprecated chart is a lifecycle
 * mark this format has a native field for rather than one it has to declare away.
 *
 * <p>No pull-through proxy leg: that is a second change, and it is not implied by "serve the classic repository".
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
    // Exported to test modules only; the unit suite is named here because its assertions
    // are about the path grammar, which belongs in the fastest lane.
    exports build.jenesis.repository.format.helm to
            build.jenesis.repository.gateway.test,
            build.jenesis.repository.gateway.census.test,
            build.jenesis.repository.format.helm.test;
    provides build.jenesis.repository.format.RepositoryFormat
            with build.jenesis.repository.format.helm.HelmFormat;
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.format.helm.HelmListingObserver;
}
