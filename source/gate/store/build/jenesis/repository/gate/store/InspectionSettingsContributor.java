package build.jenesis.repository.gate.store;

import module java.base;
import build.jenesis.repository.compliance.QualityInspector;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Surfaces what this deployment does with an artifact too large to hand an inspector in one array.
 *
 * <p>The size of that array is a deployment property rather than a row here ({@code jenreg.inspection.prefix-bytes},
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
                        "What to do with an artifact larger than the inspection prefix - jenreg.inspection.prefix-bytes, "
                                + "32 MiB by default - which is the most of one artifact an inspector is ever handed in "
                                + "memory. STREAM (the default) screens it anyway, reading it from the store as a stream "
                                + "so a licence, a coordinate or a signature stored at the back of a large archive is "
                                + "still found; it costs a pass over the artifact. QUARANTINE holds it for review, and "
                                + "REJECT refuses the publish - both of them saying the artifact was too large to "
                                + "screen, which is a statement about its size and not about what is in it. This "
                                + "governs what is PUBLISHED here: a proxied artifact is either streamed through "
                                + "to the client as it is fetched, where there is no stored body to go back to, "
                                + "or spooled whole by the hardening proxy, which already screens it whole.",
                        Setting.Kind.CHOICE, List.of("STREAM", "QUARANTINE", "REJECT"),
                        QualityInspector.OVERSIZED_DEFAULT, true));
    }
}
