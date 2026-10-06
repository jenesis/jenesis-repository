package build.jenesis.repository.compliance.osv;

import module java.base;
import build.jenesis.repository.compliance.SignalContext;
import build.jenesis.repository.maintenance.StorageNamespace;

/**
 * The OSV feed's storage manifest: the signal space its change log is committed into, {@code .system/config/signals/osv},
 * and the mirror's, {@code .system/config/signals/osv-mirror}, holding its copy of the export and its own change log.
 * Deployment-global, as every signal's space is: the change lists are the same public data for every tenant, drawn
 * once. It sits under {@link SignalContext#SNAPSHOT_ROOT}, inside the {@code config} space, so the orphan diagnostic and
 * the named purge see it like any other key-space, and absence never deletes it.
 */
public final class OsvStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> sharedPrefixes() {
        return Set.of(SignalContext.SNAPSHOT_ROOT + "/osv", SignalContext.SNAPSHOT_ROOT + "/" + OsvMirrorSource.FEED);
    }
}
