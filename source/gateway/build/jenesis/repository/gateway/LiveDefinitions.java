package build.jenesis.repository.gateway;

import module java.base;
import build.jenesis.repository.definitions.RepositoryDefinition;
import build.jenesis.repository.server.kernel.LiveConfig;
import build.jenesis.repository.server.kernel.RepositoryDefinitions;
import build.jenesis.repository.server.RepositoryProperties;
import build.jenesis.repository.server.kernel.Settings;
import build.jenesis.repository.settings.SettingsScopes;

/**
 * The deployment's repository definitions, read live: the runtime-stored {@code repositories.<name>} over the
 * file-configured {@code jenreg.repositories.<name>} default, through the same pin-over-store-over-file precedence
 * every other live dial takes ({@link LiveConfig#effective}), so an added or changed definition routes on the next
 * request. The router takes {@link #definition} per request; the kernel takes the two answers it needs through
 * {@link RepositoryDefinitions}, which is what lets the kernel not require this module.
 *
 * <p>This used to live inside {@code LiveConfig}, which made the kernel require the router for the parser of one
 * setting. It is the router's model, so it lives beside the router now, and the boot module builds it once from the
 * same three things the kernel's own snapshot is built from.
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

    /** The routing definition for a named repository, parsed, or {@code null} when neither the store nor the file
     *  configuration defines it (the deployment's default serve path then applies). */
    public RepositoryDefinition definition(String name) {
        String specification = live.effective(SettingsScopes.repositoryKey(name), defaults.getRepositories().get(name));
        return specification == null || specification.isBlank()
                ? null
                : RepositoryDefinition.parse(specification);
    }

    @Override
    public boolean writable(String repository) {
        // Undefined means hosted, which is writable; only a definition can say otherwise - a proxy, a group view,
        // or one marked read-only.
        RepositoryDefinition definition = definition(repository);
        return definition == null || definition.writable();
    }

    @Override
    public boolean hardened(String repository) {
        RepositoryDefinition definition = definition(repository);
        return definition != null && definition.harden();
    }

    /**
     * The boot-time definition sweep (§9): parse EVERY configured repository definition and
     * fail the boot LOUD, naming the offending repository and the remedy, on any one that does not parse. The swept set
     * is every {@code repositories.<name>} the deployment names - the file-configured
     * {@code jenreg.repositories.<name>} defaults and the runtime-stored {@code repositories.<name>}
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
                        + "': " + invalid.getMessage() + " Fix the definition under jenreg.repositories."
                        + name + " (or the stored repositories." + name + " override), or remove it - a selected "
                        + "repository definition that cannot be parsed must never be silently ignored.", invalid);
            }
            // The outbound screen on the operator-configured upstream, applied here as well as at every write
            // surface. This is the backstop that makes the refusal structural rather than a habit: a definition
            // written straight into the file configuration, or one stored before this shipped, never passes through a
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
