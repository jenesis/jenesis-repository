package build.jenesis.repository.server;

import module java.base;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.StoreCache;

/**
 * Whether a repository exists, for a deployment that creates its repositories deliberately rather than on the first
 * publish into a name.
 *
 * <p>Unless {@value #SETTING} is switched on, a request to a repository that does not exist is not answered: a publish
 * would otherwise create any repository a client names, a misspelled one included, and a read through a pull-through
 * upstream would fill one the same way. A repository exists when it holds anything - which is every repository a
 * deployment used before it was created this way, so none of them stops answering - when it has a definition (a
 * proxy, a group, or a hosted one stored over the API), when the routing serves it without being asked
 * ({@link RepositoryRouting#serves}), or when an operator created it, which writes
 * {@link build.jenesis.repository.scope.Scopes#CREATED} into its scope.
 *
 * <p><b>What it costs.</b> The answer comes from the tenant's listing of repository names, read through the node's
 * {@link StoreCache} - the listing the console's sidebar reads - so a request to a repository that exists pays no
 * store read in the steady state. A name the cached listing does not hold is probed once more directly, one child
 * name at most, because it may have been created since the listing was read; the probe is paid only by a request to
 * a repository that is new or does not exist, and not at all while the setting lets a publish create one.
 */
public final class RepositoryPresence {

    /** The runtime setting that lets a publish create the repository it names. Off by default. */
    public static final String SETTING = "create-repository-on-publish";

    /** Every repository answers: what a composition with no stored configuration and no store of its own - a test's
     *  controller over a fixture - is given. */
    public static final RepositoryPresence ANY = new RepositoryPresence(null, _ -> "true", _ -> false);

    private final ArtifactStore root;
    private final UnaryOperator<String> settings;
    private final Predicate<String> defined;

    /**
     * @param root     the un-scoped store, whose {@code <tenant>/<repository>} scopes are probed.
     * @param settings the effective value of a bare setting key, read on every call so a change applies at once.
     * @param defined  whether a repository of this name has a definition.
     */
    public RepositoryPresence(ArtifactStore root, UnaryOperator<String> settings, Predicate<String> defined) {
        this.root = root;
        this.settings = settings;
        this.defined = defined;
    }

    /** Whether a request may reach this repository: it exists, or a publish may create it. */
    public boolean answers(String tenant, String repository) throws IOException {
        return Boolean.parseBoolean(settings.apply(SETTING)) || defined.test(repository) || exists(tenant, repository);
    }

    /** Whether the repository holds anything, its creation marker included. */
    public boolean exists(String tenant, String repository) throws IOException {
        if (StoreCache.of("repositories", root, StoreCache.configuredTtl()).list(tenant).contains(repository)) {
            return true;
        }
        boolean[] found = {false};
        root.scope(tenant).scope(repository).page("", "", 1, _ -> found[0] = true);
        return found[0];
    }
}
