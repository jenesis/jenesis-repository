package build.jenesis.repository.compliance.openssf;

import module java.base;
import build.jenesis.repository.compliance.SignalContext;
import build.jenesis.repository.maintenance.StorageNamespace;

/** The malicious-package feed's signal space: the change log it draws from OSV's change lists. */
public final class OpenSsfStorageNamespace implements StorageNamespace {

    @Override
    public Set<String> sharedPrefixes() {
        return Set.of(SignalContext.SNAPSHOT_ROOT + "/openssf");
    }
}
