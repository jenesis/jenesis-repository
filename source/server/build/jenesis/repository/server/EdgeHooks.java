package build.jenesis.repository.server;

import module java.base;

import build.jenesis.repository.format.FormatExchange;
import build.jenesis.repository.format.RepositoryFormat;
import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * The edge plug-in seam ingress concerns are contributed through, so they <em>plug in</em> to the one shared
 * screening edge ({@link ScreenedDispatch}) rather than a second deploy controller. With the {@link #NONE no-op
 * default} bound the write choreography (screen &rarr; lay out &rarr;
 * {@link build.jenesis.repository.store.Publication#published published}; {@code QUARANTINE} &rarr; {@code 202};
 * {@code REJECT} &rarr; {@code 422}) runs alone. The gateway's implementation binds a tenant, refuses an
 * immutable-release overwrite, records a quarantine-dispatch replay context and observes each deploy - concerns
 * that must run at the edge (they need the claiming {@link RepositoryFormat} and the post-hash, pre-layout moment),
 * which is why they live here rather than as a store {@link PublishInterceptor}.
 *
 * <p>Each hook is a no-op by default, so an implementation overrides only the points it cares about. The three points
 * bracket the {@code ACCEPT}/{@code QUARANTINE}/{@code REJECT} choreography the edge already runs:
 * <ul>
 *   <li>{@link #beforeLayout} runs on {@code ACCEPT}, after the screen chain assigned the blob's {@code hash} but
 *       <em>before</em> the format lays it out. A present {@link Refusal} short-circuits: the edge answers it and lays
 *       nothing out and fires no {@code published()} - the seam the gateway's hooks fire the release-immutability
 *       {@code 409} from.</li>
 *   <li>{@link #held} runs on the {@code QUARANTINE} branch, around the {@code 202}, so the gateway's hooks record the
 *       quarantine-dispatch replay context for the held body.</li>
 *   <li>{@link #verdict} runs once per screened write with the chain's final disposition, for the deploy
 *       observation/metric.</li>
 * </ul>
 */
public interface EdgeHooks {

    /** The no-op default the core binds: every hook declines, so the shared edge behaves exactly as if no
     *  edition plugged in. */
    EdgeHooks NONE = new EdgeHooks() {
    };

    /** An edge refusal a {@link #beforeLayout} hook returns to short-circuit a write it will not admit: the HTTP
     *  {@code status} to answer and a human-readable {@code message}. The gateway's hooks return a {@code 409} from
     *  here when a write would overwrite an immutable release. A {@code null} message answers with an empty body. */
    record Refusal(int status, String message) {
    }

    /** Called on {@code ACCEPT} after the screen chain stored the body under {@code hash} but before the
     *  {@link RepositoryFormat} lays it out. Returning a {@link Refusal} short-circuits the write (the edge answers the
     *  refusal, lays nothing out and fires no {@code published()}); {@link Optional#empty()} lets the layout proceed.
     *  No-op (accept) by default. */
    default Optional<Refusal> beforeLayout(RepositoryFormat format, ArtifactStore store, ArtifactDescriptor descriptor,
                                           String hash, FormatExchange exchange) throws IOException {
        return Optional.empty();
    }

    /**
     * Whether the layout of an accepted write to {@code path} is held to the pointer it is about to replace: run
     * through {@link build.jenesis.repository.store.Publication#guarded}, so a link of that path meeting a pointer that
     * names other bytes is refused inside its own compare-and-set. A refusal {@link #beforeLayout} returns is decided
     * over the pointer as it stood when the hook read it; this is what keeps that decision true when a concurrent
     * write lands between the read and the layout. Guards nothing by default.
     */
    default boolean guardsLayout(RepositoryFormat format, ArtifactStore store, String path) throws IOException {
        return false;
    }

    /**
     * Whether this publish may replace a released file - the {@code allow-redeploy} opt-out for the publishing tenant.
     * The edge binds the answer around the layout ({@link build.jenesis.repository.store.Publication#redeploying}),
     * and a format linking a released file honours it there. {@code false} by default: a release is immutable unless
     * an operator opted out.
     */
    default boolean redeploys(RepositoryFormat format, ArtifactStore store) throws IOException {
        return false;
    }

    /** Called on the {@code QUARANTINE} branch (the body is stored for review, not laid out), around the edge's
     *  {@code 202}, so an edition can record the held body's replay context - {@code path} is the request path and
     *  {@code hash} the stored blob. No-op by default. */
    default void held(RepositoryFormat format, ArtifactStore store, String path, String hash, FormatExchange exchange)
            throws IOException {
    }

    /** Called once per screened write with the chain's final {@code disposition} and the (hash-enriched) descriptor,
     *  for an edition's deploy observation or metric. Fires whatever the disposition, including when a
     *  {@link #beforeLayout} refusal short-circuited an otherwise-{@code ACCEPT} write. No-op by default. */
    default void verdict(PublishInterceptor.Disposition disposition, ArtifactDescriptor descriptor,
                         FormatExchange exchange) throws IOException {
    }
}
