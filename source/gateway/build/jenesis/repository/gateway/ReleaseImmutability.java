package build.jenesis.repository.gateway;

import module java.base;
import module org.slf4j;
import build.jenesis.repository.format.ArtifactLayout;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.inventory.OriginSection;
import build.jenesis.repository.metadata.MetadataProvider;
import build.jenesis.repository.metadata.Section;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Publication;
import build.jenesis.repository.server.kernel.LiveConfig;

/**
 * Release-version immutability, enforced on the deploy path <em>before</em> a screened upload is
 * laid out. A re-publish of an immutable RELEASE coordinate whose bytes DIFFER from the incumbent is refused with a
 * loud, named {@code 409} (§9) rather than silently re-pointing the {@code publish/<path>} pointer at the new
 * content - the supply-chain / dependency-confusion hazard this closes. Default-ON; an operator relaxes it per
 * tenant with {@code allow-redeploy=true} ({@link LiveConfig#allowRedeploy}).
 *
 * <p><b>Streaming (§1).</b> The check never materialises the body: it reads the incumbent's <em>pointer</em> - the
 * small {@code publish/<path>} document - through {@link Publication#blob(String)} and compares its stored hash to
 * the hash the gate already assigned the freshly-stored blob. No blob bytes are re-read on either side.
 *
 * <p><b>Origin-blind.</b> The guard keys off the existing pointer's hash, not how the incumbent arrived. So it fires
 * identically whether the incumbent was directly uploaded OR written by a caching/fallback leg (both write the same
 * {@code publish/<path>} pointer) - which is exactly what lets the caching legs reuse it with
 * no new mechanism.
 *
 * <p><b>What counts as an immutable release.</b> The predicate {@link #immutableReleaseArtifact} reuses two existing
 * signals rather than inventing a parallel release-detection heuristic:
 * <ol>
 *   <li>the installed layout knowledge - some installed {@link ArtifactLayout#describe} that CLAIMS the path must
 *       yield a concrete {@code coordinate}+{@code version}. The deploying plugin is asked first and every other
 *       installed format claiming the path after, because the layout capability that places a path is
 *       separable from the format the edge dispatched the write to; keying the guard on the plugin alone let a
 *       deployment where the two differ silently exempt every release that layout places. A path that describes to
 *       empty or coordinate-less (a generated index, a {@code maven-metadata.xml}, a checksum root, an npm packument /
 *       dist-tag root) is a mutable channel and is exempt; an ecosystem no installed {@link ArtifactLayout} places at
 *       all (raw, OCI's digest-addressed store and its mutable tag space, which resolve only through a
 *       {@code BlobLayout}) exposes no immutable-release pointer here and is exempt, exactly as before;</li>
 *   <li>the gateway's drift signal {@link HardenedScreen#immutableCoordinate} - the same predicate the hardened
 *       proxy uses to tell an immutable coordinate from a Maven {@code -SNAPSHOT}/mutable one, so a snapshot
 *       re-publish stays allowed exactly as its bytes re-publish under the same coordinate by design.</li>
 * </ol>
 */
public final class ReleaseImmutability {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReleaseImmutability.class);

    /** Release re-point refusals raised since boot - the loud {@code jenreg.immutability.refused} counter
     *  (§9), mirroring the hardened proxy's drift counter, so a refused re-point is observable, not silent. */
    private static final AtomicLong REFUSALS = new AtomicLong();

    private final LiveConfig live;

    public ReleaseImmutability(LiveConfig live) {
        this.live = live;
    }

    /** The number of release re-point refusals raised since boot. */
    public static long refusals() {
        return REFUSALS.get();
    }

    /**
     * Whether re-pointing {@code path} at {@code screenedHash} in {@code tenant}'s store must be refused: an incumbent
     * pointer exists, it names DIFFERENT bytes, the coordinate is an immutable release, and the tenant has not enabled
     * {@code allow-redeploy}. A first publish (no incumbent), a same-hash re-publish (idempotent), a snapshot/mutable
     * coordinate, or an opted-out tenant all return {@code false} (the publish proceeds). Reads only the pointer, never
     * the body (§1).
     */
    public boolean refusesRepoint(RepositoryFormat plugin, ArtifactStore store, String tenant, String path,
                                  String screenedHash) throws IOException {
        if (!guards(plugin, tenant, path)) {
            return false;
        }
        Optional<String> incumbent = new Publication(store).blob(path);
        return incumbent.isPresent() && !incumbent.get().equals(screenedHash);
    }

    /**
     * Whether {@code path} is an immutable release in {@code tenant}'s store - the coordinate class this guard
     * protects, whatever stands there now. The deploy edge refuses a visible re-point with {@link #refusesRepoint}
     * and holds the layout to the same question inside the pointer's compare-and-set, so two first publishes that
     * both found the path empty cannot both land.
     */
    public boolean guards(RepositoryFormat plugin, String tenant, String path) throws IOException {
        return !live.allowRedeploy(tenant) && immutableReleaseArtifact(plugin, path);
    }

    /** Record and log the loud, named refusal (§9), returning the client-facing message. Called by the deploy path
     *  once it has decided to answer {@code 409}. The {@code store} is read for the incumbent's ORIGIN:
     *  the incumbent pointer's hash keys the {@link OriginSection origin} record, so the message can name whether the
     *  colliding version arrived by a hand upload or was cached from a named fallback - the audit fact the operator
     *  needs to choose the remedy (evict the cached copy, or {@code allow-redeploy}). Both reads are of tiny pointer
     *  and section objects, never the artifact body (§1). */
    public String recordRefusal(RepositoryFormat plugin, ArtifactStore store, String path) throws IOException {
        REFUSALS.incrementAndGet();
        String coordinate = coordinate(plugin, path);
        String origin = incumbentOrigin(store, path);
        LOGGER.warn("RELEASE IMMUTABILITY: refused re-pointing already-published release '" + coordinate + "' at '"
                + path + "'" + origin + " with different bytes (409); enable allow-redeploy or evict the version"
                + " to replace it");
        return message(coordinate, path, origin);
    }

    /**
     * The origin clause naming <em>how the incumbent arrived</em>. The guard itself stays origin-blind (it still
     * refuses {@code 409} regardless); this only makes the MESSAGE origin-aware. It joins the {@link OriginSection
     * origin} record by the incumbent {@code publish/<path>} pointer's hash: a {@code fallback} row whose bytes match
     * the incumbent yields "cached from fallback '&lt;target&gt;' at &lt;instant&gt;" (so the operator learns the
     * colliding version was a cached-fallback copy, not a hand upload - the hybrid host+proxy's sharp edge), a {@code
     * local-upload} row yields "uploaded at &lt;instant&gt;", and an unrecorded or unreadable origin yields the empty
     * clause (the message then degrades to the plain wording). The origin lookup asks {@link
     * HardenedScreen#originCoordinate} for the key rather than deriving one, so it cannot fall behind the writer: this
     * javadoc used to say it was "keyed exactly as the fallback-fetch path records it" and then restate that
     * derivation, which stopped being true the moment the row moved to the format coordinate - the clause would have
     * gone quietly missing from every 409 on a format-claimed path. Best-effort read (&sect;10): never fails the
     * already-decided 409.
     */
    private String incumbentOrigin(ArtifactStore store, String path) {
        Optional<MetadataProvider> provider = MetadataProvider.installed();
        if (provider.isEmpty()) {
            return "";
        }
        try {
            String incumbentHash = new Publication(store).blob(path).orElse(null);
            if (incumbentHash == null) {
                return "";
            }
            HardenedScreen.Coordinate coordinate = HardenedScreen.originCoordinate(store, path);
            Optional<Section> section = provider.get().over(store)
                    .section(coordinate.ecosystem(), coordinate.coordinate(), coordinate.version(), OriginSection.TAG);
            for (OriginSection.Acquisition row : OriginSection.acquisitions(section)) {
                if (!incumbentHash.equals(row.sha256())) {
                    continue;
                }
                if (row.fallback()) {
                    String named = row.target() != null ? row.target() : row.repository();
                    return ", cached from fallback '" + named + "'" + (row.at() == null ? "" : " at " + row.at());
                }
                if (row.localUpload()) {
                    return ", uploaded" + (row.at() == null ? "" : " at " + row.at());
                }
            }
            return "";
        } catch (IOException | RuntimeException e) {
            // Render-what-you-have (§10): a metadata read failure only costs the origin phrase, never the loud 409.
            LOGGER.warn("Could not name the incumbent origin for " + path, e);
            return "";
        }
    }

    /** The loud, named {@code 409} message (§9), now origin-aware: {@code originClause} names how the
     *  incumbent arrived (a cached fallback copy vs a hand upload) when the origin record has it, or is empty (the plain
     *  wording) when it does not. Origin-blind by construction - the message is richer, the refusal unchanged. */
    private static String message(String coordinate, String path, String originClause) {
        return "Release '" + coordinate + "' at '" + path + "' is already published with different bytes" + originClause
                + "; release-version immutability refuses re-pointing it. Delete/evict the existing version, or enable "
                + "allow-redeploy, to replace it.";
    }

    /**
     * Whether {@code path} names an immutable release artifact - see the class javadoc: a concrete coordinate+version
     * under some installed {@link ArtifactLayout} AND not a snapshot/mutable channel per the gateway drift signal.
     * Package-private for the structural test that pins the predicate's decisions.
     *
     * <p><b>Asked of every installed layout, not of the deploying plugin alone.</b> The capability that places
     * a path as a coordinate+version is separable from the format that handles the write - the product ships exactly
     * such a split in {@code format.oci-inventory}, whose layout lives in a module of its own - so keying the guard on
     * {@code plugin instanceof ArtifactLayout} made release immutability a function of which module happens to carry
     * the layout: move it or drop it and every release it places is silently exempted, and an already-published release
     * can be re-pointed at different bytes with no {@code 409}. The incumbent {@code publish/<path>} pointer is durable
     * and says a release stands here; which module can name it is discovery, and discovery must not decide whether the
     * release is protected. {@code plugin} is still consulted first so a plugin that IS the layout answers exactly as
     * before, on the hot path, with no discovery at all.
     *
     * <p>The fan-out is the primary pass of {@code StoreRepositoryInventory.describe}, the product's one owner of
     * "which coordinate does this path name": only a layout that CLAIMS the path is asked. The inventory's
     * capability-only fallback - consulting a provider that does <em>not</em> claim the path - is deliberately NOT
     * extended to {@link ArtifactLayout} here, and the inventory does not extend it either: it consults only
     * {@code BlobLayout} providers there, because a layout's {@code describe} is a
     * parser and a lax one answers for a foreign path it was never shown (several installed layouts do, for an OCI
     * {@code /v2/} path). A parser that would mis-place another format's path must never be able to turn a mutable
     * channel into a {@code 409}.
     *
     * <p>Restricted to {@link ArtifactLayout} on purpose: the deliberately-mutable hosted stores stay exempt exactly as
     * they were - {@code raw} declares no layout at all, and OCI's tag space resolves only through a
     * {@code BlobLayout}, so a tag re-push is as mutable as it has always been.
     */
    static boolean immutableReleaseArtifact(RepositoryFormat plugin, String path) {
        if (plugin instanceof ArtifactLayout layout && places(layout, path)) {
            return HardenedScreen.immutableCoordinate(path);
        }
        for (RepositoryFormat installed : Layouts.INSTALLED) {
            if (installed.handles(path) && installed instanceof ArtifactLayout layout && places(layout, path)) {
                return HardenedScreen.immutableCoordinate(path);
            }
        }
        return false;
    }

    /** The installed format list, resolved once through the SPI home's own {@link RepositoryFormat#installed()} and
     *  held for the life of the JVM - the same deployment-static snapshot {@code StoreRepositoryInventory} keeps, and
     *  for the same reason: this predicate is on the per-upload deploy path and must not re-run discovery per write.
     *  Held on a nested class so the resolution (which throws on a duplicate format name, a packaging error) happens on
     *  first use rather than at this class's initialization. */
    private static final class Layouts {
        private static final List<RepositoryFormat> INSTALLED = RepositoryFormat.installed();
    }

    /** Whether a layout maps {@code path} to a concrete coordinate AND version - the "this is a release artifact, not a
     *  mutable channel" half of the predicate. A path that describes to empty or coordinate-less (a generated index, a
     *  {@code maven-metadata.xml}, a checksum root, an npm packument / dist-tag root) is a mutable channel and answers
     *  {@code false}. A hostile layout that throws is contained and answers {@code false}: the guard must never fail a
     *  deploy with a foreign format's parse error, and the incumbent-pointer half of {@link #refusesRepoint} is what
     *  decides anything at all. */
    private static boolean places(ArtifactLayout layout, String path) {
        try {
            Optional<ArtifactDescriptor> described = layout.describe(path);
            return described.isPresent() && described.get().coordinate() != null && described.get().version() != null;
        } catch (RuntimeException hostile) {
            LOGGER.warn("Layout " + layout.getClass().getName() + " could not describe " + path
                    + "; it does not place this path for the release-immutability guard", hostile);
            return false;
        }
    }

    /** The {@code coordinate:version} the format parses from {@code path}, or the raw {@code path} when it carries no
     *  full coordinate - the name the refusal message and log carry. */
    private static String coordinate(RepositoryFormat plugin, String path) {
        if (plugin instanceof ArtifactLayout layout) {
            Optional<ArtifactDescriptor> described = layout.describe(path);
            if (described.isPresent() && described.get().coordinate() != null && described.get().version() != null) {
                return described.get().coordinate() + ":" + described.get().version();
            }
        }
        return path;
    }
}
