package build.jenesis.repository.settings;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Epoch;
import build.jenesis.repository.store.Retries;

/**
 * The settings documents of one repository or build-cache project: the per-module {@link SettingsDocuments} kept in
 * its own scope ({@code <tenant>/<repository>/.system/config/settings/<module>.json},
 * {@code .system/cache/<tenant>/<project>/.system/config/settings/<module>.json}), so they go with it when it is
 * deleted, written as one compare-and-set per module's document. Every surface reads them through here, so a value
 * resolves the same way everywhere. No setting at these levels may be a secret, so nothing here is encrypted.
 */
public final class StoredSettings {

    private StoredSettings() {
    }

    /** The scope a repository's documents live in. */
    public static ArtifactStore repository(ArtifactStore root, String tenant, String repository) {
        return root.scope(Scopes.require("tenant", tenant)).scope(Scopes.require("repository", repository));
    }

    /**
     * The scope a build-cache project's documents live in, {@code .system/cache/<tenant>/<project>}, so deleting the
     * project deletes them with its entries.
     */
    public static ArtifactStore project(ArtifactStore root, String tenant, String project) {
        return root.scope(Scopes.SYSTEM).scope(Scopes.CACHE).scope(Scopes.require("tenant", tenant))
                .scope(Scopes.require("project", project));
    }

    /**
     * A build-cache project's effective configuration read from the store, for the build cache, which runs no settings
     * service: its own documents over its tenant's over the deployment's, each wider level counting only for a key it
     * may hold ({@link SettingsScopes#settableAt}); {@code null} when none sets it. No operator pin applies.
     */
    public static UnaryOperator<String> projectChain(ArtifactStore root, String tenant, String project)
            throws IOException {
        Map<String, String> own = read(project(root, tenant, project), Setting.Scope.PROJECT);
        Map<String, String> tenants = read(root.scope(Scopes.require("tenant", tenant)), Setting.Scope.PROJECT);
        Map<String, String> deployment = read(root, Setting.Scope.PROJECT);
        return key -> {
            String value = own.get(key);
            if (value == null && SettingsScopes.settableAt(key, Setting.Scope.TENANT)) {
                value = tenants.get(key);
            }
            if (value == null && SettingsScopes.settableAt(key, Setting.Scope.GLOBAL)) {
                value = deployment.get(key);
            }
            return value;
        };
    }

    /** One key's value in a scope's documents - a point read of its owning module's document - or empty. */
    public static Optional<String> value(ArtifactStore scope, String key) throws IOException {
        Optional<ArtifactStore.Versioned> document =
                scope.readVersioned(SettingsDocuments.document(SettingsDocuments.moduleOf(key)));
        return document.isEmpty() ? Optional.empty()
                : Optional.ofNullable(SettingsDocuments.parse(document.get().content()).get(key));
    }

    /**
     * The values a scope's documents hold for the modules declaring a setting of {@code level}
     * ({@link SettingsScopes#modulesDeclaring}): one point read per module and no listing, so a request costs the same
     * however much the scope holds.
     */
    public static Map<String, String> read(ArtifactStore scope, Setting.Scope level) throws IOException {
        Map<String, String> merged = new TreeMap<>();
        for (String module : SettingsScopes.modulesDeclaring(level)) {
            Optional<ArtifactStore.Versioned> object = scope.readVersioned(SettingsDocuments.document(module));
            if (object.isPresent()) {
                merged.putAll(SettingsDocuments.parse(object.get().content()));
            }
        }
        return merged;
    }

    /** Every value in a scope's documents, found by listing them: for the one-time move off the request path, which
     *  must see any module's document. A request reads by level ({@link #read(ArtifactStore, Setting.Scope)}). */
    public static Map<String, String> read(ArtifactStore scope) throws IOException {
        Map<String, String> merged = new TreeMap<>();
        for (String child : scope.list(SettingsDocuments.ROOT)) {
            if (!child.endsWith(".json")) {
                continue;
            }
            Optional<ArtifactStore.Versioned> object = scope.readVersioned(SettingsDocuments.ROOT + "/" + child);
            if (object.isPresent()) {
                merged.putAll(SettingsDocuments.parse(object.get().content()));
            }
        }
        return merged;
    }

    /**
     * Sets each non-blank value and clears each blank one, one compare-and-set per owning module's document, so a
     * concurrent change to another key is never clobbered. The caller has validated every value
     * ({@link SettingsContributor#refusal(String, String, Setting.Scope, UnaryOperator)}).
     */
    public static void write(ArtifactStore scope, Map<String, String> values) throws IOException {
        Map<String, Map<String, String>> byModule = new TreeMap<>();
        values.forEach((key, value) -> byModule.computeIfAbsent(SettingsDocuments.moduleOf(key),
                _ -> new LinkedHashMap<>()).put(key, value == null ? "" : value.trim()));
        for (Map.Entry<String, Map<String, String>> module : byModule.entrySet()) {
            Retries.update(scope, SettingsDocuments.document(module.getKey()), current -> {
                Map<String, String> stored = current
                        .map(versioned -> SettingsDocuments.parse(versioned.content()))
                        .orElseGet(LinkedHashMap::new);
                module.getValue().forEach((key, value) -> {
                    if (value.isEmpty()) {
                        stored.remove(key);
                    } else {
                        stored.put(key, value);
                    }
                });
                return SettingsDocuments.serialize(stored);
            });
        }
    }

    /** Moves the deployment's settings epoch ({@link SettingsDocuments#EPOCH}) after a write made outside the server's
     *  settings service, so every node's re-read picks it up. */
    public static void changed(ArtifactStore root) throws IOException {
        new Epoch(root, SettingsDocuments.EPOCH).bump();
    }

    /**
     * Sets each value only where the scope holds none for its key, returning what was written: the one-time move of a
     * value into a setting, which never overwrites an operator's value and is safe to re-run.
     */
    public static Map<String, String> writeAbsent(ArtifactStore scope, Map<String, String> values) throws IOException {
        Map<String, String> stored = read(scope);
        Map<String, String> absent = new TreeMap<>();
        values.forEach((key, value) -> {
            if (!stored.containsKey(key) && value != null && !value.isBlank()) {
                absent.put(key, value);
            }
        });
        if (!absent.isEmpty()) {
            write(scope, absent);
        }
        return absent;
    }
}
