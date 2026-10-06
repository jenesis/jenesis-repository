/**
 * A published version's transitive closure, resolved best-effort from what the store holds and nothing else: what a
 * build through the repository that published it can resolve today. A release's declared dependencies are walked
 * through the repository's own holdings - its releases and the copies it cached from upstreams - each requirement
 * taking the newest held version that satisfies it, each held version's own declarations read from its document or
 * its manifest. No HTTP request is made. A dependency that is not held, is held for review or states a requirement no
 * installed grammar evaluates ends its own subtree only, recorded as a cut beside the components that resolved.
 *
 * <p>The closure is a section of the version's document ({@link build.jenesis.repository.closure.spi.ClosureSection}),
 * resolved off the request path by a pass ({@link build.jenesis.repository.closure.ClosureTask}) that visits every
 * release without one, a release published late and one published before the setting was on alike, and asked for
 * per repository by {@code closure-resolution}. How a closure is produced is a discovered
 * {@link build.jenesis.repository.closure.spi.ClosureSource}, each serving the ecosystems it declares and asked in order -
 * the bill a release carries ({@link build.jenesis.repository.closure.CarriedBill}), an ecosystem's own resolver, a
 * scanner, and last the walk over declarations ({@link build.jenesis.repository.closure.DeclaredClosure}), the first
 * answer winning; this module provides the first and the last. Evaluating a requirement in the walk is a discovered
 * {@link build.jenesis.repository.closure.spi.RequirementGrammar}. Beside the closure the pass keeps what it reaches that
 * is held for review or carries findings ({@link build.jenesis.repository.closure.spi.ExposureSection}), re-derived on each
 * full pass, which is the state a published version inherits from the copies it relies on. The other way round, the
 * pass indexes which published versions rely on each version a repository holds
 * ({@link build.jenesis.repository.closure.ReliedOn}), read a page at a time from the copy's side.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-repository.properties
 * @jenesis.signature signature-repository.properties
 */
module build.jenesis.repository.closure {
    requires transitive build.jenesis.repository.store;
    requires transitive build.jenesis.repository.closure.spi;
    requires build.jenesis.repository.compliance;
    requires build.jenesis.repository.definitions;
    requires build.jenesis.repository.dependency;
    requires build.jenesis.repository.findings;
    requires build.jenesis.repository.inventory;
    requires build.jenesis.repository.maintenance;
    requires build.jenesis.repository.metadata;
    requires build.jenesis.repository.scope;
    requires build.jenesis.repository.settings;
    requires tools.jackson.databind;
    requires org.slf4j;
    exports build.jenesis.repository.closure;
    provides build.jenesis.repository.closure.spi.ClosureSource
            with build.jenesis.repository.closure.CarriedBill, build.jenesis.repository.closure.DeclaredClosure;
    provides build.jenesis.repository.closure.spi.RelianceProvider
            with build.jenesis.repository.closure.ReliedOnReliance;
    provides build.jenesis.repository.maintenance.MaintenanceTaskProvider
            with build.jenesis.repository.closure.ClosureTaskProvider;
    provides build.jenesis.repository.maintenance.StorageNamespace
            with build.jenesis.repository.closure.ClosureStorageNamespace;
    provides build.jenesis.repository.settings.SettingsContributor
            with build.jenesis.repository.closure.ClosureSettingsContributor;
}
