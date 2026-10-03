package build.jenesis.repository.test;

import java.util.List;

import build.jenesis.repository.store.ArtifactDescriptor;
import build.jenesis.repository.store.PublishInterceptor;

/**
 * A test-only publication screen, discovered like a real one, that lets a test exercise the whole verdict range
 * without a compliance module on the path. It is inert for every ordinary path - {@code ACCEPT}, so the rest of the
 * server suite publishes and serves exactly as before - and reacts only to two distinctive marker tokens a test puts
 * in an entry name: a path containing {@code gate-quarantine} is quarantined, one containing {@code gate-reject} is
 * rejected. That keeps the batch-upload test able to assert per-entry {@code quarantined}/{@code rejected} outcomes
 * off the same discovered chain the downstream gate rides, with zero effect on any other test.
 */
public final class MarkerInterceptor implements PublishInterceptor {

    static final String QUARANTINE_MARKER = "gate-quarantine";
    static final String REJECT_MARKER = "gate-reject";

    @Override
    public Disposition assess(ArtifactDescriptor artifact, Content content) {
        String path = artifact.path();
        if (path == null) {
            return Disposition.ACCEPT;
        }
        if (path.contains(REJECT_MARKER)) {
            return Disposition.REJECT;
        }
        if (path.contains(QUARANTINE_MARKER)) {
            return Disposition.QUARANTINE;
        }
        return Disposition.ACCEPT;
    }

    /** Why it held or refused, as a gate would say it, so a test can read it back from the answer. */
    @Override
    public List<String> reasons(ArtifactDescriptor artifact) {
        return List.of("The test gate " + (artifact.path().contains(REJECT_MARKER) ? "refuses" : "holds")
                + " a marked path.");
    }
}
