package build.jenesis.repository.server.kernel;

import build.jenesis.repository.server.RepositoryProperties;
import module java.base;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.settings.SecretCipher;
import build.jenesis.repository.settings.SettingsDocuments;
import build.jenesis.repository.settings.SettingsScopes;
import build.jenesis.repository.settings.SettingsSecrets;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Epoch;
import build.jenesis.repository.store.Retries;

/**
 * Deployment-wide settings that can be changed at runtime, kept as one JSON document per contributing module in the
 * artifact store ({@code .system/config/settings/<module>.json}, {@link SettingsDocuments}) and layered over the file/env
 * defaults from {@link RepositoryProperties}: a stored value wins, an absent one falls back to the configured default
 * - mirroring how a per-tenant policy or quota overrides its deployment default. So an operator retunes compliance,
 * retention, the cleanup schedule and the defaults through the API, console or CLI without redeploying, and all three
 * surfaces read and write the same documents. Settings that cannot change at runtime (the storage backend, the listen
 * port, whether authorization is enforced) stay file-only and are deliberately not represented here.
 *
 * <p>A write compare-and-sets only the owning module's document, so a concurrent edit to a <em>different</em> module
 * never contends and a lost race on the <em>same</em> document re-reads and retries rather than clobbering another
 * node's change. Reads serve an in-memory snapshot merged across every module's document rather than the store, so
 * the hot publish/proxy paths (which rebuild the compliance gate per request from these values through
 * {@link LiveConfig}) pay no storage round-trip. A write refreshes the snapshot at once, so the writing node sees its
 * own change immediately; a scheduled re-read picks up another node's change within the refresh interval, which is
 * how a write-anywhere multi-node deployment converges. A stored value is inert for a key an operator has pinned from
 * a higher-precedence source (an environment variable, {@code -D} or an external config file) - {@link LiveConfig}
 * consults the {@link PinnedSettings} origin probe for that.
 *
 * <p><b>Global and per-tenant scope.</b> A setting is either deployment-wide (global) or one a tenant may override for
 * its own artifact space, classified by {@link SettingsScopes}. A tenant's documents live under the tenant's own store
 * scope ({@code <tenant>/.system/config/settings/<module>.json}, the aligned layout), so the effective value of a
 * tenant-overridable key follows the chain <em>Spring pin &gt; tenant document &gt; global document &gt; packaged
 * default</em> ({@link #getOrDefault(String, String, String)}), while a global-only key is refused in a tenant document
 * and always resolves deployment-wide. Gate policies, deny lists and forward targets can therefore differ per tenant
 * while deployment-wide knobs stay uniform. Each tenant's merged overrides are cached in the same way as the global
 * snapshot, lazily loaded and invalidated on a write or the scheduled re-read.
 */
public final class Settings {


    private final ArtifactStore root;
    private volatile Properties snapshot;

    /** Bumped by every write, read by the refresh: one point read that says whether the documents changed, so the
     *  thirty-second refresh lists and re-reads every settings document only when another node wrote one. */
    private final Epoch epoch;
    /** Beside the documents under {@code .system/config}: a key at the root would make {@code config} a tenant to
     *  every pass that enumerates them, and its file a repository no pass can write under. */
    static final String EPOCH = Scopes.space(Scopes.CONFIG) + "/settings-epoch";
    /** Per-tenant merged overrides, lazily loaded and dropped on a write to that tenant or the scheduled refresh, so a
     *  tenant's effective values are served from memory like the global snapshot without a storage round-trip. */
    private final Map<String, Properties> tenantSnapshots = new ConcurrentHashMap<>();

    /** How many tenants' snapshots are held, empty ones included: past this many, a tenant that holds no settings is
     *  read and not cached, so a flood of invented tenant names on an anonymous deployment cannot grow the map. The
     *  bound is far above any deployment's tenant count, so it is a cap on a misuse and never on a deployment. */
    private static final int TENANT_SNAPSHOTS = 1_000;
    /** The envelope cipher for SECRET-kind values: a keyed write encrypts, a read decrypts, and a secret write with no
     *  key configured is refused - so only {@code enc:v1:} ciphertext ever reaches the store (clean cutover). */
    private final SecretCipher cipher;
    /** The SECRET-kind keys, resolved once (the catalogue is static for the JVM), so the per-read secret check is a
     *  cheap set membership rather than re-running the {@link SettingsSecrets} discovery on every config read. */
    private final Set<String> secretKeys;

    public Settings(ArtifactStore root) throws IOException {
        this(root, SecretCipher.fromEnvironment());
    }

    /** Settings backed by an explicit cipher - the seam a test injects a fixed master key (or an unconfigured cipher)
     *  through without mutating the process environment. Production uses {@link #Settings(ArtifactStore)}, which reads
     *  the key from {@value SecretCipher#ENV}. */
    public Settings(ArtifactStore root, SecretCipher cipher) throws IOException {
        this.root = root;
        this.cipher = cipher;
        this.secretKeys = SettingsSecrets.keys();
        this.epoch = new Epoch(root, EPOCH);
        this.snapshot = load();
    }

    /** Whether a key holds a SECRET-kind value, from the once-resolved catalogue. */
    private boolean isSecret(String key) {
        return secretKeys.contains(key);
    }

    /** The store form of a value about to be persisted for {@code key}: unchanged for a non-secret key or a clearing
     *  (blank) value; for a SECRET key set to a non-blank value it is the {@code enc:v1:} envelope - refusing the write
     *  (§9) when no master key is configured, so plaintext never reaches the store, and passing an already-encrypted
     *  envelope through untouched so a carried-forward secret is not double-sealed. */
    private String forStore(String key, String value) {
        if (value == null || value.isBlank() || !isSecret(key) || SecretCipher.isEnvelope(value)) {
            return value;
        }
        if (!cipher.configured()) {
            throw new IllegalStateException("setting secret '" + key + "' requires " + SecretCipher.ENV
                    + " to be configured so the value can be encrypted at rest; set it (a <key-id>:<base64-32-byte-key>"
                    + " entry) or supply the secret through the environment instead - the plaintext was not stored");
        }
        return cipher.encrypt(value);
    }

    /** The usable value of a stored entry for {@code key}: decrypted when it is an {@code enc:v1:} envelope; passed
     *  through for a non-secret; and refused (§9, fail-closed) when it is a SECRET key whose stored value is not an
     *  envelope - a legacy/tampered plaintext secret must be re-entered, never silently used. A stored envelope that no
     *  configured key can open throws from {@link SecretCipher#decrypt}, so a broken secret never reads as blank. */
    private String fromStore(String key, String value) {
        if (SecretCipher.isEnvelope(value)) {
            return cipher.decrypt(value);
        }
        if (value != null && isSecret(key)) {
            throw new IllegalStateException("stored secret '" + key + "' is not an " + SecretCipher.ENV
                    + " envelope (enc:v1:...); it is invalid and must be re-entered - it will not be used as plaintext");
        }
        return value;
    }

    /** The stored override for a key, or {@code fallback} (the file/env default) when none is set. A SECRET-kind value
     *  is decrypted for use here (fail-closed if it cannot be); a non-secret value is passed straight through, so the
     *  cipher never touches an ordinary config read. */
    public String getOrDefault(String key, String fallback) {
        String value = snapshot.getProperty(key);
        return value == null ? fallback : fromStore(key, value);
    }

    /** The effective value of a key for a tenant: the tenant's own override where the key is tenant-overridable and the
     *  tenant set one, otherwise the deployment-wide (global) override, otherwise {@code fallback} (the file/env
     *  default). A global-only key ignores any tenant document and resolves deployment-wide, so a tenant can retune its
     *  gate policy, deny list or forward target without touching a deployment knob. The Spring-pin leg of the chain is
     *  applied by {@link LiveConfig}, above this. */
    public String getOrDefault(String tenant, String key, String fallback) {
        if (tenant == null || tenant.isBlank() || !SettingsScopes.tenantOverridable(key)) {
            return getOrDefault(key, fallback);
        }
        String tenantValue = tenantSnapshot(tenant).getProperty(key);
        return tenantValue != null ? fromStore(key, tenantValue) : getOrDefault(key, fallback);
    }

    /** Every stored override, sorted; a surface renders these over the file defaults it already knows. */
    public SortedMap<String, String> overrides() {
        Properties current = snapshot;
        SortedMap<String, String> map = new TreeMap<>();
        current.stringPropertyNames().forEach(name -> map.put(name, current.getProperty(name)));
        return map;
    }

    /** A tenant's own stored overrides, sorted - the tenant-overridable keys this tenant has set for its own space,
     *  distinct from the deployment-wide ones {@link #overrides()} returns. A surface renders these as the tenant's
     *  layer over the global effective values. */
    public SortedMap<String, String> overrides(String tenant) {
        Properties current = tenantSnapshot(tenant);
        SortedMap<String, String> map = new TreeMap<>();
        current.stringPropertyNames().forEach(name -> map.put(name, current.getProperty(name)));
        return map;
    }

    /** Set ({@code value} non-blank) or clear ({@code null}/blank) one override, leaving the rest untouched. Only the
     *  key's owning module document is compare-and-set, re-read and retried on a lost race, so a concurrent change to
     *  another key (in the same or another module) is never lost. */
    public void set(String key, String value) throws IOException {
        writeInto(root, key, value);
        snapshot = load();
    }

    /** Compare-and-set one override into a store's owning module document (the deployment root for a global write, a
     *  tenant's scope for a per-tenant one), re-reading and retrying a lost race so a concurrent change to another key
     *  is never clobbered. Does not refresh a snapshot - the caller reloads the affected view. */
    private void writeInto(ArtifactStore store, String key, String value) throws IOException {
        // Encrypt (or refuse) a SECRET value up front, before any store read/write, so a refusal persists nothing and a
        // stored SECRET is always an enc:v1: envelope, never plaintext.
        String stored = forStore(key, value);
        Retries.update(store, SettingsDocuments.document(SettingsDocuments.moduleOf(key)), current -> {
            Map<String, String> values = current
                    .map(versioned -> SettingsDocuments.parse(versioned.content()))
                    .orElseGet(LinkedHashMap::new);
            if (value == null || value.isBlank()) {
                values.remove(key);
            } else {
                values.put(key, stored);
            }
            return SettingsDocuments.serialize(values);
        });
        epoch.bump();
    }

    /** Set or clear one override in a tenant's own scope, layered over the deployment-wide value. Refuses a global-only
     *  key, which no tenant document may carry - a tenant retunes only a gate policy, deny list or forward target, never
     *  a deployment knob. The compare-and-set touches only that tenant's owning module document, and the tenant's cached
     *  snapshot is dropped so the writing node sees the change at once. */
    public void set(String tenant, String key, String value) throws IOException {
        if (!SettingsDocuments.validTenant(tenant)) {
            throw new IllegalArgumentException("Not a tenant name: " + tenant);
        }
        if (!SettingsScopes.tenantOverridable(key)) {
            throw new IllegalArgumentException("Setting '" + key
                    + "' is deployment-wide and cannot be set per tenant");
        }
        writeInto(root.scope(tenant), key, value);
        tenantSnapshots.remove(tenant);
    }

    /** Every stored settings document, module name to that module's stored overrides, read straight from the store -
     *  the raw on-store shape {@link #exportBundle} redacts and serialises. Sorted (documents and keys) so a re-export
     *  of unchanged state is byte-identical; an empty document is omitted, since a cleared setting leaves none behind.
     *  This is the raw reader (SECRET keys and all), so the SECRET-stripping lives in the export methods, not here -
     *  the {@code .keySet()} consumers (the modules diagnostic) need every module a document names. */
    public SortedMap<String, SortedMap<String, String>> documents() throws IOException {
        return documentsOf(root);
    }

    /** A tenant's own stored settings documents, module name to that tenant's overrides - the tenant slice the export
     *  bundle carries and a tenant admin edits, distinct from the deployment-wide {@link #documents()}. Credential-free
     *  by construction: every SECRET-kind key is stripped so a stored secret never travels in a backup (a tenant
     *  document holds no global secret today, but the guard is uniform). */
    public SortedMap<String, SortedMap<String, String>> documents(String tenant) throws IOException {
        if (!SettingsDocuments.validTenant(tenant)) {
            throw new IllegalArgumentException("Not a tenant name: " + tenant);
        }
        return SettingsSecrets.redact(documentsOf(root.scope(tenant)));
    }

    /** Every stored settings document under a store scope, module name to that module's overrides, sorted so a
     *  re-export of unchanged state is byte-identical and an empty document is omitted. */
    private static SortedMap<String, SortedMap<String, String>> documentsOf(ArtifactStore store) throws IOException {
        SortedMap<String, SortedMap<String, String>> documents = new TreeMap<>();
        for (String child : store.list(SettingsDocuments.ROOT)) {
            if (!child.endsWith(".json")) {
                continue;
            }
            Optional<ArtifactStore.Versioned> object = store.readVersioned(SettingsDocuments.ROOT + "/" + child);
            if (object.isEmpty()) {
                continue;
            }
            SortedMap<String, String> values = new TreeMap<>(SettingsDocuments.parse(object.get().content()));
            if (!values.isEmpty()) {
                documents.put(child.substring(0, child.length() - ".json".length()), values);
            }
        }
        return documents;
    }

    /** The full export bundle: every deployment-wide (global) document keyed by its module name, plus every tenant's
     *  documents keyed {@code tenant:<tenant>:<module>} ({@link SettingsDocuments#tenantKey}). One flat
     *  {@code string -> document} object the existing serializer emits byte-identically under re-export; with no tenant
     *  overrides it is exactly the global bundle, so an operator who never used per-tenant config sees the same export
     *  as before. Credential-free by construction: every SECRET-kind key is excluded (a stored secret - the keyless
     *  identity token - never travels in a backup), so the export carries no credential even though a SECRET setting
     *  can live in the store; the write-only upstream credentials live outside {@code config/settings} and were never
     *  in it. */
    public SortedMap<String, SortedMap<String, String>> exportBundle() throws IOException {
        SortedMap<String, SortedMap<String, String>> bundle = new TreeMap<>(documents());
        for (String tenant : configuredTenants()) {
            documentsOf(root.scope(tenant)).forEach((module, values) ->
                    bundle.put(SettingsDocuments.tenantKey(tenant, module), values));
        }
        return SettingsSecrets.redact(bundle);
    }

    /** Restore a full export bundle produced by {@link #exportBundle}: the deployment-wide (global) documents and every
     *  tenant slice it carries, a full restore that clears any global document or tenant document the bundle omits. A
     *  tenant slice may only carry tenant-overridable keys - a global-only key in a tenant document is refused before
     *  anything is written, so an import cannot smuggle a deployment knob into a tenant's scope. The caller validates
     *  the values first (a dry {@link LiveConfig} resolve). */
    public void importBundle(Map<String, ? extends Map<String, String>> bundle) throws IOException {
        Map<String, Map<String, Map<String, String>>> perTenant = new LinkedHashMap<>();
        Map<String, Map<String, String>> global = new LinkedHashMap<>();
        partition(bundle, global, perTenant);
        Set<String> tenants = new TreeSet<>(perTenant.keySet());
        tenants.addAll(configuredTenants());
        // A full restore rewrites the global documents and every tenant slice; capture the current raw state first and
        // roll the whole import back on any failure, so a global that resolves but a later tenant slice that does not
        // (or a mid-restore store error) never leaves a half-applied - and possibly boot-wedging - mix of old and new.
        SortedMap<String, SortedMap<String, String>> globalBefore = documentsOf(root);
        Map<String, SortedMap<String, SortedMap<String, String>>> tenantsBefore = new LinkedHashMap<>();
        for (String tenant : tenants) {
            tenantsBefore.put(tenant, documentsOf(root.scope(tenant)));
        }
        try {
            importDocuments(global);
            for (String tenant : tenants) {
                restoreTenant(tenant, perTenant.getOrDefault(tenant, Map.of()));
            }
        } catch (IOException | RuntimeException failure) {
            try {
                importDocuments(new LinkedHashMap<>(globalBefore));
                for (Map.Entry<String, SortedMap<String, SortedMap<String, String>>> entry : tenantsBefore.entrySet()) {
                    restoreTenant(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
                }
            } catch (IOException | RuntimeException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    /** Restore one tenant's slice - the tenant-admin import path - validating every key is tenant-overridable and doing
     *  a full restore of that tenant's documents (a document the slice omits is cleared), leaving the global settings
     *  and every other tenant untouched. */
    public void importTenant(String tenant, Map<String, ? extends Map<String, String>> documents) throws IOException {
        if (!SettingsDocuments.validTenant(tenant)) {
            throw new IllegalArgumentException("Not a tenant name: " + tenant);
        }
        restoreTenant(tenant, documents);
    }

    /** Split a bundle into its global documents (module-keyed) and its per-tenant documents ({@code tenant:<t>:<module>}
     *  keyed), refusing an unsafe tenant/module key and a global-only key in a tenant slice up front. */
    private static void partition(Map<String, ? extends Map<String, String>> bundle,
                                  Map<String, Map<String, String>> global,
                                  Map<String, Map<String, Map<String, String>>> perTenant) {
        bundle.forEach((key, document) -> {
            Map<String, String> values = document == null ? Map.of() : document;
            if (SettingsDocuments.isTenantKey(key)) {
                String[] parsed = SettingsDocuments.parseTenantKey(key);
                if (parsed == null) {
                    throw new IllegalArgumentException("Not a tenant settings key: " + key);
                }
                for (String setting : values.keySet()) {
                    if (!SettingsScopes.tenantOverridable(setting)) {
                        throw new IllegalArgumentException("Setting '" + setting
                                + "' is deployment-wide and cannot appear in a tenant document");
                    }
                }
                perTenant.computeIfAbsent(parsed[0], _ -> new LinkedHashMap<>()).put(parsed[1], values);
            } else {
                global.put(key, values);
            }
        });
    }

    /** Full-restore one tenant's documents into its scope (blanks dropped, omitted documents cleared) and drop its
     *  cached snapshot. Every key is re-checked tenant-overridable, so a direct slice import is guarded like a write. */
    private void restoreTenant(String tenant, Map<String, ? extends Map<String, String>> documents) throws IOException {
        if (!SettingsDocuments.validTenant(tenant)) {
            throw new IllegalArgumentException("Not a tenant name: " + tenant);
        }
        ArtifactStore scope = root.scope(tenant);
        Set<String> written = new HashSet<>();
        for (Map.Entry<String, ? extends Map<String, String>> entry : documents.entrySet()) {
            if (!SettingsDocuments.validModule(entry.getKey())) {
                throw new IllegalArgumentException("Not a settings module name: " + entry.getKey());
            }
            for (String setting : entry.getValue().keySet()) {
                if (!SettingsScopes.tenantOverridable(setting)) {
                    throw new IllegalArgumentException("Setting '" + setting
                            + "' is deployment-wide and cannot appear in a tenant document");
                }
            }
            writeDocument(scope, entry.getKey(), entry.getValue());
            written.add(SettingsDocuments.document(entry.getKey()));
        }
        for (String child : scope.list(SettingsDocuments.ROOT)) {
            String key = SettingsDocuments.ROOT + "/" + child;
            if (child.endsWith(".json") && !written.contains(key)
                    && !retainSecrets(scope, child.substring(0, child.length() - ".json".length()))) {
                scope.delete(key);
            }
        }
        tenantSnapshots.remove(tenant);
    }

    /** The tenants that hold any settings document, so the export bundle enumerates their slices and a full restore
     *  clears a tenant the bundle omits. A top-level name is a tenant by the shared {@link Scopes#valid} rule, so
     *  every reserved key space is excluded - not just the two ({@code auth}, {@code config}) this once named by
     *  hand, which left {@code audit}, {@code locks} and {@code quota} able to reach an export bundle as tenant
     *  slices. */
    public SortedSet<String> configuredTenants() throws IOException {
        SortedSet<String> tenants = new TreeSet<>();
        for (String entry : root.list("")) {
            if (!Scopes.valid(entry)) {
                continue;
            }
            if (!documentsOf(root.scope(entry)).isEmpty()) {
                tenants.add(entry);
            }
        }
        return tenants;
    }

    /** Whether a tenant holds any settings document - the fast-path check {@link LiveConfig} uses to resolve a tenant's
     *  gate from the precomputed global snapshot when the tenant has overridden nothing. */
    public boolean tenantConfigured(String tenant) {
        return !tenantSnapshot(tenant).isEmpty();
    }

    /** Restore an imported bundle: write each module's document as a whole through the store's compare-and-set, and
     *  clear any stored document the bundle omits, so an import is a full restore rather than a merge. The caller
     *  validates the bundle first (a dry {@link LiveConfig} resolve); this only persists it. A module name that is not
     *  a safe document key is refused before anything is written, so an operator-supplied name can never escape the
     *  settings prefix. A blank value is dropped, matching {@link #set} - a cleared setting is absent, not empty. */
    public void importDocuments(Map<String, ? extends Map<String, String>> documents) throws IOException {
        for (String module : documents.keySet()) {
            if (!SettingsDocuments.validModule(module)) {
                throw new IllegalArgumentException("Not a settings module name: " + module);
            }
        }
        Set<String> written = new HashSet<>();
        for (Map.Entry<String, ? extends Map<String, String>> entry : documents.entrySet()) {
            writeDocument(root, entry.getKey(), entry.getValue());
            written.add(SettingsDocuments.document(entry.getKey()));
        }
        for (String child : root.list(SettingsDocuments.ROOT)) {
            String key = SettingsDocuments.ROOT + "/" + child;
            if (child.endsWith(".json") && !written.contains(key)
                    && !retainSecrets(root, child.substring(0, child.length() - ".json".length()))) {
                root.delete(key);
            }
        }
        snapshot = load();
    }

    /** Overwrite one module's document with {@code values} (blanks dropped) through the store's compare-and-set,
     *  re-reading and retrying a lost race so a concurrent write to the same document is never clobbered blindly. A
     *  restore bundle is credential-free by construction - {@link #exportBundle} redacts every SECRET-kind key through
     *  {@code SettingsSecrets} - so a naive whole-document overwrite would silently drop a stored secret (the keyless
     *  signer's identity token) and keyless signing would stop after a backup/restore cycle. Any existing SECRET value
     *  the incoming document does not itself set is therefore carried forward, so a restore preserves live secrets
     *  while an explicit new secret value in the bundle still wins. */
    private void writeDocument(ArtifactStore store, String module, Map<String, String> values)
            throws IOException {
        Map<String, String> sanitized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                // A restored SECRET is re-encrypted here (or refused when no key is configured, §9) exactly like a
                // console write, so an import never writes a secret as plaintext; an already-enveloped value passes
                // through untouched.
                sanitized.put(key, forStore(key, value));
            }
        });
        Retries.update(store, SettingsDocuments.document(module), current -> {
            Map<String, String> merged = new LinkedHashMap<>(sanitized);
            current.map(versioned -> SettingsDocuments.parse(versioned.content())).ifPresent(stored ->
                    stored.forEach((key, value) -> {
                        if (SettingsSecrets.secret(key) && !merged.containsKey(key)) {
                            merged.put(key, value);
                        }
                    }));
            return SettingsDocuments.serialize(merged);
        });
    }

    /** Rewrite an omitted document (one a full restore would otherwise delete) down to just its stored SECRET keys, so
     *  the credential-free restore bundle - which never carries a secret - keeps the keyless signer's token rather than
     *  clearing it. Returns whether anything was preserved: {@code false} when the document holds no secret, so the
     *  caller deletes it as a full restore normally would. Compare-and-set, re-reading a lost race. */
    private static boolean retainSecrets(ArtifactStore store, String module) throws IOException {
        boolean[] preserved = new boolean[1];
        Retries.update(store, SettingsDocuments.document(module), current -> {
            preserved[0] = false;
            if (current.isEmpty()) {
                return null;
            }
            Map<String, String> secrets = new LinkedHashMap<>();
            SettingsDocuments.parse(current.get().content()).forEach((key, value) -> {
                if (SettingsSecrets.secret(key)) {
                    secrets.put(key, value);
                }
            });
            if (secrets.isEmpty()) {
                return null;
            }
            preserved[0] = true;
            return SettingsDocuments.serialize(secrets);
        });
        return preserved[0];
    }

    /** The settings epoch: a token every write bumps, empty on a store nothing has written to since it existed. A
     *  refresh that sees the token it saw last time has nothing to re-read. */
    public String epoch() throws IOException {
        return epoch.current();
    }

    /** Re-read every module document into a fresh snapshot, so another node's change is picked up. Driven on the
     *  refresh interval by {@link SettingsRefresh}, which also re-seeds the environment and the live gate when the
     *  stored settings changed, so a write-anywhere multi-node deployment converges for lookup-consumed keys too. The
     *  cached tenant snapshots are dropped so a tenant's change on another node is re-read on its next lookup too. */
    public void refresh() {
        try {
            snapshot = load();
            tenantSnapshots.clear();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to refresh runtime settings", e);
        }
    }

    /** A tenant's merged overrides, loaded once and cached until a write to that tenant or the scheduled refresh drops
     *  it - the tenant counterpart of the global snapshot, so a tenant's effective values are served from memory. */
    private Properties tenantSnapshot(String tenant) {
        Properties cached = tenantSnapshots.get(tenant);
        if (cached != null) {
            return cached;
        }
        Properties loaded;
        try {
            loaded = loadFrom(root.scope(tenant));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read tenant settings for '" + tenant + "'", e);
        }
        // An empty snapshot is cached like a full one: a tenant with no overrides used to be re-read on every lookup,
        // and every tenant-overridable key a publish consults listed the settings space again - three listings per
        // publish on a deployment that had never set a tenant value, measured. The map is bounded rather than the
        // negative left out: an anonymous deployment never validates that a request key names a real tenant, so a
        // flood of invented names could otherwise grow it without bound until the scheduled refresh clears it; past
        // TENANT_SNAPSHOTS entries an empty snapshot is answered and not kept.
        if (!loaded.isEmpty() || tenantSnapshots.size() < TENANT_SNAPSHOTS) {
            Properties raced = tenantSnapshots.putIfAbsent(tenant, loaded);
            return raced != null ? raced : loaded;
        }
        return loaded;
    }

    private Properties load() throws IOException {
        return loadFrom(root);
    }

    private static Properties loadFrom(ArtifactStore store) throws IOException {
        Properties merged = new Properties();
        for (String child : store.list(SettingsDocuments.ROOT)) {
            if (!child.endsWith(".json")) {
                continue;
            }
            Optional<ArtifactStore.Versioned> object = store.readVersioned(SettingsDocuments.ROOT + "/" + child);
            if (object.isPresent()) {
                SettingsDocuments.parse(object.get().content()).forEach(merged::setProperty);
            }
        }
        return merged;
    }
}
