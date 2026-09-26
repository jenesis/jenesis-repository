package build.jenesis.repository.gateway;

import module java.base;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.gate.QuarantineDispatch;
import build.jenesis.repository.server.EdgeHooks;
import build.jenesis.repository.server.Observations;
import build.jenesis.repository.server.RepositoryRouting;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.PublishTenant;
import build.jenesis.repository.server.kernel.PublishTenantFilter;
import io.micrometer.observation.ObservationRegistry;

/**
 * The {@link EdgeHooks}: it plugs the deploy edge's ingress concerns into the one shared free
 * screening edge ({@code ScreenedDispatch}) rather than forking a second deploy controller. Retiring the
 * {@code DeployController} onto {@code RepositoryController}, this bean carries the three concerns
 * that must run at the edge - they need the claiming {@link RepositoryFormat} and the post-hash, pre-layout moment,
 * which a store {@link PublishInterceptor} does not have:
 * <ul>
 *   <li>{@link #beforeLayout} fires the release-version immutability {@code 409}: after the screen chain has
 *       assigned the freshly-stored blob its {@code hash} but before the format lays it out, a re-point of an
 *       already-published immutable RELEASE coordinate at DIFFERENT bytes is refused. The refusal short-circuits the
 *       write (the edge answers it and lays nothing out, fires no {@code published()}), so the original bytes keep
 *       serving. Reads only the incumbent pointer's hash, never the body ({@link ReleaseImmutability} §1).</li>
 *   <li>{@link #held} records the {@link QuarantineDispatch} replay context around the {@code QUARANTINE} {@code 202},
 *       so a later review release can replay {@code plugin.handle} from the stored publish envelope and actually
 *       materialise the version rather than link a raw envelope blob. This is the concern whose absence on the free
 *       fixed-tenancy write path was the -class drift the {@code DeployController} fork left: a fixed-tenancy hold
 *       recorded no dispatch context and so could not be released - moving it here fixes both tenancy modes at once.</li>
 *   <li>{@link #verdict} raises the {@code jenreg.deploy} observation once per screened write with the
 *       chain's disposition, so every accepted/quarantined/rejected deploy is observed exactly as the fork's own
 *       {@code Observations.observe} wrapper did.</li>
 * </ul>
 *
 * <p>The tenant a concern needs ({@link ReleaseImmutability#refusesRepoint}'s {@code allow-redeploy} lookup, the
 * observation's tenant tag) is read from {@link PublishTenant#current()}, bound for the request by the
 * {@link PublishTenantFilter} on {@code /repository/**} and {@code /v2/**} - the same per-thread binding the fork opened
 * around its own {@code Publication.screen} call, now opened by the filter so the edge and this bean both see it.
 * {@link ReleaseImmutability}/{@code HoldLifecycle}/{@link QuarantineDispatch}/audit all stay behind the hook; only the
 * {@code EdgeHooks} interface moved down beside the edge for this bean to implement.
 */
public final class DeployEdgeHooks implements EdgeHooks {

    private final ReleaseImmutability immutability;
    private final ObservationRegistry observations;

    public DeployEdgeHooks(LiveConfig live, ObservationRegistry observations) {
        this.immutability = new ReleaseImmutability(live);
        this.observations = observations;
    }

    /**
     * Release-version immutability (§9): BEFORE the format lays out the version, refuse a re-point of an
     * already-published immutable RELEASE coordinate at DIFFERENT bytes (default-on, {@code allow-redeploy} opt-out).
     * The check reads only the incumbent {@code publish/<path>} pointer's hash (§1, never the body) and is origin-blind
     * (keys off the pointer, not how the incumbent arrived), so a {@code 409} refusal fires here and nothing is laid out
     * or re-pointed. A same-hash re-publish, a first publish, a snapshot/mutable coordinate, or an opted-out tenant fall
     * through and lay out as before.
     */
    @Override
    public Optional<Refusal> beforeLayout(RepositoryFormat format, ArtifactStore store, ArtifactDescriptor descriptor,
                                          String hash, FormatExchange exchange) throws IOException {
        String tenant = PublishTenant.current();
        String path = exchange.path();
        if (immutability.refusesRepoint(format, store, tenant, path, hash)) {
            return Optional.of(new Refusal(409, immutability.recordRefusal(format, store, path)));
        }
        return Optional.empty();
    }

    /** An immutable release's layout is held to the pointer it replaces, so the check above cannot be raced by a
     *  concurrent first publish of the same release: the loser meets the winner's pointer at its own write and is
     *  answered {@code 409}. */
    @Override
    public boolean guardsLayout(RepositoryFormat format, ArtifactStore store, String path) throws IOException {
        return immutability.guards(format, PublishTenant.current(), path);
    }

    /**
     * The gate held this upload before the format laid it out, so the stored blob is the raw publish envelope (an npm
     * packument, a NuGet/PyPI multipart), never the served artifact. Record the dispatch context beside the hold - the
     * claiming format, method, body hash and framing headers - so a later review release can replay {@code plugin.handle}
     * from it and actually materialise the version, rather than linking a raw envelope blob that installs nothing and
     * strands a phantom pointer.
     */
    @Override
    public void held(RepositoryFormat format, ArtifactStore store, String path, String hash, FormatExchange exchange)
            throws IOException {
        QuarantineDispatch.record(store, path, format.name(), exchange.method(), hash,
                QuarantineDispatch.capture(exchange::requestHeader));
    }

    /** One verdict per screened write, raised as the {@code jenreg.deploy} observation tagged with the
     *  publishing tenant ({@link PublishTenant#current()}), the repository the request addressed and the chain's
     *  disposition - the deploy signal the fork emitted from its own {@code Observations.observe} wrapper. */
    @Override
    public void verdict(PublishInterceptor.Disposition disposition, ArtifactDescriptor descriptor,
                        FormatExchange exchange) throws IOException {
        String repository = repositoryOf(exchange.requestUri());
        Observations.observe(observations, "jenreg.deploy", repository, PublishTenant.current(),
                observation -> {
                    observation.lowCardinalityKeyValue("verdict", disposition.name());
                    return null;
                });
    }

    /** The repository a write addressed, read the way every routing reads it - or {@code null} when the request
     *  names none, which {@link Observations} tags as {@code none}. */
    private static String repositoryOf(String requestUri) {
        if (requestUri == null) {
            return null;
        }
        String repository = RepositoryRouting.target(requestUri).repository();
        return repository.isEmpty() ? null : repository;
    }
}
