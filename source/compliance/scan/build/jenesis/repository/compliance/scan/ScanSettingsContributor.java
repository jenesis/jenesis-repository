package build.jenesis.repository.compliance.scan;

import module java.base;
import build.jenesis.repository.compliance.KnownExploitedSource;
import build.jenesis.repository.inventory.IncrementalPasses;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes the scheduled re-scan's settings, so they surface on the settings screens exactly when this module is
 * installed.
 *
 * <p>The two cadence entries render their key and default straight off {@link VulnerabilityScanTaskProvider}'s and
 * {@link SignalRefreshTaskProvider}'s {@code IntervalSetting} constants, so the catalogue and the code cannot drift.
 * Both keys name their unit, so the default is rendered in milliseconds rather than as an ISO-8601 string: the entry
 * an operator edits is a {@code LONG}, and a duration string in a number field would be a default nobody could type
 * back. The scan cadence is the whole scan family's dial - six more passes read the same key from their own modules -
 * and the shared constant holds those to the same default, since they cannot share the constant
 * across module boundaries.
 *
 * <p>The signal refresh and the two known-exploited dials are listed only where a known-exploited catalogue is
 * installed: the refresh pass is created only for a mirroring source and the enforcement holds nothing without a
 * catalogue, so on a composition carrying none they are settings that change nothing - and the settings screen, the
 * generated reference and the boot check for unrecognised settings would all describe a capability the deployment
 * does not have. The answer is held, since installation is fixed for the life of a JVM and the catalogue is asked
 * for on every render of the settings screens.
 */
public final class ScanSettingsContributor implements SettingsContributor {

    private static final boolean KNOWN_EXPLOITED = !KnownExploitedSource.installed().isEmpty();

    @Override
    public List<Setting> settings() {
        List<Setting> settings = new ArrayList<>(List.of(
                new Setting("scheduled-scan", "Compliance", "Scheduled scan",
                        "Re-scan every repository's inventory against the advisory feeds on a schedule.",
                        Setting.Kind.BOOLEAN, "true", true).gate(),
                new Setting(VulnerabilityScanTaskProvider.INTERVAL.key(), "Compliance", "Scan interval",
                        "Milliseconds between scheduled scans; each pass hits the upstream feeds.",
                        Setting.Kind.LONG,
                        VulnerabilityScanTaskProvider.INTERVAL.fallbackMillis(), true),
                new Setting(IncrementalPasses.FULL_EVERY, "Compliance", "Full pass every",
                        "Every Nth scheduled pass of the advisory scan, and of every other pass that re-reads "
                                + "what the repository holds, re-reads every published version; the passes between "
                                + "read only the versions published since the last full pass, and a catalogue that "
                                + "changed asks for a full pass at once. A full pass reads one inventory document per published version, "
                                + "which over an object store is a round trip per version.",
                        Setting.Kind.LONG, String.valueOf(IncrementalPasses.DEFAULT_FULL_EVERY), true),
                new Setting(IncrementalPasses.LOOKBACK, "Compliance", "Full pass lookback",
                        "How far before the last full pass's stamp an incremental pass still looks. A full pass "
                                + "enumerates the published key space live and in key order, so a version whose "
                                + "publish instant is before the pass began but whose row was written after the pass "
                                + "had gone by its key was never visited - and the stamp says it was. Without this "
                                + "window such a version waits for the next full pass, up to a day at the defaults. "
                                + "It is paid on every incremental pass as publish-rate times window in extra scans, "
                                + "so a deployment publishing fast turns it down and one with lagging publisher "
                                + "clocks turns it up; zero switches it off and leaves the full pass to heal.",
                        Setting.Kind.DURATION, IncrementalPasses.DEFAULT_LOOKBACK, true)));
        if (!KNOWN_EXPLOITED) {
            return List.copyOf(settings);
        }
        settings.addAll(List.of(
                new Setting(SignalRefreshTaskProvider.INTERVAL.key(), "Compliance", "Signal refresh interval",
                        "Milliseconds between passes that draw a mirroring security signal (the known-exploited "
                                + "catalogue) into its stored snapshot, so a gate decision renders that snapshot "
                                + "instead of fetching on the publish thread. A pass inside a signal's own refresh "
                                + "window costs nothing, so this governs how quickly a cold or restored deployment "
                                + "reaches its first complete snapshot rather than how often the vendor is drawn.",
                        Setting.Kind.LONG,
                        SignalRefreshTaskProvider.INTERVAL.fallbackMillis(), true),
                new Setting("kev-auto-hold", "Compliance", "KEV auto-hold",
                        "When a scheduled scan finds an already-published artifact whose CVE is on a known-exploited "
                                + "catalogue, quarantine it for review (the same hold the gate writes). Only the "
                                + "narrow, actively-exploited set is held; everything below it stays report-only. An "
                                + "operator's release of a held artifact sticks. Applies on the next scan.",
                        Setting.Kind.BOOLEAN, "true", false),
                new Setting("kev-auto-release", "Compliance", "KEV auto-release",
                        "When a scheduled scan finds a retroactively KEV-held artifact whose CVE is no longer on any "
                                + "known-exploited catalogue (delisted, or the advisory retracted), automatically "
                                + "release the hold - the self-healing counterpart to KEV auto-hold. A human's release "
                                + "is unaffected and never re-held; only this sweep's own auto-holds are walked back, "
                                + "and a re-listing of the CVE re-holds. Applies on the next scan.",
                        Setting.Kind.BOOLEAN, "true", false)));
        return List.copyOf(settings);
    }
}
