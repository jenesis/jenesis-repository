package build.jenesis.repository.compliance.scan;

import module java.base;
import build.jenesis.repository.compliance.RefreshableSource;
import build.jenesis.repository.compliance.SignalSource;
import build.jenesis.repository.compliance.SignalSourceProvider;
import build.jenesis.repository.maintenance.IntervalSetting;
import build.jenesis.repository.maintenance.MaintenanceTask;
import build.jenesis.repository.maintenance.MaintenanceTaskProvider;

/**
 * Discovers the signal-refresh pass. Deliberately <em>not</em> gated on {@code scheduled-scan} the way its four
 * siblings in this module are: those passes re-screen a repository's inventory, which is an opt-in cost, while this
 * one is what keeps an <em>enabled</em> signal's data present at all. Gating it on the re-scan switch would leave a
 * deployment that runs the gate with {@code scheduled-scan} off holding a signal source that renders a snapshot
 * nothing ever draws - the shape that moving the draw off the query path must not create.
 *
 * <p>It is scoped by the signals themselves instead: {@link SignalSourceProvider#named} yields only the enabled
 * sources, and only those that mirror ({@link RefreshableSource}) have anything to refresh, so a deployment with no
 * mirroring signal enabled gets no task at all rather than a pass that does nothing every interval.
 *
 * <p>The cadence is held as an {@link IntervalSetting} constant and rendered into {@link ScanSettingsContributor} from
 * it, so the catalogue default cannot drift from the code.
 */
public final class SignalRefreshTaskProvider implements MaintenanceTaskProvider {

    /**
     * How often the pass runs - which is <em>not</em> how often a vendor is drawn. A mirroring source inside its own
     * refresh window renders and returns without a request, so this interval governs how quickly a cold deployment
     * (a first boot, a restore) reaches its first complete snapshot, and how quickly a failed draw is retried. Its own
     * dial rather than the scan family's hourly {@code scan-interval-millis}, because the two answer different
     * questions and an hour is a long time for a gate to run without its catalogue.
     */
    static final IntervalSetting INTERVAL =
            IntervalSetting.millis("signal-refresh-interval-millis", "PT5M");

    @Override
    public String name() {
        return "signal-refresh";
    }

    @Override
    public Optional<MaintenanceTask> create(UnaryOperator<String> config) {
        Map<String, RefreshableSource> mirrors = new LinkedHashMap<>();
        // SignalSource.class as the contract yields EVERY enabled source, whichever specialised contracts it answers -
        // the refresh is a property of how a source holds its data, not of which signal it contributes.
        SignalSourceProvider.named(SignalSource.class, config).forEach((signal, source) -> {
            if (source instanceof RefreshableSource mirror) {
                mirrors.put(signal, mirror);
            }
        });
        return mirrors.isEmpty()
                ? Optional.empty()
                : Optional.of(new SignalRefreshTask(INTERVAL.resolve(config), mirrors));
    }
}
