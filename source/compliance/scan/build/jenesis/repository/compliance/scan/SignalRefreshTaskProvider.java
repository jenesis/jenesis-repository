package build.jenesis.repository.compliance.scan;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.RefreshableSource;
import build.jenesis.repository.compliance.SignalSource;
import build.jenesis.repository.compliance.SignalSourceProvider;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the signal-refresh pass. Unlike its siblings it is not gated on {@code scheduled-scan}: they re-screen an
 * inventory, an opt-in cost, while this one keeps an enabled signal's data present at all, and a gate running with
 * re-scans off would otherwise render a snapshot nothing draws.
 *
 * <p>It is scoped by the signals instead: only enabled sources that mirror ({@link RefreshableSource}) or publish their
 * changes ({@link AdvisorySource.Changes}) have anything to draw, so with none there is no task.
 *
 * <p>The cadence is an {@link IntervalSetting} rendered into {@link ScanSettingsContributor}.
 */
public final class SignalRefreshTaskProvider implements MaintenanceTaskProvider {

    /** How often the pass runs, which is not how often a vendor is drawn: a source inside its refresh window returns
     *  without a request, so this governs how quickly a cold deployment reaches its first snapshot and how quickly a
     *  failed draw is retried. Its own dial, since an hour is long for a gate to run without its catalogue. */
    static final IntervalSetting INTERVAL =
            IntervalSetting.millis("signal-refresh-interval-millis", "PT5M");

    @Override
    public String name() {
        return "signal-refresh";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        Map<String, RefreshableSource> mirrors = new LinkedHashMap<>();
        Map<String, AdvisorySource.Changes> changes = new LinkedHashMap<>();
        // SignalSource.class yields every enabled source: refreshing is a property of how a source holds its data, and
        // publishing its changes of how a feed answers.
        SignalSourceProvider.named(SignalSource.class, config).forEach((signal, source) -> {
            if (source instanceof RefreshableSource mirror) {
                mirrors.put(signal, mirror);
            }
            if (source instanceof AdvisorySource.Changes changed) {
                changes.put(signal, changed);
            }
        });
        return mirrors.isEmpty() && changes.isEmpty()
                ? Optional.empty()
                : Optional.of(new SignalRefreshTask(INTERVAL.resolve(config), mirrors, changes));
    }
}
