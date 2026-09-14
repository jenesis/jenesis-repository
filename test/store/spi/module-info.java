/**
 * Focused unit tests for the storage SPI, all driven against a real
 * {@code FilesystemArtifactStore} rooted at a JUnit {@code @TempDir} (through
 * {@link build.jenesis.repository.store.ArtifactStoreProvider#resolve}) so the store primitives are exercised without
 * the server or any network: the content-addressed {@link build.jenesis.repository.store.Publication} pointer model, the
 * {@link build.jenesis.repository.store.QuotaArtifactStore} byte-ceiling decorator (including its
 * {@link build.jenesis.repository.observation.ObservabilitySource} adoption - the {@code jenreg.quota.used}
 * used-vs-available metric and {@code jenreg.quota.capacity} health check, silent while unlimited), the provider's ServiceLoader
 * resolution with its filesystem fallback, the config-driven SPI enable/disable convention
 * ({@link build.jenesis.repository.store.Features} - toggle semantics, required-config self-disable, and the store
 * backend's fail-loud exception to it), the {@link build.jenesis.repository.store.Tenants} directory seam
 * falling back to the fixed single tenant, and the two publication hook classes - the ordered
 * {@link build.jenesis.repository.store.PublishInterceptor} screens (verdict routing and the withhold read side)
 * and the contained after-commit {@link build.jenesis.repository.store.PublicationObserver} observers.
 *
 * @jenesis.release 25
 * @jenesis.test build.jenesis.repository.store
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
open module build.jenesis.repository.store.test {
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.store.testkit;
    requires build.jenesis.repository.observation;
    requires org.junit.jupiter;
    requires org.assertj.core;
    provides build.jenesis.repository.store.ArtifactStoreProvider
            with build.jenesis.repository.store.test.NeedyArtifactStoreProvider;
    // The withhold-change feed guard (WithholdFeedTest) needs a discovered PublicationObserver, since Withheld.mark /
    // Withheld.clear are static and fire through Publication's ServiceLoader-discovered OBSERVERS list, not an injected
    // one. A base-only observer, so it never joins the verdict chain.
    provides build.jenesis.repository.store.PublicationObserver
            with build.jenesis.repository.store.test.RecordingWithholdObserver;
}
