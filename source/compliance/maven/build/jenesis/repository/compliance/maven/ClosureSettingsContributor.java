package build.jenesis.repository.compliance.maven;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * Describes where a published POM's closure is resolved from and how far; the defaults are {@link ClosureResolution}'s.
 */
public final class ClosureSettingsContributor implements SettingsContributor {

    @Override
    public List<Setting> settings() {
        return List.of(
                new Setting(ClosureResolution.REPOSITORY, "Compliance", "Maven closure repository",
                        "The Maven repository a published POM's dependency closure is resolved through, so the gate "
                                + "screens the licences and advisories of what the artifact pulls in and not only the "
                                + "artifact. Empty, the default, resolves nothing over the network: a POM that "
                                + "publishes no CycloneDX document beside it is screened on itself and what it "
                                + "declares, and each such publish is counted on " + ClosureResolution.UNRESOLVED
                                + ". Name the mirror this deployment's builds resolve through, or "
                                + "https://repo1.maven.org/maven2/ for Maven Central. The walk reads that repository "
                                + "alone, on the publish, within " + ClosureResolution.DOCUMENTS + " and "
                                + ClosureResolution.TIMEOUT + ".",
                        Setting.Kind.URI, "", true).standard(),
                new Setting(ClosureResolution.DOCUMENTS, "Compliance", "Maven closure documents",
                        "The most POM and metadata documents one published POM's closure may read from "
                                + ClosureResolution.REPOSITORY + ". A closure that needs more is not resolved: the "
                                + "artifact is screened without it, and the publish is logged and counted on "
                                + ClosureResolution.INCOMPLETE + ".",
                        Setting.Kind.INTEGER, ClosureResolution.DOCUMENTS_DEFAULT, true).advanced(),
                new Setting(ClosureResolution.TIMEOUT, "Compliance", "Maven closure timeout",
                        "How long one published POM's closure may keep reading new documents from "
                                + ClosureResolution.REPOSITORY + ", and how long each connection and read may take, "
                                + "so a publish waits about twice this at most. A closure that takes longer is not "
                                + "resolved: the artifact is screened without it, and the publish is logged and "
                                + "counted on " + ClosureResolution.INCOMPLETE + ".",
                        Setting.Kind.DURATION, ClosureResolution.TIMEOUT_DEFAULT, true).advanced());
    }
}
