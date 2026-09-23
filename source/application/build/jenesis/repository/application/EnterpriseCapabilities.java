package build.jenesis.repository.application;

import module java.base;

import build.jenesis.repository.server.spi.CapabilityContributor;

/**
 * The enterprise's contribution to the <em>one</em> free-served {@code /api/capabilities} endpoint. The free
 * {@link build.jenesis.repository.server.RepositoryController#capabilities} builds its base map
 * ({@code readOnly}, {@code auth}, {@code anonymousRights}) and then merges every {@link ServiceLoader}-discovered
 * {@link CapabilityContributor} into it (base keys win); this enterprise contributor plugs the richer view -
 * installed formats, import sources, advisory-report columns, per-module capability rows and feature flags - onto that
 * single endpoint. It <b>retires the former {@code WebMvcRegistrations} mapping-suppression stopgap</b> that dropped
 * the free {@code capabilities} mapping so the enterprise {@link DeploymentInfoController} could own the same path: the
 * free controller now serves {@code /api/capabilities} once, extended (never shadowed) by this contribution.
 *
 * <p>A {@link CapabilityContributor} is discovered with a plain {@code ServiceLoader.load} inside the free controller,
 * so it is instantiated with a no-arg constructor and has <b>no</b> Spring context. The rich view, by contrast, reads
 * live Spring beans and the effective configuration ({@code Repositories}, {@code Settings}, the provenance signer,
 * the upstream fetcher). So the enterprise
 * {@link DeploymentInfoController} - the Spring bean that already builds that view - {@linkplain #install installs} a
 * supplier of it here at construction, the same {@code install}/{@code installed} static-holder seam
 * {@code SpoolStore} and {@code MaintenanceObservability} use to bridge a Spring bean to a {@code ServiceLoader}-
 * discovered collaborator. With nothing installed (a shell that wires no {@code DeploymentInfoController}) this
 * contributes an empty map, so the free base map is served byte-for-byte unchanged - the SPI's no-op-by-absence
 * contract.
 *
 * <p><b>The merge reports a collision now, so this side no longer refuses one</b> (then /). The free
 * {@link CapabilityContributor#merge} used to fold a contribution in with {@code putIfAbsent}: a base key
 * <em>always</em> won, which protects the free product's own flags but dropped the contributed value with nothing
 * logged, nothing thrown and nothing visible in the served body. an earlier change closed that from this side, by throwing at the
 * point the contribution is built. Free core 0.10.0 closed it properly and at the right end: the merge now
 * <em>names</em> every entry it refuses, in the returned {@link CapabilityContributor.Merged} report and in the served
 * body under {@value CapabilityContributor#CONFLICTS_KEY}.
 *
 * <p>So the throw is gone, and its removal is a <em>fix</em> rather than a relaxation. It had become both redundant
 * and <b>coarser than the report it stood in for</b>: an exception out of {@link #capabilities} is contained by the
 * merge and recorded as a contributor <em>failure</em>, which drops this contribution <b>entirely</b> - so one
 * misspelled key would have cost {@code /api/capabilities} the deployment's formats, import sources, signals, modules
 * and feature flags, where the free rule drops exactly the one colliding key and serves the rest. Preventing a silent
 * drop by causing a loud, larger one is the wrong trade on a read surface whose whole job is to say what this
 * deployment can do.
 *
 * <p>What replaces it is a <b>build-time</b> guard rather than a request-time one, which is where a key-naming mistake
 * belongs: {@code CoreControllerSplitE2ETest} names the six component keys {@link DeploymentInfoController}
 * contributes, asserts the real served body carries them all, and asserts the remaining top-level keys are exactly
 * {@link #FREE_BASE_KEYS} - so a collision fails a build, not a request. {@link #extending} survives as the documented statement of the rule and as
 * the null-contribution normaliser.
 *
 * <p>This contribution no longer carries the optional modules' feature flags. Each feature module now contributes
 * its own flag directly through this same SPI, so {@code walk}, {@code gc}, {@code search}, {@code dependents},
 * {@code scan}, {@code provenance} and {@code audit} are top-level entries of the served document rather than
 * fields lifted into {@code FeaturesView} by a second fan-out. Two modules claiming one flag is caught by the one
 * merge, which serves a single value and names the refused contribution.
 */
public final class EnterpriseCapabilities implements CapabilityContributor {

    /**
     * The keys the free {@code RepositoryController.capabilities} puts in its base map before merging contributions.
     * A contribution may only <em>extend</em> that map: on a conflict the base wins - reported, since free 0.10.0, in
     * {@link CapabilityContributor.Merged#conflicts()} and under {@value CapabilityContributor#CONFLICTS_KEY} - so a
     * contributed key spelled like one of these is not served. Kept live by
     * {@code CoreControllerSplitE2ETest.the_console_shell_and_deployment_info_reads_serve},
     * which reads the real merged body rather than trusting this list.
     */
    public static final Set<String> FREE_BASE_KEYS = Set.of("readOnly", "auth", "anonymousRights");

    /** The live rich-capabilities supplier, installed by {@link DeploymentInfoController} at construction. Volatile so
     *  the ServiceLoader-discovered instance in the request thread reads the reference the boot thread published. */
    private static volatile Supplier<Map<String, Object>> supplier;

    /** Publish the supplier of the enterprise rich-capabilities map (the {@link DeploymentInfoController}'s live view),
     *  so a request-time contributor renders exactly the deployment's current formats / import-sources / module-flags.
     *  Called once at bean construction; re-installing replaces the reference. */
    public static void install(Supplier<Map<String, Object>> richCapabilities) {
        supplier = richCapabilities;
    }

    /** {@inheritDoc}
     *
     *  <p>The {@code configuration} operator is unused: this contribution is the {@link DeploymentInfoController}'s
     *  own live view, already resolved through that controller's pin-over-stored-over-environment chain, which is
     *  strictly richer than the boot-layered chain the free controller merges with. Resolving it a second time here
     *  would answer the same question through the weaker chain. */
    @Override
    public Map<String, Object> capabilities(UnaryOperator<String> configuration) {
        Supplier<Map<String, Object>> installed = supplier;
        return installed == null ? Map.of() : extending(installed.get());
    }

    /**
     * The rich view, normalised: a contributor's "nothing to contribute" is an empty map, and a {@code null} supplier
     * answer means the same (the SPI's absence sentinel).
     *
     * <p>It deliberately does <b>not</b> refuse a key {@link #FREE_BASE_KEYS the free base map already owns} any more.
     * Free core 0.10.0's merge names every refused entry in its {@link CapabilityContributor.Merged} report and in the
     * served body, so the silence this check existed to break is gone - and throwing here is strictly worse than the
     * report, because the merge contains the exception and drops this contribution WHOLE where the free rule drops the
     * one colliding key. The rule itself is unchanged and is now enforced where a naming mistake can be fixed for
     * free: at build time, by {@code CoreControllerSplitE2ETest}'s served-body key assertions.
     */
    static Map<String, Object> extending(Map<String, Object> contribution) {
        return contribution == null ? Map.of() : contribution;
    }
}
