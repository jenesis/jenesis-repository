package build.jenesis.repository.server.kernel;

import module java.base;
import build.jenesis.repository.cleanup.RetentionPolicy;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.settings.SettingsScopes;
import build.jenesis.repository.settings.StoredSettings;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Durations;
import build.jenesis.repository.store.RepositoryDocument;

/**
 * The one-time move of the configuration a deployment kept outside the settings catalogue before the catalogue had
 * tenant, repository and project levels for it, into those levels' settings documents:
 * <ul>
 * <li>a tenant's storage quota and rate limit, kept in the credential space ({@code .system/auth/<tenant>/quota} and
 *     {@code .../ratelimit}), become the tenant's {@code tenant-quota} and {@code rate-limit} settings;</li>
 * <li>a tenant's own definition of one of its repositories ({@code repositories.<name>} in the tenant's settings)
 *     becomes that repository's {@code routing} setting;</li>
 * <li>a repository's stored retention policy (its {@code retention} object) becomes its four retention settings, a
 *     rule the policy left unset written {@code none} - the stored policy replaced the deployment's whole, so a rule it
 *     did not set was off for it, whatever the deployment set;</li>
 * <li>a build-cache project's {@code cache.properties} becomes its three project settings.</li>
 * </ul>
 *
 * <p><b>It is a boot action, run before the node serves.</b> What it moves decides what a request is answered with -
 * where a repository fetches from, what a publish is metered against - so it lands before the first request resolves
 * through the new chain, which a walk consumer (on its own cron, and per repository, never reaching a tenant's
 * credential space or the build cache) could not promise. It runs in the artifact that ships, so every deployment
 * that starts runs it.
 *
 * <p><b>It is idempotent and it deletes nothing.</b> Each value is written only where the destination holds none for
 * its key ({@link StoredSettings#writeAbsent}), so a second run - a node that started beside this one, or a run that a
 * crash cut short - writes nothing a first run wrote, and never overwrites what an operator has set since. Every old
 * document is left exactly where it was: nothing deletes data automatically, and a document nothing reads any more is
 * an operator's to remove. Once a run has visited everything it records {@link #DONE}, and every later start reads that
 * one object and does nothing more.
 *
 * <p>A value whose setting no installed module declares is left where it is and named in the log - writing it would
 * put it in a document its module, installed later, would not own - as is a tenant's definition of a repository that
 * does not exist, which routed nothing.
 */
public final class SettingsScopeMove {

    /** The marker a completed move leaves: the move has run on this store, and does not run again. */
    public static final String DONE = Scopes.space(Scopes.CONFIG) + "/moved/settings-scopes";

    private static final System.Logger LOGGER = System.getLogger(SettingsScopeMove.class.getName());

    /** The credential-space documents a tenant's ceilings were kept in, and the property each carried. */
    private static final String QUOTA_DOCUMENT = "quota";
    private static final String QUOTA_PROPERTY = "max-bytes";
    private static final String RATE_DOCUMENT = "ratelimit";
    private static final String RATE_PROPERTY = "permits-per-minute";

    /** The settings the moved values become. */
    private static final String RATE_LIMIT = "rate-limit";
    private static final String ROUTING = "routing";
    private static final String PROJECT_SIZE = "project-size";
    private static final String PROJECT_LRU = "project-lru";
    private static final String PROJECT_TTL = "project-ttl";

    /** A project's former policy document. */
    private static final String CACHE_PROPERTIES = "cache.properties";

    private final ArtifactStore root;
    private final FormerRetention formerRetention;

    /** How a repository's former retention policy is read - its inventory's, which owns that object. */
    @FunctionalInterface
    public interface FormerRetention {
        Optional<RetentionPolicy> of(ArtifactStore repository) throws IOException;
    }

    public SettingsScopeMove(ArtifactStore root, FormerRetention formerRetention) {
        this.root = root;
        this.formerRetention = formerRetention;
    }

    /** What one run moved, by destination, for the log and for a test. */
    public record Moved(SortedMap<String, SortedMap<String, String>> written) {
    }

    /**
     * Move everything not yet moved, unless a completed run already recorded {@link #DONE}. Returns what it wrote.
     */
    public Moved run() throws IOException {
        SortedMap<String, SortedMap<String, String>> written = new TreeMap<>();
        if (root.readVersioned(DONE).isPresent()) {
            return new Moved(written);
        }
        for (String tenant : root.list("")) {
            if (!Scopes.valid(tenant)) {
                continue;
            }
            tenant(tenant, written);
        }
        ArtifactStore cache = root.scope(Scopes.SYSTEM).scope(Scopes.CACHE);
        for (String tenant : cache.list("")) {
            if (Scopes.valid(tenant)) {
                projects(tenant, cache.scope(tenant), written);
            }
        }
        root.writeVersioned(DONE, new byte[0], null);
        if (!written.isEmpty()) {
            StoredSettings.changed(root);
            LOGGER.log(System.Logger.Level.INFO, "Moved configuration kept outside the settings catalogue into its "
                    + "settings documents, leaving the old documents in place: " + written);
        }
        return new Moved(written);
    }

    private void tenant(String tenant, SortedMap<String, SortedMap<String, String>> written) throws IOException {
        Map<String, String> ceilings = new TreeMap<>();
        former(Scopes.space(Scopes.AUTH) + "/" + tenant + "/" + QUOTA_DOCUMENT, QUOTA_PROPERTY)
                .ifPresent(value -> ceilings.put(QuotaSettingsContributor.KEY, value));
        former(Scopes.space(Scopes.AUTH) + "/" + tenant + "/" + RATE_DOCUMENT, RATE_PROPERTY)
                .ifPresent(value -> ceilings.put(RATE_LIMIT, value));
        write(tenant, root.scope(tenant), declared(tenant, ceilings), written);

        ArtifactStore tenantScope = root.scope(tenant);
        Map<String, String> tenantSettings = StoredSettings.read(tenantScope);
        for (String repository : tenantScope.list("")) {
            if (!Scopes.valid(repository)) {
                continue;
            }
            ArtifactStore repositoryScope = tenantScope.scope(repository);
            Map<String, String> moved = new TreeMap<>();
            String definition = tenantSettings.get(SettingsScopes.repositoryKey(repository));
            if (definition != null && !definition.isBlank()) {
                moved.put(ROUTING, definition);
            }
            Optional<RetentionPolicy> policy;
            try {
                policy = formerRetention.of(repositoryScope);
            } catch (IOException corrupt) {
                LOGGER.log(System.Logger.Level.WARNING, "The stored retention policy of " + tenant + "/" + repository
                        + " does not parse, so it was not moved into the repository's settings; it is left in place.",
                        corrupt);
                policy = Optional.empty();
            }
            policy.ifPresent(retention -> {
                moved.put(RetentionPolicy.KEEP_LAST, Integer.toString(retention.keepLast()));
                moved.put(RetentionPolicy.MAX_AGE, rule(retention.maxAge()));
                moved.put(RetentionPolicy.PRERELEASE_EXPIRY, rule(retention.prereleaseExpiry()));
                moved.put(RetentionPolicy.NOT_DOWNLOADED_FOR, rule(retention.notDownloadedFor()));
            });
            write(tenant + "/" + repository, repositoryScope, declared(tenant + "/" + repository, moved), written);
        }
        // A tenant definition of a repository that does not exist routed nothing and is not moved.
        for (String key : tenantSettings.keySet()) {
            if (key.startsWith(SettingsScopes.REPOSITORY_PREFIX)) {
                String repository = key.substring(SettingsScopes.REPOSITORY_PREFIX.length());
                if (!Scopes.valid(repository)
                        || RepositoryDocument.read(tenantScope.scope(repository)).isEmpty()) {
                    LOGGER.log(System.Logger.Level.INFO, "Tenant " + tenant + " defines a repository '" + repository
                            + "' that does not exist; the definition routed nothing and is left where it is.");
                }
            }
        }
    }

    private void projects(String tenant, ArtifactStore cacheTenant,
                          SortedMap<String, SortedMap<String, String>> written) throws IOException {
        for (String project : cacheTenant.list("")) {
            if (!Scopes.valid(project)) {
                continue;
            }
            Optional<ArtifactStore.Versioned> stored = cacheTenant.scope(project).readVersioned(CACHE_PROPERTIES);
            if (stored.isEmpty()) {
                continue;
            }
            Properties policy = new Properties();
            policy.load(new ByteArrayInputStream(stored.get().content()));
            Map<String, String> moved = new TreeMap<>();
            copy(policy, "size", PROJECT_SIZE, moved);
            copy(policy, "lru", PROJECT_LRU, moved);
            copy(policy, "ttl", PROJECT_TTL, moved);
            write("project " + tenant + "/" + project, StoredSettings.project(root, tenant, project),
                    declared("project " + tenant + "/" + project, moved), written);
        }
    }

    private static void copy(Properties from, String property, String key, Map<String, String> to) {
        String value = from.getProperty(property);
        if (value != null && !value.isBlank()) {
            to.put(key, value.trim());
        }
    }

    /** A former duration rule as its setting: its duration, or {@code none} for a rule the policy left unset. */
    private static String rule(Duration duration) {
        return duration == null ? Durations.NONE : duration.toString();
    }

    /** The values whose setting an installed module declares; the rest are named and left where they are. */
    private static Map<String, String> declared(String where, Map<String, String> values) {
        Map<String, String> declared = new TreeMap<>();
        values.forEach((key, value) -> {
            if (SettingsScopes.declared(key).isPresent()) {
                declared.put(key, value);
            } else {
                LOGGER.log(System.Logger.Level.WARNING, "The " + key + " of " + where + " ('" + value + "') was not "
                        + "moved: no installed module declares that setting. It is left where it was.");
            }
        });
        return declared;
    }

    /** One property of a former credential-space document, when it holds a value other than zero - zero was never
     *  stored, it removed the document. */
    private Optional<String> former(String key, String property) throws IOException {
        Optional<ArtifactStore.Versioned> stored = root.readVersioned(key);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Properties document = new Properties();
        document.load(new ByteArrayInputStream(stored.get().content()));
        String value = document.getProperty(property);
        return value == null || value.isBlank() || value.trim().equals("0") ? Optional.empty()
                : Optional.of(value.trim());
    }

    private static void write(String where, ArtifactStore scope, Map<String, String> values,
                              SortedMap<String, SortedMap<String, String>> written) throws IOException {
        if (values.isEmpty()) {
            return;
        }
        Map<String, String> absent = StoredSettings.writeAbsent(scope, values);
        if (!absent.isEmpty()) {
            written.put(where, new TreeMap<>(absent));
        }
    }
}
