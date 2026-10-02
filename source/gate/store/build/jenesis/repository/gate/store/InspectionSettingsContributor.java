package build.jenesis.repository.gate.store;

import module java.base;
import build.jenesis.repository.compliance.QualityInspector;
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
                        QualityInspector.OVERSIZED_DEFAULT, true).advanced());
    }
}
