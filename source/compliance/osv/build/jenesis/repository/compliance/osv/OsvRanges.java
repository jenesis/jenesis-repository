package build.jenesis.repository.compliance.osv;

import module java.base;
import module tools.jackson.databind;
import build.jenesis.repository.closure.spi.RequirementGrammar;
import build.jenesis.repository.compliance.Ecosystems;

/**
 * Whether an OSV record affects one version of a package, decided from the record alone as OSV's own schema evaluates
 * it: an {@code affected} entry naming the package affects a version its {@code versions} list names, or one its
 * {@code SEMVER} or {@code ECOSYSTEM} ranges place between an {@code introduced} event and the {@code fixed},
 * {@code last_affected} or {@code limit} event after it. Versions are ordered by the ecosystem's
 * {@link RequirementGrammar}, the order a closure takes its versions by. A {@code GIT} range names commits, which a
 * version cannot be placed among, so only the versions the record enumerates for it count. A withdrawn record affects
 * nothing.
 */
final class OsvRanges {

    /** The lowest version, as an {@code introduced} event names it. */
    private static final String ZERO = "0";

    private OsvRanges() {
    }

    /** Whether {@code record} affects {@code version} of {@code name} in {@code ecosystem}, the product's name of the
     *  ecosystem - release-qualified for a distribution, as a query names one. */
    static boolean affects(JsonNode record, String ecosystem, String name, String version) {
        if (!record.path("withdrawn").asString("").isBlank()) {
            return false;
        }
        String product = OsvQuery.base(ecosystem);
        String asked = OsvQuery.osvName(ecosystem);
        String key = key(product, name);
        String placed = version(product, version);
        Comparator<String> order = order(product);
        for (JsonNode affected : record.path("affected")) {
            JsonNode named = affected.path("package");
            if (!sameEcosystem(named.path("ecosystem").asString(""), asked)
                    || !key.equals(key(product, named.path("name").asString("")))) {
                continue;
            }
            for (JsonNode listed : affected.path("versions")) {
                if (placed.equals(version(product, listed.asString("")))) {
                    return true;
                }
            }
            for (JsonNode range : affected.path("ranges")) {
                String type = range.path("type").asString("");
                if ((type.equals("SEMVER") || type.equals("ECOSYSTEM")) && within(range.path("events"), placed, order,
                        product)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The names of the packages {@code record} affects in {@code osvEcosystem}, OSV's name of the ecosystem without
     *  a release - each spelled as {@link #key} keys it. */
    static Set<String> packages(JsonNode record, String osvEcosystem, String product) {
        Set<String> packages = new LinkedHashSet<>();
        for (JsonNode affected : record.path("affected")) {
            JsonNode named = affected.path("package");
            String name = named.path("name").asString("");
            if (OsvQuery.base(named.path("ecosystem").asString("")).equals(osvEcosystem) && !name.isBlank()) {
                packages.add(key(product, name));
            }
        }
        return packages;
    }

    /**
     * A package's name as the mirror keys it, so a version asked by any spelling the ecosystem treats as the same name
     * finds its records: PyPI's normalised name (lower case, every run of {@code -}, {@code _} and {@code .} one
     * hyphen) and NuGet's case-insensitive one, every other ecosystem's name as it is spelled.
     */
    static String key(String product, String name) {
        if (product.equals(Ecosystems.PYPI)) {
            return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
        }
        if (product.equals(Ecosystems.NUGET)) {
            return name.toLowerCase(Locale.ROOT);
        }
        return name;
    }

    /** Whether a range's {@code events} place {@code version}: walked in version order, an {@code introduced} at or
     *  below it opens the range and a {@code fixed} or {@code limit} at or below it, or a {@code last_affected} below
     *  it, closes it again. */
    private static boolean within(JsonNode events, String version, Comparator<String> order, String product) {
        List<Map.Entry<String, String>> sorted = new ArrayList<>();
        for (JsonNode event : events) {
            for (String kind : List.of("introduced", "fixed", "last_affected", "limit")) {
                String at = event.path(kind).asString(null);
                if (at != null && !at.isBlank()) {
                    sorted.add(Map.entry(kind, version(product, at)));
                }
            }
        }
        sorted.sort(Comparator.comparing(Map.Entry::getValue, (left, right) -> left.equals(ZERO) || right.equals(ZERO)
                ? Boolean.compare(!left.equals(ZERO), !right.equals(ZERO)) : order.compare(left, right)));
        boolean affected = false;
        for (Map.Entry<String, String> event : sorted) {
            String at = event.getValue();
            switch (event.getKey()) {
                case "introduced" -> {
                    if (at.equals(ZERO) || order.compare(version, at) >= 0) {
                        affected = true;
                    }
                }
                case "last_affected" -> {
                    if (order.compare(version, at) > 0) {
                        affected = false;
                    }
                }
                default -> {
                    if (order.compare(version, at) >= 0) {
                        affected = false;
                    }
                }
            }
        }
        return affected;
    }

    /** {@code version} as OSV spells it in {@code product}: Go's records name a module version without its leading
     *  {@code v}. */
    private static String version(String product, String version) {
        return product.equals(Ecosystems.GO) && version.startsWith("v") ? version.substring(1) : version;
    }

    /** The ecosystem's version order. */
    private static Comparator<String> order(String product) {
        RequirementGrammar grammar = RequirementGrammar.of(product);
        return grammar::compare;
    }

    /** Whether an entry's {@code ecosystem} is the one asked: the same name, or a release of the distribution asked
     *  without one. */
    private static boolean sameEcosystem(String ecosystem, String asked) {
        return ecosystem.equals(asked) || (asked.indexOf(':') < 0 && ecosystem.startsWith(asked + ":"));
    }
}
