package build.jenesis.repository.application;

import module java.base;

import build.jenesis.repository.server.spi.CapabilityContributor;

/**
 * This deployment's contribution to {@code /api/capabilities}: the installed formats, import sources, report columns,
 * module rows and feature flags of {@link DeploymentInfoController}, merged by
 * {@link build.jenesis.repository.server.RepositoryController#capabilities} onto its base map.
 *
 * <p>It is a bean, not a discovered provider, because the view reads the deployment's live beans; the controller
 * merges its own context's contributor beans beside the discovered ones. With no {@code DeploymentInfoController} in
 * the context it contributes nothing. Each optional module contributes its own feature flag through the same SPI.
 *
 * <p>A key colliding with the base map is not refused here: {@link CapabilityContributor#merge} keeps the base value
 * and names the refused entry under {@value CapabilityContributor#CONFLICTS_KEY}, while a throw would drop this whole
 * contribution.
 */
public final class DeploymentCapabilities implements CapabilityContributor {

    /** The keys of the controller's base map, which win over a contribution spelled the same. */
    public static final Set<String> FREE_BASE_KEYS = Set.of("readOnly", "auth", "anonymousRights");

    /** The live view, asked per request; {@code null} when the deployment carries no
     *  {@link DeploymentInfoController}. */
    private final Supplier<Map<String, Object>> supplier;

    /** A contribution of the view {@code richCapabilities} supplies. */
    public DeploymentCapabilities(Supplier<Map<String, Object>> richCapabilities) {
        this.supplier = Objects.requireNonNull(richCapabilities, "richCapabilities");
    }

    /** {@inheritDoc}
     *
     *  <p>{@code configuration} is unused: the view is already resolved through the controller's pin-aware chain,
     *  which the boot-layered operator would weaken. */
    @Override
    public Map<String, Object> capabilities(UnaryOperator<String> configuration) {
        return extending(supplier.get());
    }

    /** The view, with a {@code null} answer normalised to the empty contribution. */
    static Map<String, Object> extending(Map<String, Object> contribution) {
        return contribution == null ? Map.of() : contribution;
    }
}
