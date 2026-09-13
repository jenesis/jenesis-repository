/**
 * The carrier: one launchable module whose {@code requires} closure is every free SPI implementation -
 * all four layouts ({@code maven}, {@code jenesis}, {@code oci}, {@code raw}), all four store backends
 * ({@code filesystem}, {@code s3}, {@code gcs}, {@code azure}), all five import connectors, the upstream HTTP
 * fetcher ({@code proxy}), the OIDC token exchange ({@code oidc}), the token-bucket rate limiter, the credential
 * usage tracker and the web console ({@code ui}) - so the packaging {@code bundle} step emits a {@code bundle.zip}
 * carrying the complete free product, and the {@code Dockerfile} turns that one zip into the image.
 * Nothing here names a plugin: the server keeps discovering everything through {@code ServiceLoader}, and the image
 * is trimmed by configuration instead of rebuilt - {@code jenreg.<feature>=false} (settable as
 * {@code JENREG_<FEATURE>=false} through relaxed binding) disables an implementation exactly as if its
 * module were absent, and {@code jenreg.<spi>=<feature>} selects among exclusive implementations
 * (the store defaults to {@code filesystem}), per the {@code build.jenesis.repository.store.Features} convention.
 *
 * <p>{@link build.jenesis.repository.bundle.Server} boots the repository server under the config name
 * {@code bundle} ({@code bundle.properties} in this module), because with the server and the console both on
 * the module path two root {@code application.properties} would be ambiguous;
 * so the one image also runs the console node via {@code MAINMODULE}/{@code MAINCLASS}.
 *
 * @jenesis.release 25
 * @jenesis.main build.jenesis.repository.bundle.Server
 * @jenesis.bom pin-repository.properties
 */
open module build.jenesis.repository.bundle {
    exports build.jenesis.repository.bundle;
    requires build.jenesis.repository.server;
    requires build.jenesis.repository.ui;
    requires build.jenesis.repository.store.filesystem;
    requires build.jenesis.repository.store.s3;
    requires build.jenesis.repository.store.gcs;
    requires build.jenesis.repository.store.azure;
    requires build.jenesis.repository.format.jenesis;
    requires build.jenesis.repository.format.maven;
    requires build.jenesis.repository.format.oci;
    requires build.jenesis.repository.format.raw;
    requires build.jenesis.repository.importer.artifactory;
    requires build.jenesis.repository.importer.index;
    requires build.jenesis.repository.importer.jenesis;
    requires build.jenesis.repository.importer.maven;
    requires build.jenesis.repository.importer.nexus;
    requires build.jenesis.repository.oidc;
    requires build.jenesis.repository.proxy;
    requires build.jenesis.repository.ratelimit;
    requires build.jenesis.repository.usage;
    // Reclamation: the collector, and the walk consumer that runs it at the end of a pass. Without these an
    // installed collector is never called and a deployment's storage only ever grows.
    requires build.jenesis.repository.gc;
    requires build.jenesis.repository.gc.store;
    requires build.jenesis.repository.gc.walk;
    requires spring.boot;
    requires spring.context;
}
