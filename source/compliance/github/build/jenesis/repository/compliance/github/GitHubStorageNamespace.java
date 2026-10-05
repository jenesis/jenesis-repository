package build.jenesis.repository.compliance.github;

import module java.base;
import build.jenesis.repository.compliance.SignalContext;
import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The GitHub advisory feed's storage manifest: the signal space its change log is committed into,
 * {@code .system/config/signals/github}. Deployment-global, as every signal's space is: what the database changed is
 * the same public data for every tenant, drawn once. It sits under {@link SignalContext#SNAPSHOT_ROOT}, inside the {@code config} space, so the orphan diagnostic and
 * the named purge see it like any other key-space, and absence never deletes it.
 */
public final class GitHubStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> sharedPrefixes() {
        return Set.of(SignalContext.SNAPSHOT_ROOT + "/github");
    }
}
