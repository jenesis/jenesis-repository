package build.jenesis.repository.settings;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The on-store shape of the runtime settings: one JSON document per contributing module, kept under
 * {@code config/settings/<module>.json}, rather than a single object for the whole deployment. Keying storage by the
 * owning module lets a write to one module's settings compare-and-set only that module's document, so concurrent
 * edits to different modules never contend, and it aligns the storage key with the module attribution the modules
 * console surfaces. The neutral core dials and the map-shaped entries ({@code repositories.*}, {@code
 * format-upstream.*}) - which no discovered {@link SettingsContributor} declares - live in the {@link #NEUTRAL}
 * document.
 *
 * <p>This is the one place the document format lives, shared by the repository server ({@code Settings}), the console
 * ({@code SettingsAdmin}) and the boot-time environment layer, so the three administration surfaces read and write an
 * identical layout. It carries no store dependency - a caller streams the document bytes in and out through its own
 * {@code ArtifactStore}, this only maps a key to its document and (de)serialises the flat {@code string -> string}
 * body through the JSON library. The stored form is key-sorted and indented, so a re-write of unchanged state is
 * byte-identical and reads cleanly in a diff.
 */
public final class SettingsDocuments {

    /** The store prefix under which the per-module documents are kept. */
    public static final String ROOT = Scopes.space(Scopes.CONFIG) + "/settings";

    /** The document a setting with no discovered contributor (the neutral core dials, the map entries) belongs in. */
    public static final String NEUTRAL = "core";

    /** The reserved prefix that keys a tenant's per-module document within an export bundle: {@code tenant:<tenant>:<module>}.
     *  A colon can never appear in a JPMS module name ({@link #MODULE}), so a tenant slice never collides with a
     *  deployment-wide (global) document key, and the bundle stays one flat {@code string -> document} object that the
     *  existing serializer and parser handle unchanged. */
    public static final String TENANT_KEY_PREFIX = "tenant:";

    /** A safe document name: a JPMS module name (or {@link #NEUTRAL}), never a path that could escape the settings
     *  prefix. Disallows a separator or an empty/dot segment. */
    private static final Pattern MODULE = Pattern.compile("[A-Za-z0-9_.-]+");

    /** A safe tenant name - the same traversal-free segment the store scopes by, so an operator-supplied bundle key
     *  can never escape a tenant's scope. */
    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9_-]+");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Key-sorted and indented, so unchanged state re-writes byte-identical and a stored document reads in a diff. */
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

    /** The {@code [tenant, module]} a {@link #isTenantKey tenant bundle key} names, or {@code null} when the key is
     *  malformed or carries an unsafe tenant/module segment - so an operator-supplied bundle key can never escape a
     *  tenant's settings scope. */
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

    /** Whether {@code tenant} is a safe tenant name - a traversal-free path segment, never a path that could escape
     *  its store scope. Guards an operator-supplied import bundle before its tenant keys become store paths. */
    public static boolean validTenant(String tenant) {
        return tenant != null && TENANT.matcher(tenant).matches();
    }

    /** The store key of one module's settings document. */
    public static String document(String module) {
        return ROOT + "/" + module + ".json";
    }

    /** Whether {@code module} is a safe settings-document name - a JPMS module name or {@link #NEUTRAL}, and not a
     *  path that could escape {@link #ROOT}. Guards an operator-supplied import bundle before its module keys become
     *  store paths. */
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

    /** Serialise a flat map into a document body: a key-sorted, indented JSON object, so the stored form is stable
     *  under re-writes and reads cleanly in a diff. */
    public static byte[] serialize(Map<String, String> values) {
        return pretty(new TreeMap<>(values));
    }

    /** Serialise a whole settings bundle - each module name to that module's stored overrides - into one indented
     *  JSON object, the on-store documents dumped for export. Modules and keys are sorted, so an export of unchanged
     *  state is byte-identical and a re-import writes each document back unchanged. */
    public static byte[] serializeBundle(Map<String, ? extends Map<String, String>> documents) {
        SortedMap<String, SortedMap<String, String>> sorted = new TreeMap<>();
        documents.forEach((module, values) -> sorted.put(module, new TreeMap<>(values)));
        return pretty(sorted);
    }

    private static byte[] pretty(Object document) {
        return (PRETTY.writeValueAsString(document) + "\n").getBytes(StandardCharsets.UTF_8);
    }

}
