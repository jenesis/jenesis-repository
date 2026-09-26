package build.jenesis.repository.server.kernel;

import module java.base;
import build.jenesis.repository.settings.Setting;
import build.jenesis.repository.store.Features;

/**
 * The boot check that tells an operator when a {@code jenreg.*} property they set is one nothing reads.
 *
 * <p>It exists because this product changes settings by clean cutover - {@code AGENTS.md} disallows compatibility
 * shims outright - while an unrecognised key is <em>silently ignored</em>. Those two together mean a rename or a
 * removal is invisible to whoever had the old key set: their value simply stops having effect, with nothing said at
 * boot, in the log or on any screen, and they discover it when the behaviour it governed does something else. The
 * cutover is right; the silence is what makes it indistinguishable from a setting that quietly stopped working.
 *
 * <h2>What counts as recognised, and why it is computed rather than listed</h2>
 * A hand-kept list of valid keys would be wrong within a release, so the recognised set is derived from the same
 * declarations the deployment actually binds and reads:
 * <ul>
 * <li><b>The settings catalogue</b> ({@code SettingsContributor.all()}) - every runtime-editable dial, including the
 *     per-module toggles, which are generated from the installed modules at boot and therefore exist in no source
 *     literal. That is why this check can see them and the build's settings-reference extractor cannot: the
 *     extractor reads class files, where those keys have not been computed yet.</li>
 * <li><b>Everything the {@code @ConfigurationProperties} classes bind</b> - the boot-only properties that are
 *     deliberately absent from the catalogue because they are not runtime-editable ({@code auth},
 *     {@code bootstrap-key}, {@code read-only}, {@code anonymous-rights}, ...). Judging against the catalogue alone
 *     would have flagged those as unknown, which is most of a real deployment's configuration.</li>
 * <li><b>The keys an installed module reads straight from its configuration</b> - a store backend's bucket, its
 *     root, its endpoint; a node's own id; a licence key - which neither of the two above can see, since the reader
 *     binds nothing. A store backend declares them on its provider, any other module as its settings contributor's
 *     startup keys. The module declares them, and a key it declares is recognised whichever backend is selected.</li>
 * <li><b>Open prefixes</b> for a property bound as a {@link Map} or a collection, where the sub-key is chosen by the
 *     operator rather than declared - {@code jenreg.proxy.<format>} being the one that matters. A prefix is not a
 *     special case written down here; it is what a {@code Map}-typed property <em>means</em>.</li>
 * </ul>
 *
 * <h2>It warns, and it never refuses</h2>
 * A refusal would turn a stale key in someone's config into a failed start, which is a bad trade for a diagnostic,
 * and it would make this check the most dangerous line in a boot. The register is {@code Features.active}'s, which
 * logs what it disabled and how to silence it rather than failing.
 *
 * <p>The comparison errs deliberately towards <b>silence</b>. Keys are flattened to alphanumerics before matching, so
 * every spelling Spring's relaxed binding accepts - {@code s3-allow-insecure-endpoint}, {@code s3AllowInsecureEndpoint},
 * {@code JENREG_S3_ALLOW_INSECURE_ENDPOINT} - is one key here, and an open prefix swallows anything beneath it. That
 * can accept a key nobody reads; it cannot reject one somebody does. The asymmetry is the point: a false negative is
 * silence, while a false positive is noise, and a warning that cries wolf on a valid deployment gets muted - after
 * which the real one is invisible too, which is the very failure this is here to end.
 */
public final class UnrecognisedSettings {

    /** The namespace, taken from the one public accessor that composes it rather than spelled a tenth time. */
    private static final String NAMESPACE = Features.key("");

    /** How deep a nested {@code @ConfigurationProperties} object is walked; also the cycle bound. */
    private static final int MAX_DEPTH = 4;

    private UnrecognisedSettings() {
    }

    /** What a deployment recognises: exact keys, and prefixes under which any sub-key is the operator's to choose. */
    public record Known(Set<String> keys, Set<String> prefixes) {

        public Known {
            keys = Set.copyOf(keys);
            prefixes = Set.copyOf(prefixes);
        }
    }

    /**
     * One bound {@code @ConfigurationProperties} object and the prefix it binds under. A list of these rather than a
     * map keyed by prefix, because two objects may bind one prefix with disjoint keys - the console's shell and its
     * identity layer both bind {@code jenreg.ui} - and a map kept one of them, so the other's keys read as unknown.
     */
    public record Bound(String prefix, Object properties) {
    }

    /** One configured key nothing reads, with the closest recognised key where one is close enough to suggest. */
    public record Finding(String key, String nearest) {
    }

    /** The keys this deployment does not read, in the spelling the operator used. */
    public record Report(List<Finding> findings) {

        public Report {
            findings = List.copyOf(findings);
        }

        public boolean isEmpty() {
            return findings.isEmpty();
        }
    }

    /**
     * The recognised set, from the catalogue and from the property objects the deployment binds.
     *
     * @param catalogue the runtime-editable dials, {@code SettingsContributor.all()}
     * @param bound     each {@code @ConfigurationProperties} object and its prefix (with or without the
     *                  {@code jenreg.} namespace); a {@code Map} or collection property contributes an open prefix
     * @param declared  the full keys installed modules declare they read, such as a store backend's; one ending in
     *                  {@code .*} opens that prefix, as a {@code Map} property does
     */
    public static Known known(List<Setting> catalogue, List<Bound> bound, Set<String> declared) {
        Set<String> keys = new HashSet<>();
        Set<String> prefixes = new HashSet<>();
        for (Setting setting : catalogue) {
            keys.add(flatten(setting.key()));
        }
        for (String key : declared) {
            if (key.endsWith(".*")) {
                prefixes.add(flatten(key.substring(0, key.length() - 2)));
            } else {
                keys.add(flatten(key));
            }
        }
        bound.forEach(each -> walk(each.properties(), root(each.prefix()), keys, prefixes, 0));
        return new Known(keys, prefixes);
    }

    /** The configured keys that are neither a recognised key nor beneath an open prefix. */
    public static Report assess(Collection<String> configured, Known known) {
        List<Finding> findings = new ArrayList<>();
        for (String key : new TreeSet<>(configured)) {
            String flat = flatten(key);
            if (flat.isEmpty() || known.keys().contains(flat)) {
                continue;
            }
            if (known.prefixes().stream().anyMatch(p -> !p.isEmpty() && flat.startsWith(p) && flat.length() > p.length())) {
                continue;
            }
            findings.add(new Finding(key, nearest(flat, known.keys())));
        }
        return new Report(findings);
    }

    /**
     * A bound object's prefix as a key stem. The namespace itself is the empty stem - a property of the
     * {@code jenreg}-rooted object is named by nothing but its own name - while a narrower root
     * ({@code jenreg.ui.oidc}) keeps what remains once the namespace is dropped.
     */
    private static String root(String prefix) {
        String flat = flatten(prefix);
        return flat.equals("jenreg") ? "" : flat;
    }

    /**
     * The property's identity for comparison: the namespace dropped and every separator removed, lowercased - the
     * equivalence Spring's relaxed binding already treats as one name, so a key set as an environment variable and
     * the same key declared in kebab-case are not reported as a mismatch.
     */
    static String flatten(String key) {
        String bare = key;
        if (bare.regionMatches(true, 0, NAMESPACE, 0, NAMESPACE.length())) {
            bare = bare.substring(NAMESPACE.length());
        } else if (bare.regionMatches(true, 0, "JENREG_", 0, "JENREG_".length())) {
            bare = bare.substring("JENREG_".length());
        }
        StringBuilder flat = new StringBuilder(bare.length());
        for (int index = 0; index < bare.length(); index++) {
            char each = bare.charAt(index);
            if (Character.isLetterOrDigit(each)) {
                flat.append(Character.toLowerCase(each));
            }
        }
        return flat.toString();
    }

    /** The closest recognised key, when it is close enough that a typo or a rename is the likely explanation. */
    private static String nearest(String flat, Set<String> keys) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : keys) {
            if (Math.abs(candidate.length() - flat.length()) >= bestDistance) {
                continue;
            }
            int distance = distance(flat, candidate, bestDistance);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        // A third of the key may differ before the suggestion is more distracting than helpful.
        return best != null && bestDistance <= Math.max(2, flat.length() / 3) ? best : null;
    }

    /** Levenshtein distance, abandoned once every cell of a row exceeds {@code cap}. */
    private static int distance(String left, String right, int cap) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            int best = current[0];
            for (int column = 1; column <= right.length(); column++) {
                int substitution = previous[column - 1] + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(substitution, Math.min(previous[column] + 1, current[column - 1] + 1));
                best = Math.min(best, current[column]);
            }
            if (best >= cap) {
                return cap;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    /** Read one bound object's property names, recursing into a nested settings object and opening a prefix at a Map. */
    private static void walk(Object object, String prefix, Set<String> keys, Set<String> prefixes, int depth) {
        if (object == null || depth >= MAX_DEPTH) {
            return;
        }
        for (Method method : object.getClass().getMethods()) {
            String property = property(method);
            if (property == null) {
                continue;
            }
            String key = prefix + property;
            Class<?> type = method.getReturnType();
            if (Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)) {
                prefixes.add(key);   // the sub-key is the operator's: jenreg.proxy.<format>
                keys.add(key);       // and the property itself is nameable, as a list
            } else if (simple(type)) {
                keys.add(key);
            } else {
                keys.add(key);
                walk(read(method, object), key, keys, prefixes, depth + 1);
            }
        }
    }

    /** The bare property name of a getter, flattened, or {@code null} where the method is not one. */
    private static String property(Method method) {
        if (method.getParameterCount() != 0 || method.getDeclaringClass() == Object.class
                || Modifier.isStatic(method.getModifiers())) {
            return null;
        }
        String name = method.getName();
        String bare;
        if (name.startsWith("get") && name.length() > 3) {
            bare = name.substring(3);
        } else if (name.startsWith("is") && name.length() > 2 && (method.getReturnType() == boolean.class
                || method.getReturnType() == Boolean.class)) {
            bare = name.substring(2);
        } else {
            return null;
        }
        return flatten(bare);
    }

    /** Whether the type is a leaf - a value a property holds rather than a nested settings object. */
    private static boolean simple(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type.isArray()
                || type.getPackageName().startsWith("java.") || type.getPackageName().startsWith("javax.");
    }

    private static Object read(Method method, Object object) {
        try {
            return method.invoke(object);
        } catch (ReflectiveOperationException | RuntimeException _) {
            return null;   // a property that will not answer contributes its own name and no children
        }
    }
}
