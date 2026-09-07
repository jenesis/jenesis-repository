/**
 * The artifact-store SPI and the format-neutral content-addressed store ({@code Publication}) built on it.
 * Its only dependency beyond java.base is the equally minimal, registry-free
 * {@code build.jenesis.repository.observation} SPI (so a capped store can report its {@code jenreg.quota.*}
 * used-vs-available signals), so a format plugin builds on the store and its {@code Publication} without
 * pulling in the server. A backend ships as its own module that {@code provides} an {@code ArtifactStoreProvider},
 * discovered on the module path with {@code ServiceLoader}: the default filesystem backend, plus the optional s3,
 * gcs and azure backends when on the graph. The {@code Tenants} directory of the shared
 * {@code <tenant>/<repository>/...} layout is discovered the same way ({@code TenantsProvider}); with no module
 * installed it is the fixed single tenant. A publication carries one discovered hook class, the
 * {@code PublicationObserver} after-commit observer (forwarding, webhooks, replication - notified only once an
 * accepted artifact serves, contained so it never fails a publish), with the verdict-bearing
 * {@code PublishInterceptor} (accept / quarantine / reject before the pointer links, withhold on read) as its
 * {@code instanceof}-detected sub-interface: one {@code uses PublicationObserver} clause discovers both, and
 * {@code Publication} splits the discovered list to drive the interceptor chain while notifying every observer.
 * Both are empty by default. Alongside them live the two halves of the shared SPI convention every discovered
 * seam in the product reuses: {@code Features} (is an implementation switched on, and which one did the operator
 * select) and {@code Providers} (the per-policy resolution primitives - {@code all}, {@code optionalUnique},
 * {@code namedUnique}, {@code exclusiveWithDefault}, {@code installedNames} - an SPI's own {@code resolve} /
 * {@code installed} statics delegate to, so an explicitly selected implementation that is absent, switched off or
 * misconfigured fails loudly instead of silently degrading to the unselected default).
 *
 * @jenesis.release 25
 * @jenesis.pin com.github.ben-manes.caffeine/caffeine 3.2.4 SHA-256/9d9d2cfd681fd9272ded3d27c9930db12f89f732345975aa113ebc223bbf1224
 * @jenesis.pin com.github.benmanes.caffeine 3.2.4
 * @jenesis.pin com.google.errorprone/error_prone_annotations 2.49.0 SHA-256/3b1003e51b8ae56fdbd7c71073e81d1683b97e6c4dff5a9151164d59b769d13c
 * @jenesis.pin org.jspecify/jspecify 1.0.0 SHA-256/1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab
 * @jenesis.pin org.slf4j 2.0.18
 * @jenesis.pin org.slf4j/slf4j-api 2.0.18 SHA-256/44508fd1576500688c790b190acdd16fec4f8c79a3e0b900afd70503cf055f55
 */
module build.jenesis.repository.store {
    requires build.jenesis.repository.scope;
    requires transitive build.jenesis.repository.observation;
    requires org.slf4j;
    requires com.github.benmanes.caffeine;
    exports build.jenesis.repository.store;
    uses build.jenesis.repository.store.ArtifactStoreProvider;
    uses build.jenesis.repository.store.PublicationObserver;
    uses build.jenesis.repository.store.TenantsProvider;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.store.QuotaObservability,
                 build.jenesis.repository.store.StoredListing.Observability,
            build.jenesis.repository.store.StoreCacheObservability;

    // The family extends IconContributor, so every implementation gains the optional mark seam and
    // the console resolves one answer for all of them. Transitive: an implementation overriding
    // icon() names IconResource in its own signature.
    requires transitive build.jenesis.repository.icon;
}
