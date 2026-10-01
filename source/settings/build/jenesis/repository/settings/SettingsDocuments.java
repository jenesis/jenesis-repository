package build.jenesis.repository.settings;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The on-store shape of the runtime settings: one JSON document per contributing module under
 * {@code config/settings/<module>.json}, so a write compare-and-sets only its module's document and edits to different
 * modules never contend. The core dials and the map-shaped entries ({@code repositories.*}, {@code format-upstream.*})
 * live in the {@link #NEUTRAL} document.
 *
 * <p>The one home of the format, shared by the server, the console and the boot-time environment layer. It maps a key
 * to its document and (de)serialises the flat {@code string -> string} body; the caller does the store I/O. The stored
 * form is key-sorted and indented, so unchanged state re-writes byte-identical.
 */
public final class SettingsDocuments {

    /** The store prefix under which the per-module documents are kept. */
    public static final String ROOT = Scopes.space(Scopes.CONFIG) + "/settings";

    /** The settings epoch: a token every settings write moves, so a node's scheduled re-read re-reads the documents
     *  only when a writer anywhere changed one. Under {@code .system/config}, so no pass enumerates it as a tenant. */
    public static final String EPOCH = Scopes.space(Scopes.CONFIG) + "/settings-epoch";

    /** The document a setting with no discovered contributor (the neutral core dials, the map entries) belongs in. */
    public static final String NEUTRAL = "core";

    /** The prefix keying a tenant's module document in an export bundle, {@code tenant:<tenant>:<module>}. No module
     *  name holds a colon ({@link #MODULE}), so a tenant slice never collides with a deployment-wide document. */
    public static final String TENANT_KEY_PREFIX = "tenant:";

    /** A safe document name: a JPMS module name or {@link #NEUTRAL}, never a path. */
    private static final Pattern MODULE = Pattern.compile("[A-Za-z0-9_.-]+");

    /** A safe tenant name: the traversal-free segment the store scopes by. */
    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9_-]+");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final ObjectWriter PRETTY = JSON.writerWithDefaultPrettyPrinter();

    private SettingsDocuments() {
    }

    /** The export-bundle key for one tenant's module document: {@code tenant:<tenant>:<module>}. */
    public static String tenantKey(String tenant, String module) {
        return TENANT_KEY_PREFIX + tenant + ":" + module;
    }

    /** Whether a bundle key names a tenant slice ({@code tenant:<tenant>:<module>}) rather than a deployment-wide
     *  (global) document. */
    public static boolean isTenantKey(String key) {
        return key != null && key.startsWith(TENANT_KEY_PREFIX);
    }

    /** The {@code [tenant, module]} a {@link #isTenantKey tenant bundle key} names, or {@code null} when it is
     *  malformed or carries an unsafe segment. */
    public static String[] parseTenantKey(String key) {
        if (!isTenantKey(key)) {
            return null;
        }
        int separator = key.indexOf(':', TENANT_KEY_PREFIX.length());
        if (separator < 0) {
            return null;
        }
        String tenant = key.substring(TENANT_KEY_PREFIX.length(), separator);
        String module = key.substring(separator + 1);
        if (!validTenant(tenant) || !validModule(module)) {
            return null;
        }
        return new String[] {tenant, module};
    }

    /** Whether {@code tenant} is a safe tenant name, checked before an import bundle's keys become store paths. */
    public static boolean validTenant(String tenant) {
        return tenant != null && TENANT.matcher(tenant).matches();
    }

    /** The store key of one module's settings document. */
    public static String document(String module) {
        return ROOT + "/" + module + ".json";
    }

    /** Whether {@code module} is a safe document name, checked before an import bundle's keys become store paths. */
    public static boolean validModule(String module) {
        return module != null && !module.equals(".") && !module.equals("..") && MODULE.matcher(module).matches();
    }

    /** The module whose document a key belongs in: the JPMS name of the {@link SettingsContributor} that declares it,
     *  or {@link #NEUTRAL} when no installed contributor does (a core dial or a map entry). */
    public static String moduleOf(String key) {
        return SettingsContributor.attribution().getOrDefault(key, NEUTRAL);
    }

    /** Parse one document body (a flat JSON object of {@code string -> string}) into an ordered map; an empty map for
     *  {@code null}/empty content. Rejects a malformed body rather than silently dropping settings. */
    public static Map<String, String> parse(byte[] body) {
        Map<String, String> values = new LinkedHashMap<>();
        if (body == null || body.length == 0) {
            return values;
        }
        JsonNode document;
        try {
            document = JSON.readTree(body);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("Malformed settings document", malformed);
        }
        if (!document.isObject()) {
            throw new IllegalArgumentException("Not a settings document: expected an object");
        }
        document.properties().forEach(entry -> {
            if (!entry.getValue().isString()) {
                throw new IllegalArgumentException("Malformed settings document: '" + entry.getKey()
                        + "' is not a string");
            }
            values.put(entry.getKey(), entry.getValue().asString());
        });
        return values;
    }

    /** A flat map as a document body: key-sorted, indented JSON. */
    public static byte[] serialize(Map<String, String> values) {
        return pretty(new TreeMap<>(values));
    }

    /** A settings bundle (module to stored values) as one sorted, indented JSON object, the export form. */
    public static byte[] serializeBundle(Map<String, ? extends Map<String, String>> documents) {
        SortedMap<String, SortedMap<String, String>> sorted = new TreeMap<>();
        documents.forEach((module, values) -> sorted.put(module, new TreeMap<>(values)));
        return pretty(sorted);
    }

    private static byte[] pretty(Object document) {
        return (PRETTY.writeValueAsString(document) + "\n").getBytes(StandardCharsets.UTF_8);
    }

}
