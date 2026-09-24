package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/** Schedules {@link TrustedRootTask} unless {@code signature-sigstore-trusted-root-url} was written empty, which is
 *  how a deployment says it fetches no root; a deployment that pasted its own root keeps it and the pass, finding
 *  one, fetches nothing. */
public final class TrustedRootTaskProvider implements MaintenanceTaskProvider {

    public TrustedRootTaskProvider() {
    }

    @Override
    public String name() {
        return TrustedRootTask.NAME;
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        if (!TrustedRootTask.enabled(config)) {
            return Optional.empty();
        }
        return Optional.of(new TrustedRootTask(TrustedRootTask.INTERVAL.resolve(config)));
    }
}
