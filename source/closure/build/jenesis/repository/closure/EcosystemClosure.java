package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;

/**
 * How one ecosystem resolves a release's closure where reading each version's declarations and evaluating a
 * requirement is not enough - Maven, whose dependencies inherit from parents, import BOMs and interpolate properties,
 * is resolved by its own resolver. An ecosystem without one is walked by {@link ClosureResolver}.
 *
 * <h2>Contract</h2>
 * <ol>
 *   <li><b>Thread-safety.</b> Shared and stateless: the closure pass may call it for several versions at once.</li>
 *   <li><b>Read purity.</b> It reads only {@code store} - the repository the release was published to - and makes no
 *       network request: what is not held is a cut.</li>
 *   <li><b>Absence sentinel.</b> {@link Optional#empty()} where it cannot resolve this release at all - a Maven release
 *       with no POM - so the walk by declarations answers instead; a resolution it began is a closure, partial where a
 *       subtree failed, never an exception.</li>
 *   <li><b>Bounded work.</b> It stops at {@link ClosureResolver#MAX_COMPONENTS} components and says so.</li>
 *   <li><b>Selection.</b> {@code OPTIONAL_UNIQUE} per ecosystem: two naming one ecosystem fail {@link #of}.</li>
 * </ol>
 */
public interface EcosystemClosure {

    /** The ecosystem this resolves, in the canonical spelling a version's document uses. */
    String ecosystem();

    /** {@code coordinate} at {@code version}'s closure as {@code store} holds it, as of {@code now}. */
    Optional<ClosureSection.Closure> resolve(ArtifactStore store, String coordinate, String version, Instant now)
            throws IOException;

    /** The installed one for {@code ecosystem}, if any. */
    static Optional<EcosystemClosure> of(String ecosystem) {
        return Optional.ofNullable(InstalledClosures.CLOSURES.get(ecosystem));
    }
}
