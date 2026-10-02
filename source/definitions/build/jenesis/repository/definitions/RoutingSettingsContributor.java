package build.jenesis.repository.definitions;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.settings.SettingsContributor;

/**
 * A repository's routing as a repository setting: whether it accepts uploads and where it fetches what it lacks, in the
 * clause grammar {@link RepositoryDefinition#parse} reads, kept in the repository's own settings document so the wizard
 * asks it and the settings screen edits it.
 *
 * <p>{@link Setting#localOnly() Local}, since a tenant or deployment default would route every repository of every
 * format through one upstream, and {@link Setting#operatorOnly() operator-only}, since it names upstreams fetched with
 * the deployment's per-host credential. A repository that sets none routes as the deployment's by-name definition
 * ({@code repositories.<name>}, over {@code jenrepo.repositories.<name>}) says, and with neither it is hosted.
 */
public final class RoutingSettingsContributor implements SettingsContributor {

    /** The setting's key. */
    public static final String KEY = "routing";

    @Override
    public List<Setting> settings() {
        return List.of(new Setting(KEY, "Routing", "Routing",
                "Whether this repository accepts uploads, and the upstreams and other repositories it fetches what it "
                        + "lacks from, in order. Unset, it routes as the deployment's definition of its name says, "
                        + "else it is hosted.",
                Setting.Kind.STRING, "", true, Setting.Scope.REPOSITORY).form(Setting.Form.ROUTING).local().operator().essential());
    }

    /** A definition the parser refuses, or one naming an upstream this deployment must not pull from - the refusals the
     *  boot sweep and every write surface make. */
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
