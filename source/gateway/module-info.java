/**
 * The integration around the repository's routed serving: the repository router (hosted, proxy and
 * group definitions over named repositories) and the proxy-path screen that gates a pull-through fetch. The
 * publish-path compliance screening itself rides the publication-interceptor chain as the {@code gate}
 * module's discovered {@code ComplianceScreen}; this module consumes its {@code QuarantineLog} for the
 * proxy-path audit. Reviewing a hold is the gate's own - {@code GatedRepository} lives there with the
 * {@code HoldLifecycle} primitive it is a facade over.
 *
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 * @jenesis.release 25
 */
module build.jenesis.repository.gateway {
    requires build.jenesis.repository.server;
    // The kernel's live settings, tenant binding and the definitions seam the router answers into. The kernel never
    // requires this module: what it used to take from here - the definitions parser, the deploy edge's hooks, the
    // release-immutability guard - lives here now, so a web adapter that needs only a tenant and a store no longer
    // drags the gated edge.
    requires build.jenesis.repository.server.kernel;
    requires build.jenesis.repository.store;
    requires build.jenesis.repository.format;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.gate.spi;
    requires build.jenesis.repository.observation;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.settings;
    // The definitions model the router resolves through; transitive because the router's constructor and the
    // RedirectHandler seam name it.
    requires transitive build.jenesis.repository.definitions;
    // The ProxyLeg seam names the one proxy outbound dial (proxy-allow-internal); the router screens the
    // operator-configured upstream under that same key, so it reads the constant rather than respelling it.
    requires build.jenesis.repository.blobs;
    requires java.net.http;
    // The deploy edge's hooks raise the jenreg.deploy observation.
    requires micrometer.observation;
    requires org.slf4j;
    exports build.jenesis.repository.gateway;
    uses build.jenesis.repository.gateway.RedirectHandlerProvider;
    provides build.jenesis.repository.observation.ObservabilitySource
            with build.jenesis.repository.gateway.SpoolObservability,
                    build.jenesis.repository.gateway.HardeningObservability;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.gateway.MigrationRescreenTaskProvider;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.gateway.HardeningSettingsContributor;
}
