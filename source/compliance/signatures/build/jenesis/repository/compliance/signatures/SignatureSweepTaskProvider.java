package build.jenesis.repository.compliance.signatures;

import module java.base;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/** Schedules {@link SignatureSweepTask} when {@code signature-sweep} is switched on; nothing otherwise. */
public final class SignatureSweepTaskProvider implements MaintenanceTaskProvider {

    public SignatureSweepTaskProvider() {
    }

    @Override
    public String name() {
        return SignatureSweepTask.NAME;
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        if (!SignatureSweepTask.enabled(config)) {
            return Optional.empty();
        }
        return Optional.of(new SignatureSweepTask(SignatureSweepTask.INTERVAL.resolve(config)));
    }
}
