package build.jenesis.repository.gateway;

import module java.base;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.definitions.RoutingSettingsContributor;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.RepositoryDefinitions;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.settings.SettingsScopes;

/**
 * The repository definitions, read live and per repository: a repository's own {@code routing} setting, stored in its
 * own settings document, over the deployment's runtime-stored {@code repositories.<name>}, over the file-configured
 * {@code jenrepo.repositories.<name>} default - the deployment's through the same pin-over-store-over-file precedence
 * every other live dial takes ({@link LiveConfig#effective}) - so an added or changed definition routes on the next
 * request. The router takes {@link #definition} per request; the kernel takes the
 * two answers it needs through {@link RepositoryDefinitions}, which is what lets the kernel not require this module.
 *
 * <p>It is the router's model, so it lives beside the router rather than inside {@code LiveConfig}, which would make
 * the kernel require the router for the parser of one setting; the boot module builds it once from the same three
 * things the kernel's own snapshot is built from.
 */
public final class LiveDefinitions implements RepositoryDefinitions {

    private final LiveConfig live;
    private final Settings settings;
    private final RepositoryProperties defaults;

    public LiveDefinitions(LiveConfig live, Settings settings, RepositoryProperties defaults) {
        this.live = live;
        this.settings = settings;
        this.defaults = defaults;
    }

    /** The routing definition for a named repository of {@code tenant}, parsed, or {@code null} when neither the
     *  repository, the deployment nor the file configuration defines it (the default serve path then applies). A
     *  {@code null} tenant reads the deployment's definition alone. */
    public RepositoryDefinition definition(String tenant, String name) {
        String own = tenant == null ? null : live.effective(tenant, name, RoutingSettingsContributor.KEY, null);
        String specification = own != null && !own.isBlank() ? own
                : live.effective(SettingsScopes.repositoryKey(name), defaults.getRepositories().get(name));
        return specification == null || specification.isBlank()
                ? null
                : RepositoryDefinition.parse(specification);
    }

    @Override
    public boolean writable(String tenant, String repository) {
        // Undefined means hosted, which is writable; only a definition can say otherwise - a proxy, a group view,
        // or one marked read-only.
        RepositoryDefinition definition = definition(tenant, repository);
        return definition == null || definition.writable();
    }

    @Override
    public boolean hardened(String tenant, String repository) {
        RepositoryDefinition definition = definition(tenant, repository);
        return definition != null && definition.harden();
    }

    /**
     * The boot-time definition sweep: parse EVERY configured repository definition and
     * fail the boot LOUD, naming the offending repository and the remedy, on any one that does not parse. The swept set
     * is every {@code repositories.<name>} the deployment names - the file-configured
     * {@code jenrepo.repositories.<name>} defaults and the runtime-stored {@code repositories.<name>}
     * overrides layered over them, resolved through the same effective lookup {@link #definition} uses - so a broken
     * definition is caught wherever it was written. A definition outside the clause grammar, an unknown option, a
     * policy option on a repository-name fallback, a {@code !writable}-with-no-fallbacks shape, or an unknown token
     * throws here at startup rather than being silently ignored while the repository serves the deployment's default
     * path - the same {@code store=s3}-with-the-s3-module-off fail-fast posture, applied to repository definitions. A
     * <em>valid</em> definition that carries only a warn condition (an {@code unscreened} fallback, a mixed-strength
     * fallback list) logs its warning inside {@link RepositoryDefinition#parse} but does not block the boot.
     */
    public void sweepDefinitions() {
        boolean allowInternal = live.proxyAllowInternal();
        for (String name : definedRepositories()) {
            String specification = live.effective(SettingsScopes.repositoryKey(name),
                    defaults.getRepositories().get(name));
            if (specification == null || specification.isBlank()) {
                continue;
            }
            RepositoryDefinition definition;
            try {
                definition = RepositoryDefinition.parse(specification);
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("Repository '" + name + "' has an invalid definition '" + specification
                        + "': " + invalid.getMessage() + " Fix the definition under jenrepo.repositories."
                        + name + " (or the stored repositories." + name + " override), or remove it - a selected "
                        + "repository definition that cannot be parsed must never be silently ignored.", invalid);
            }
            // The outbound screen on the operator-configured upstream, applied here as well as at every write
            // surface. This is the backstop that makes the refusal structural rather than a habit: a definition
            // written straight into the file configuration, or one stored without this screen, never passes through a
            // write API - and it must not serve a credentialed pull-through in cleartext because of that.
            String refused = RepositoryDefinition.upstreamRefusal(definition, allowInternal);
            if (refused != null) {
                throw new IllegalStateException("Repository '" + name + "' has a refused definition '" + specification
                        + "': " + refused + "." + RepositoryDefinition.upstreamRemedy());
            }
        }
    }

    /** Every repository the deployment names a definition for: the file-configured {@code repositories.<name>} defaults
     *  unioned with the runtime-stored global {@code repositories.<name>} overrides, name-deduplicated in a stable order
     *  so the sweep visits each configured repository exactly once. */
    private SequencedSet<String> definedRepositories() {
        SequencedSet<String> names = new LinkedHashSet<>(defaults.getRepositories().keySet());
        for (String key : settings.overrides().keySet()) {
            if (key.startsWith(SettingsScopes.REPOSITORY_PREFIX)) {
                names.add(key.substring(SettingsScopes.REPOSITORY_PREFIX.length()));
            }
        }
        return names;
    }
}
