package build.jenesis.repository.gate.store;

import module java.base;
import build.jenesis.repository.compliance.AdvisorySource;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.compliance.ScreeningMode;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Surfaces what this deployment does with an artifact too large to hand an inspector in one array.
 *
 * <p>The size of that array is a deployment property rather than a row here ({@code jenrepo.inspection.prefix-bytes},
 * read live on the publish thread for every artifact, which is why it does not go through the settings store), and
 * the description below names it so an operator who wants to move the boundary rather than change the policy can
 * find it. What is a row is the policy itself, because it is a compliance decision of exactly the kind
 * {@code license-unknown} beside it is, and it is read only for an artifact that has already exceeded the bound.
 *
 * <p>Beside it is what a screen does when a feed cannot answer, and whether what it finds holds at all
 * ({@link ScreeningMode}): a repository's dial, since a repository being rolled out under screening and one already
 * enforced sit side by side; and which of the deployment's feeds screen the repository at all
 * ({@value AdvisorySource#SELECTION}).
 */
public final class InspectionSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(QualityInspector.OVERSIZED_KEY, "Compliance", "Artifacts past the inspection bound",
                        "What to do with an artifact larger than the inspection prefix, the most of one artifact an "
                                + "inspector is handed in memory. Streaming screens it anyway, reading it from the "
                                + "store as a stream at the cost of a pass over the artifact. Holding it for review or "
                                + "refusing the publish both say it was too large to screen, which says nothing about "
                                + "its content. This governs what is published here, not the proxy path.",
                        Setting.Kind.CHOICE, List.of("STREAM", "QUARANTINE", "REJECT"),
                        QualityInspector.OVERSIZED_DEFAULT, true).advanced(),
                new Setting(ScreeningMode.KEY, "Compliance", "Screening mode",
                        "What the screen does when an advisory feed or a scanner cannot answer, and whether what it "
                                + "finds holds at all. Holding, the default, keeps an artifact the screen could not "
                                + "clear for review with the outage named. Admitting serves it when every check that "
                                + "could answer allows it, and names the check that could not. Recording never holds on "
                                + "what the screen found or could not find, and records it: the mode to run while "
                                + "screening is rolled out over what a repository already serves. The deny list "
                                + "refuses in every mode, and a hardened proxy screens strictly in every mode.",
                        List.of(new Setting.Choice("HOLD", "Hold",
                                        "an artifact the screen could not clear is held for review"),
                                new Setting.Choice("ADMIT", "Admit",
                                        "an outage leaves the artifact to the checks that could answer"),
                                new Setting.Choice("RECORD", "Record only",
                                        "nothing the screen found or could not find is held; all of it is recorded")),
                        ScreeningMode.DEFAULT, true, Setting.Scope.REPOSITORY).standard(),
                new Setting(AdvisorySource.SELECTION, "Compliance", "Advisory feeds",
                        "The advisory feeds that screen this repository's cached copies, by name and "
                                + "comma-separated, among those this deployment switches on; empty for every one but a "
                                + "mirror, which screens only a repository naming it, and '"
                                + AdvisorySource.NONE_SELECTED + "' for none. A feed's switch and its credential stay "
                                + "the deployment's. A name that is not on is an outage of every screen of the "
                                + "repository, decided by its screening mode, and fails its scans, rather than "
                                + "screening with fewer feeds than it names.",
                        Setting.Kind.STRING, "", true, Setting.Scope.REPOSITORY).advanced());
    }
}
