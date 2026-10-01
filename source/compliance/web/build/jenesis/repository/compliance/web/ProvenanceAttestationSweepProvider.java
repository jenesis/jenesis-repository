package build.jenesis.repository.compliance.web;

import module java.base;

import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the provenance-attestation reclamation sweep: daily by default, since its residue costs storage rather than
 * correctness and the reaper handles the ordinary deletion.
 */
public final class ProvenanceAttestationSweepProvider implements MaintenanceTaskProvider {

    static final IntervalSetting INTERVAL =
            IntervalSetting.of("provenance-attestation-sweep-interval", "P1D");

    @Override
    public String name() {
        return "provenance-attestation-sweep";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        // Off unless turned on.
        if (!Boolean.parseBoolean(config.apply("provenance-attestation-sweep"))) {
            return Optional.empty();
        }
        return Optional.of(new ProvenanceAttestationSweep(INTERVAL.resolve(config)));
    }
}
