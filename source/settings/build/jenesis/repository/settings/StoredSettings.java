package build.jenesis.repository.settings;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Epoch;
import build.jenesis.repository.store.Retries;

/**
 * The settings documents of one repository or one build-cache project: the same per-module JSON documents
 * ({@link SettingsDocuments}) the deployment keeps at its root and a tenant inside its scope, kept inside the
 * repository's or the project's own scope - {@code <tenant>/<repository>/.system/config/settings/<module>.json} and
 * {@code .system/cache/<tenant>/<project>/.system/config/settings/<module>.json}. So what a repository or a project is
 * configured with lives with it, goes with it when it is deleted, and is written the way every other level is: one
 * compare-and-set per owning module's document.
 *
 * <p>The repository server, the console and the build cache each read these through here, which is what lets a
 * repository's value resolve over its tenant's and the deployment's the same way on every surface. Nothing stored at
 * these two levels is a secret - the census refuses a repository or project setting of the secret kind - so, unlike
 * the deployment's documents, nothing here is encrypted.
 */
public final class StoredSettings {

    private StoredSettings() {
    }

    /** The scope a repository's documents live in. */
    public static ArtifactStore repository(ArtifactStore root, String tenant, String repository) {
        return root.scope(Scopes.require("tenant", tenant)).scope(Scopes.require("repository", repository));
    }

    /**
     * The scope a build-cache project's documents live in: inside the project, in the build cache's space
     * ({@code .system/cache/<tenant>/<project>}), so deleting the project deletes them with its entries. A project
     * name is the cache's own grammar, which the scope segment rule admits.
     */
    public static ArtifactStore project(ArtifactStore root, String tenant, String project) {
        return root.scope(Scopes.SYSTEM).scope(Scopes.CACHE).scope(Scopes.require("tenant", tenant))
                .scope(Scopes.require("project", project));
    }

    /**
     * A build-cache project's effective configuration read straight from the store - its own documents over its
     * tenant's over the deployment's, {@code null} for a key none of them sets - for the build cache, which runs no
     * settings service of its own. A tenant's or the deployment's value counts only for a key it may hold
     * ({@link SettingsScopes#settableAt}), so a local key is the project's own or nothing. No operator pin applies: the
     * deployment document is where every project's default is set.
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
     * The values a scope's documents hold for the modules that declare a setting of {@code level}
     * ({@link SettingsScopes#modulesDeclaring}): one point read per such module - a repository level has two, a
     * project level one - and never a listing. It is the read a repository's and a project's requests make, and the
     * one the build cache makes each policy window, so it costs the same however much the scope holds and is cheaper
     * than the listing it replaces on an object store, where a listing is billed as about twelve reads.
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

    /** Every value stored in a scope's documents, merged across every module's document found by listing them; empty
     *  when there are none. For the one-time move, which runs off the request path and must see a document whatever
     *  module wrote it; a request reads by level ({@link #read(ArtifactStore, Setting.Scope)}). */
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
     * Set each non-blank value and clear each blank one, in each key's owning module document - one compare-and-set
     * per document, re-read and retried on a lost race so a concurrent change to another key is never clobbered. The
     * caller has validated every value ({@link SettingsContributor#refusal(String, String, Setting.Scope,
     * UnaryOperator)}); this only persists them.
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

    /** Move the settings epoch of the deployment whose root {@code root} is, after a write that did not go through the
     *  repository server's own settings service - so every node's scheduled re-read picks the write up
     *  ({@link SettingsDocuments#EPOCH}). */
    public static void changed(ArtifactStore root) throws IOException {
        new Epoch(root, SettingsDocuments.EPOCH).bump();
    }

    /**
     * Set each value only where the scope's documents hold none for its key yet, returning what was written - the
     * one-time move of a value kept elsewhere before it was a setting, which must never overwrite a value an
     * operator has since set, and is safe to run again.
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
