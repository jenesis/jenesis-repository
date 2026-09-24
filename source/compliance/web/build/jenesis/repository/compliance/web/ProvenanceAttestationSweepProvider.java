package build.jenesis.repository.compliance.web;

import module java.base;

import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the provenance-attestation reclamation sweep.
 *
 * <p>Daily by default. The residue it clears is derived data that costs storage rather than correctness - nothing
 * serves wrongly while an orphaned attestation sits there - so a slow cadence is the right one: the event-driven
 * reaper handles the ordinary deletion promptly and this pass exists for what the notification could not carry.
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
        // Off unless turned on, like every other pass. There is no producer to latch here - attestations are written
        // by the signing path as legitimate data rather than as queue notes - so the flag governs the sweep alone.
        if (!Boolean.parseBoolean(config.apply("provenance-attestation-sweep"))) {
            return Optional.empty();
        }
        return Optional.of(new ProvenanceAttestationSweep(INTERVAL.resolve(config)));
    }
}
