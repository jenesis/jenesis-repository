package build.jenesis.repository.ui.store;

import module java.base;
import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.settings.SettingsDocuments;
import build.jenesis.repository.settings.SettingsScopes;
import build.jenesis.repository.settings.SettingsSecrets;
import build.jenesis.repository.store.ArtifactStore;
import build.jenesis.repository.store.Retries;

/**
 * The console's read/write of the deployment-wide runtime settings, kept as one JSON document per contributing module
 * ({@code config/settings/<module>.json}, {@link SettingsDocuments}) - the same layout and the same store of truth the
 * repository server's {@code Settings} and the {@code /api/settings} endpoint drive. A read merges every module's
 * document into one view; a write compare-and-sets only the key's owning module document, re-reading and retrying a
 * lost race, so a concurrent change to another key is never lost. Shared by {@link SettingsAdmin} and the
 * vulnerability/import lookups in {@code ComplianceReview} and {@link RepositoryImports} so the document format lives
 * in one place on the console side.
 */
final class StoredConfig {


    private StoredConfig() {
    }

    /** Every stored override, merged across all module documents, as a properties view. */
    static Properties load(ArtifactStore root) throws IOException {
        Properties merged = new Properties();
        for (String child : root.list(SettingsDocuments.ROOT)) {
            if (!child.endsWith(".json")) {
                continue;
            }
            Optional<ArtifactStore.Versioned> object = root.readVersioned(SettingsDocuments.ROOT + "/" + child);
            if (object.isPresent()) {
                SettingsDocuments.parse(object.get().content()).forEach(merged::setProperty);
            }
        }
        return merged;
    }

    /** A tenant's own stored overrides, merged across its module documents under {@code <tenant>/config/settings} - the
     *  tenant layer a tenant admin edits, over the deployment-wide effective values. */
    static Properties load(ArtifactStore root, String tenant) throws IOException {
        return load(root.scope(tenant));
    }

    /** Every stored settings document, module name to that module's stored overrides, read straight from the store -
     *  the on-store shape the console's settings export dumps as one JSON bundle. Sorted (documents and keys) so an
     *  export of unchanged state is byte-identical; an empty document is omitted. */
    static SortedMap<String, SortedMap<String, String>> documents(ArtifactStore root) throws IOException {
        SortedMap<String, SortedMap<String, String>> documents = new TreeMap<>();
        for (String child : root.list(SettingsDocuments.ROOT)) {
            if (!child.endsWith(".json")) {
                continue;
            }
            Optional<ArtifactStore.Versioned> object = root.readVersioned(SettingsDocuments.ROOT + "/" + child);
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

    /** The tenants that hold any settings document, so the export bundle enumerates their slices. A top-level name is
     *  a tenant by the shared {@link Scopes#valid} rule, so every reserved key space is excluded - not just the two
     *  ({@code auth}, {@code config}) this once named by hand, which left {@code audit}, {@code locks} and
     *  {@code quota} able to reach an export bundle as tenant slices. */
    static SortedSet<String> configuredTenants(ArtifactStore root) throws IOException {
        SortedSet<String> tenants = new TreeSet<>();
        for (String entry : root.list("")) {
            if (!Scopes.valid(entry)) {
                continue;
            }
            if (!documents(root.scope(entry)).isEmpty()) {
                tenants.add(entry);
            }
        }
        return tenants;
    }

    /** The full export bundle: the deployment-wide (global) documents keyed by module plus every tenant's documents
     *  keyed {@code tenant:<tenant>:<module>}, one flat object the serializer emits byte-identically - with no tenant
     *  overrides it is exactly the global bundle. Credential-free by construction: every SECRET-kind key is excluded
     *  (a stored secret - the keyless identity token - never travels in a downloaded backup), the same by-construction
     *  guard the {@code /api/settings/export} endpoint applies. */
    static SortedMap<String, SortedMap<String, String>> exportBundle(ArtifactStore root) throws IOException {
        SortedMap<String, SortedMap<String, String>> bundle = new TreeMap<>(documents(root));
        for (String tenant : configuredTenants(root)) {
            documents(root.scope(tenant)).forEach((module, values) ->
                    bundle.put(SettingsDocuments.tenantKey(tenant, module), values));
        }
        return SettingsSecrets.redact(bundle);
    }

    /** Restore a full export bundle: the global documents and every tenant slice it carries, a full restore that clears
     *  a global document or tenant document the bundle omits and refuses a global-only key in a tenant slice. */
    static void importBundle(ArtifactStore root, Map<String, ? extends Map<String, String>> bundle) throws IOException {
        Map<String, Map<String, Map<String, String>>> perTenant = new LinkedHashMap<>();
        Map<String, Map<String, String>> global = new LinkedHashMap<>();
        bundle.forEach((key, document) -> {
            Map<String, String> values = document == null ? Map.of() : document;
            if (SettingsDocuments.isTenantKey(key)) {
                String[] parsed = SettingsDocuments.parseTenantKey(key);
                if (parsed == null) {
                    throw new IllegalArgumentException("Not a tenant settings key: " + key);
                }
                guardTenantScope(values.keySet());
                perTenant.computeIfAbsent(parsed[0], _ -> new LinkedHashMap<>()).put(parsed[1], values);
            } else {
                global.put(key, values);
            }
        });
        importDocuments(root, global);
        Set<String> tenants = new TreeSet<>(perTenant.keySet());
        tenants.addAll(configuredTenants(root));
        for (String tenant : tenants) {
            importTenant(root, tenant, perTenant.getOrDefault(tenant, Map.of()));
        }
    }

    /** Restore one tenant's slice - a full restore of the tenant's documents (a document the slice omits is cleared),
     *  every key re-checked tenant-overridable, leaving the global settings and other tenants untouched. */
    static void importTenant(ArtifactStore root, String tenant, Map<String, ? extends Map<String, String>> documents)
            throws IOException {
        if (!SettingsDocuments.validTenant(tenant)) {
            throw new IllegalArgumentException("Not a tenant name: " + tenant);
        }
        documents.values().forEach(document -> {
            if (document != null) {
                guardTenantScope(document.keySet());
            }
        });
        importDocuments(root.scope(tenant), documents);
    }

    /** Refuse a deployment-wide (global-only) key in a tenant document - a tenant retunes only its own gate policy,
     *  deny list or forward target. */
    private static void guardTenantScope(Set<String> keys) {
        for (String key : keys) {
            if (!SettingsScopes.tenantOverridable(key)) {
                throw new IllegalArgumentException("Setting '" + key
                        + "' is deployment-wide and cannot appear in a tenant document");
            }
        }
    }

    /** Set or clear one override in a tenant's own scope, refusing a global-only key. */
    static void put(ArtifactStore root, String tenant, String key, String value) throws IOException {
        if (!SettingsDocuments.validTenant(tenant)) {
            throw new IllegalArgumentException("Not a tenant name: " + tenant);
        }
        guardTenantScope(Set.of(key));
        put(root.scope(tenant), key, value);
    }

    /** Restore an imported bundle: write each module document as a whole, and clear any stored document the bundle
     *  omits, so an import is a full restore rather than a merge. A module name that is not a safe document key is
     *  refused before anything is written. The caller validates the values first. */
    static void importDocuments(ArtifactStore root, Map<String, ? extends Map<String, String>> documents)
            throws IOException {
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
            if (child.endsWith(".json") && !written.contains(key)) {
                root.delete(key);
            }
        }
    }

    /** Overwrite one module's document with {@code values} (blanks dropped) through the store's compare-and-set,
     *  re-reading and retrying a lost race. */
    private static void writeDocument(ArtifactStore root, String module, Map<String, String> values)
            throws IOException {
        Map<String, String> sanitized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                sanitized.put(key, value.trim());
            }
        });
        byte[] body = SettingsDocuments.serialize(sanitized);
        Retries.update(root, SettingsDocuments.document(module), current -> body);
    }

    /** Set ({@code value} non-blank) or clear ({@code null}/blank) one override in its owning module document. */
    static void put(ArtifactStore root, String key, String value) throws IOException {
        Retries.update(root, SettingsDocuments.document(SettingsDocuments.moduleOf(key)), current -> {
            Map<String, String> values = current
                    .map(versioned -> SettingsDocuments.parse(versioned.content()))
                    .orElseGet(LinkedHashMap::new);
            if (value == null || value.isBlank()) {
                values.remove(key);
            } else {
                values.put(key, value.trim());
            }
            return SettingsDocuments.serialize(values);
        });
    }
}
