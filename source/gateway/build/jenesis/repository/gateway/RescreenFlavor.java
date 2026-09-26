package build.jenesis.repository.gateway;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.compliance.ComplianceGate;
import build.jenesis.repository.compliance.GatePolicyProvider;
import build.jenesis.repository.inventory.OriginSection;
import build.jenesis.repository.inventory.StoreRepositoryInventory;
import build.jenesis.repository.metadata.MetadataStore;
import build.jenesis.repository.store.ArtifactDescriptor;

/**
 * Which gate flavour a <em>stored</em> artifact is re-screened through - the one decision shared by the two legs that
 * re-screen bytes already in the store: the bulk {@link MigrationRescreenTask} sweep and the request-time
 * {@link HardenedHitVerify}. Neither may pick a flavour of its own; both call in here, so the answer cannot
 * drift between the sweep and the serve boundary that consumes the verdict it recorded.
 *
 * <h2>Why the flavour is not simply {@code PROXY}</h2>
 *
 * The gate has two flavours and {@link GatePolicyProvider} states, twice, that the flavour belongs to the artifact
 * rather than to the caller:
 * <ul>
 *   <li><b>Clause 2 (idempotency / replay)</b> - "a re-screen of a stored artifact reproduces the verdict it was
 *       published under rather than drifting". An artifact that was <em>uploaded</em> was published under the
 *       {@link GatePolicyProvider.Path#PUBLISH} gate, so that is the verdict a re-screen has to reproduce.</li>
 *   <li><b>The declared-asymmetry clause</b> - a {@code SYMMETRIC} dimension "must reach the same findings on both
 *       legs ... because that is exactly what the declaration promises a reader, an operator and the re-screen that
 *       assesses a stored artifact <em>through whichever flavor it was reached by</em>".</li>
 * </ul>
 *
 * <p>For a plain {@code fallback <url> harden} repository the two readings agree with an unconditional
 * {@code PROXY}: the repository accepts no upload, so every byte in its store was reached by the fallback. They part
 * company on the hybrid - a {@code writable} repository carrying a
 * hardened upstream fallback, which {@link RepositoryDefinition#harden()} (rightly) reports as a hardening
 * proxy. Its store holds <em>both</em> channels, and re-screening an upload through the proxy flavour is wrong in both
 * directions:
 * <ul>
 *   <li><b>Destructively.</b> {@link GatePolicyProvider.Symmetry#PROXY_ONLY} dimensions exist precisely because they
 *       have nothing to say about an upload - "a private coordinate being published is exactly what the tenant
 *       intends, while the same coordinate arriving from an upstream is the dependency-confusion shadow". Screened
 *       through the proxy flavour, a tenant's own reserved-namespace upload draws the private-name dimension's
 *       default {@code QUARANTINE}, and both re-screen legs answer a non-{@code ALLOW} verdict by <em>evicting</em>
 *       the artifact. The reserved-name rule written to protect the tenant's package would delete it.</li>
 *   <li><b>Permissively.</b> A {@link GatePolicyProvider.Symmetry#SOFTENED_ON_PROXY} dimension is deliberately weaker
 *       on the proxy (the licence dimension allows an unknown licence there). An upload re-screened through it earns
 *       a digest-pinned {@code ALLOW} the publish gate would have refused - and {@link HardenedHitVerify} then serves
 *       every later hit on that record without re-screening.</li>
 * </ul>
 *
 * <h2>The evidence: the {@code origin} acquisition trail, not a guess</h2>
 *
 * The channel an artifact arrived through is already recorded durably, per digest, by {@link OriginSection}:
 * a hand upload folds a {@code local-upload} row into the very CAS that commits the publish, and a fallback
 * fetch appends a {@code fallback} row. So this reads the artifact's own coordinate document and answers
 * {@link GatePolicyProvider.Path#PUBLISH} exactly when a {@code local-upload} row names <em>these</em> bytes.
 *
 * <p><b>Everything else stays {@code PROXY}</b> - no origin record, no metadata module, an unreadable document, a row
 * naming other bytes. The default is deliberately the unchanged one, so this can only ever move an artifact off the
 * proxy flavour on positive durable evidence that it was uploaded; a hardened proxy with a pre-cache (the
 * migration case the sweep exists for) keeps being screened exactly as it is today, private-name dimension included.
 * It is the same direction {@code InventoryEviction.reclaimFallbackCache} already takes for the same destructive
 * question - "a quota sweep never blindly reclaims a blob it cannot classify" - read off the same durable record.
 *
 * <p>The lookup is deliberately keyed by the <em>format</em> coordinate ({@link StoreRepositoryInventory#describe}),
 * because that is where {@code ComplianceScreen} writes the upload row; the sibling {@code fallback} rows and the
 * {@code verdict} record are keyed by {@link HardenedScreen#coordinate} instead. Reading the upload row where uploads
 * write it is what makes the evidence positive rather than absent-by-construction.
 */
public final class RescreenFlavor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RescreenFlavor.class);

    private RescreenFlavor() {
    }

    /**
     * The gate flavour {@code digest}, stored at request path {@code path}, must be re-screened through:
     * {@link GatePolicyProvider.Path#PUBLISH} when the coordinate's {@code origin} trail carries a
     * {@code local-upload} row for exactly those bytes, {@link GatePolicyProvider.Path#PROXY} otherwise. A
     * {@code null} {@code metadata} (no persistence module installed) or a read failure answers {@code PROXY} - the
     * unchanged default - and the read failure is logged rather than swallowed (§9).
     *
     * <p>{@code inventory} is taken rather than built here because constructing one runs a {@link java.util.ServiceLoader}
     * scan ({@code MetadataProvider.installed()} caches nothing, by its own contract), and the sweep asks this question
     * once per cached artifact - so the caller hoists it out of its loop.
     */
    public static GatePolicyProvider.Path of(StoreRepositoryInventory inventory, MetadataStore metadata, String path,
                                             String digest) {
        if (metadata == null || digest == null || digest.isBlank()) {
            return GatePolicyProvider.Path.PROXY;
        }
        Optional<ArtifactDescriptor> described = inventory.describe(path);
        if (described.isEmpty() || described.get().coordinate() == null || described.get().version() == null) {
            return GatePolicyProvider.Path.PROXY;   // no format places this path: nothing to key the upload row off
        }
        ArtifactDescriptor descriptor = described.get();
        try {
            for (OriginSection.Acquisition row : OriginSection.acquisitions(metadata.section(
                    descriptor.ecosystem(), descriptor.coordinate(), descriptor.version(), OriginSection.TAG))) {
                if (row.localUpload() && digest.equals(row.sha256())) {
                    return GatePolicyProvider.Path.PUBLISH;
                }
            }
        } catch (IOException | RuntimeException read) {
            LOGGER.warn("Could not read the origin trail for " + path + "; re-screening through the proxy gate", read);
        }
        return GatePolicyProvider.Path.PROXY;
    }

    /**
     * The gate itself: {@link #of} resolved against {@code gates}, the caller's flavour-to-gate lookup. A {@code null}
     * {@code gates} (an ungated tenant) yields {@code null}, which every caller already treats as "no screen".
     */
    public static ComplianceGate gate(Function<GatePolicyProvider.Path, ComplianceGate> gates,
                                      StoreRepositoryInventory inventory, MetadataStore metadata, String path,
                                      String digest) {
        return gates == null ? null : gates.apply(of(inventory, metadata, path, digest));
    }
}
