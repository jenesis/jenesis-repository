package build.jenesis.repository.application;

import module java.base;

import build.jenesis.repository.server.spi.CapabilityContributor;

/**
 * This composition's contribution to the <em>one</em> {@code /api/capabilities} endpoint. The free
 * {@link build.jenesis.repository.server.RepositoryController#capabilities} builds its base map
 * ({@code readOnly}, {@code auth}, {@code anonymousRights}) and then merges every {@link ServiceLoader}-discovered
 * {@link CapabilityContributor} into it (base keys win); this contributor plugs the richer view -
 * installed formats, import sources, advisory-report columns, per-module capability rows and feature flags - onto that
 * single endpoint. The free controller serves {@code /api/capabilities} once, extended (never shadowed) by this
 * contribution, so no mapping override is needed for {@link DeploymentInfoController}'s view to reach that path.
 *
 * <p>The rich view reads live beans and the effective configuration ({@code Repositories}, {@code Settings}, the
 * provenance signer, the upstream fetcher), so this contributor is not discovered on the module path, where it would
 * be constructed with no deployment: it is a bean of the deployment, handed the supplier of its
 * {@link DeploymentInfoController}'s view, and the free controller merges the contributor beans of its own context
 * beside the discovered ones. Two deployments in one process each serve their own view. With no
 * {@code DeploymentInfoController} in the context this contributes an empty map, so the base map is served
 * byte-for-byte unchanged - the SPI's no-op-by-absence contract.
 *
 * <p><b>The merge reports a collision, so this side does not refuse one</b>. The free
 * {@link CapabilityContributor#merge} lets a base key win, which protects the product's own flags, and <em>names</em>
 * every entry it refuses, in the returned {@link CapabilityContributor.Merged} report and in the served body under
 * {@value CapabilityContributor#CONFLICTS_KEY}.
 *
 * <p>Throwing here instead would be <b>coarser than that report</b>: an exception out of {@link #capabilities} is
 * contained by the merge and recorded as a contributor <em>failure</em>, which drops this contribution
 * <b>entirely</b> - so one misspelled key would cost {@code /api/capabilities} the deployment's formats, import
 * sources, signals, modules and feature flags, where the merge drops exactly the one colliding key and serves the
 * rest. Preventing a silent drop by causing a loud, larger one is the wrong trade on a read surface whose whole job
 * is to say what this deployment can do.
 *
 * <p>The guard is a <b>build-time</b> one rather than a request-time one, which is where a key-naming mistake
 * belongs: {@code CoreControllerSplitE2ETest} names the six component keys {@link DeploymentInfoController}
 * contributes, asserts the real served body carries them all, and asserts the remaining top-level keys are exactly
 * {@link #FREE_BASE_KEYS} - so a collision fails a build, not a request. {@link #extending} survives as the documented statement of the rule and as
 * the null-contribution normaliser.
 *
 * <p>This contribution does not carry the optional modules' feature flags. Each feature module contributes
 * its own flag directly through this same SPI, so {@code walk}, {@code gc}, {@code search}, {@code dependents},
 * {@code scan}, {@code provenance} and {@code audit} are top-level entries of the served document rather than
 * fields lifted into {@code FeaturesView} by a second fan-out. Two modules claiming one flag is caught by the one
 * merge, which serves a single value and names the refused contribution.
 */
public final class DeploymentCapabilities implements CapabilityContributor {

    /**
     * The keys the {@code RepositoryController.capabilities} puts in its base map before merging contributions.
     * A contribution may only <em>extend</em> that map: on a conflict the base wins - reported in
     * {@link CapabilityContributor.Merged#conflicts()} and under {@value CapabilityContributor#CONFLICTS_KEY} - so a
     * contributed key spelled like one of these is not served. Kept live by
     * {@code CoreControllerSplitE2ETest.the_console_shell_and_deployment_info_reads_serve},
     * which reads the real merged body rather than trusting this list.
     */
    public static final Set<String> FREE_BASE_KEYS = Set.of("readOnly", "auth", "anonymousRights");

    /** The deployment's live rich-capabilities view, asked per request; it answers {@code null} where the
     *  deployment carries no {@link DeploymentInfoController}. */
    private final Supplier<Map<String, Object>> supplier;

    /** A contribution of the view {@code richCapabilities} supplies - the deployment's
     *  {@link DeploymentInfoController}'s, so a request renders exactly its current formats / import-sources /
     *  module-flags. */
    public DeploymentCapabilities(Supplier<Map<String, Object>> richCapabilities) {
        this.supplier = Objects.requireNonNull(richCapabilities, "richCapabilities");
    }

    /** {@inheritDoc}
     *
     *  <p>The {@code configuration} operator is unused: this contribution is the {@link DeploymentInfoController}'s
     *  own live view, already resolved through that controller's pin-over-stored-over-environment chain, which is
     *  strictly richer than the boot-layered chain the controller merges with. Resolving it a second time here
     *  would answer the same question through the weaker chain. */
    @Override
    public Map<String, Object> capabilities(UnaryOperator<String> configuration) {
        return extending(supplier.get());
    }

    /**
     * The rich view, normalised: a contributor's "nothing to contribute" is an empty map, and a {@code null} supplier
     * answer means the same (the SPI's absence sentinel).
     *
     * <p>It deliberately does <b>not</b> refuse a key {@link #FREE_BASE_KEYS the base map already owns}.
     * The free core's merge names every refused entry in its {@link CapabilityContributor.Merged} report and in the
     * served body, so a collision is never silent - and throwing here is strictly worse than the report, because
     * the merge contains the exception and drops this contribution WHOLE where the rule drops the one colliding
     * key. The rule is enforced where a naming mistake can be fixed for free: at build time, by
     * {@code CoreControllerSplitE2ETest}'s served-body key assertions.
     */
    static Map<String, Object> extending(Map<String, Object> contribution) {
        return contribution == null ? Map.of() : contribution;
    }
}
