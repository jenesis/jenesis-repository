package build.jenesis.repository.definitions;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * A repository's routing as a repository setting: whether it accepts uploads and where it fetches what it lacks, in
 * the clause grammar {@link RepositoryDefinition#parse} reads. It lives in the repository's own settings document, so
 * the repository wizard asks it and the repository's settings screen edits it with every other repository setting.
 *
 * <p>It is {@link Setting#localOnly() local} - a tenant or deployment default would route every repository, of every
 * format, through one upstream - and {@link Setting#operatorOnly() the operator's}: it names the upstreams a
 * repository fetches from, with the deployment's per-host credential attached. A repository that sets none routes as
 * the deployment's by-name definition ({@code repositories.<name>}, over {@code jenreg.repositories.<name>}) says,
 * and with neither it is hosted.
 */
public final class RoutingSettingsContributor implements SettingsContributor {

    /** The setting's key. */
    public static final String KEY = "routing";

    @Override
    public List<Setting> settings() {
        return List.of(new Setting(KEY, "Routing", "Routing",
                "Whether this repository accepts uploads and where it fetches what it lacks, as clauses: writable, "
                        + "fallback <url> [nocache] [harden] [unscreened], fallback <repository>. Unset, it routes as "
                        + "the deployment's definition of its name says, else it is hosted.",
                Setting.Kind.STRING, "", true, Setting.Scope.REPOSITORY).local().operator().essential());
    }

    /** A definition the parser refuses, or one naming an upstream this deployment must not pull from - the same
     *  refusals the boot sweep and every write surface make. */
    @Override
    public Optional<String> refusal(Setting setting, String value, UnaryOperator<String> deployment) {
        RepositoryDefinition definition;
        try {
            definition = RepositoryDefinition.parse(value);
        } catch (RuntimeException invalid) {
            return Optional.of(invalid.getMessage() + " Write it as writable / fallback <source> "
                    + "[nocache|harden|unscreened].");
        }
        String refused = RepositoryDefinition.upstreamRefusal(definition,
                Boolean.parseBoolean(deployment.apply(RepositoryDefinition.ALLOW_INTERNAL_SETTING)));
        return refused == null ? Optional.empty()
                : Optional.of(refused + "." + RepositoryDefinition.upstreamRemedy());
    }
}
