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
                        "A Maven repository a published POM's dependency closure is also resolved through, so the "
                                + "gate screens the licences and advisories of everything the artifact pulls in. A POM "
                                + "is read first from this repository, so what is published here resolves with what it "
                                + "depends on, then from the upstreams this deployment's Maven repositories proxy, "
                                + "then from this one. Name it when nothing proxies the mirror this deployment's builds "
                                + "resolve through, or https://repo1.maven.org/maven2/ for Maven Central. With nowhere "
                                + "but this repository to read from, a dependency from outside is screened on its "
                                + "coordinate alone and the publish counted on " + ClosureResolution.UNRESOLVED + ".",
                        Setting.Kind.URI, "", true).advanced(),
                new Setting(ClosureResolution.ALLOWED, "Compliance", "Maven closure allowed repositories",
                        "Repositories a published POM may declare that its closure is read from, as URL prefixes "
                                + "separated by commas or spaces. A repository a POM names is otherwise never "
                                + "contacted - the upstreams this deployment proxies and "
                                + ClosureResolution.REPOSITORY + " are read whatever a POM says - so a publisher cannot "
                                + "make the server call a host of their choosing.",
                        Setting.Kind.STRING, "", true).advanced(),
                new Setting(ClosureResolution.ON_INCOMPLETE, "Compliance", "Maven closure incomplete action",
                        "Verdict for a published POM whose dependency closure could not be resolved in full from "
                                + "the places it reads: a dependency's POM none of them holds, a fetch that failed, or "
                                + "a bound reached. The artifact is screened on what was read either way, and each is "
                                + "logged and counted on " + ClosureResolution.INCOMPLETE + "; held for review by "
                                + "default, since what it pulls in was not screened.",
                        Setting.Choice.VERDICTS, ClosureResolution.ON_INCOMPLETE_DEFAULT, true).advanced(),
                new Setting(ClosureResolution.DOCUMENTS, "Compliance", "Maven closure documents",
                        "The most POM and metadata documents one published POM's closure may read. A closure "
                                + "that needs more is not resolved: the artifact is screened without it, given "
                                + ClosureResolution.ON_INCOMPLETE + ", and logged and counted on "
                                + ClosureResolution.INCOMPLETE + ".",
                        Setting.Kind.INTEGER, ClosureResolution.DOCUMENTS_DEFAULT, true).advanced(),
                new Setting(ClosureResolution.TIMEOUT, "Compliance", "Maven closure timeout",
                        "How long one published POM's closure may keep reading new documents, and how long "
                                + "each connection and read may take, so a publish waits about twice this at most. A "
                                + "closure that takes longer is not resolved: the artifact is screened without it, "
                                + "given " + ClosureResolution.ON_INCOMPLETE + ", and logged and counted on "
                                + ClosureResolution.INCOMPLETE + ".",
                        Setting.Kind.DURATION, ClosureResolution.TIMEOUT_DEFAULT, true).advanced());
    }
}
