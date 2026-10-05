package build.jenesis.repository.closure;

import module java.base;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/** Discovers the closure pass ({@link ClosureTask}), always scheduled: a repository that resolves none is skipped by
 *  its own setting, read per pass. */
public final class ClosureTaskProvider implements MaintenanceTaskProvider {

    /** How often newly published versions are resolved. */
    static final IntervalSetting INTERVAL = IntervalSetting.of("closure-interval", "PT5M");

    @Override
    public String name() {
        return ClosureTask.NAME;
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        return Optional.of(new ClosureTask(INTERVAL.resolve(config), QualityInspector.all()));
    }
}
