package build.jenesis.repository.discovery;

import module java.base;

/**
 * The domains a module name or a Maven groupId is asked of: the name read as a reversed domain, the shortest first - the
 * two labels a vendor owns - then each longer one down to the whole name. {@code net.bytebuddy.agent} is asked of
 * {@code bytebuddy.net}, then {@code agent.bytebuddy.net}. A name that cannot be a domain - a single label, a label that
 * is not letters, digits and inner hyphens, or one longer than a label may be - is asked of nothing.
 */
public final class Domains {

    /** One DNS label as a host name spells it. */
    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    /** The most labels a name is read as, bounding the files one name can ask for. */
    public static final int MOST_LABELS = 8;

    private Domains() {
    }

    /** The domains {@code name} is asked of, shortest first; empty for a name that cannot be a domain. */
    public static List<String> of(String name) {
        if (name == null) {
            return List.of();
        }
        String[] labels = name.strip().toLowerCase(Locale.ROOT).split("\\.", -1);
        if (labels.length < 2 || labels.length > MOST_LABELS) {
            return List.of();
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                return List.of();
            }
        }
        List<String> domains = new ArrayList<>();
        StringBuilder domain = new StringBuilder(labels[0]);
        for (int index = 1; index < labels.length; index++) {
            domain.insert(0, labels[index] + ".");
            domains.add(domain.toString());
        }
        return List.copyOf(domains);
    }

    /** The part of {@code name} below {@code domain}, its labels joined by dashes after a leading one - nothing for
     *  {@code net.bytebuddy} under {@code bytebuddy.net}, {@code -agent} for {@code net.bytebuddy.agent}. */
    public static String suffix(String name, String domain) {
        int owned = domain.split("\\.").length;
        String[] labels = name.strip().toLowerCase(Locale.ROOT).split("\\.");
        StringBuilder suffix = new StringBuilder();
        for (int index = owned; index < labels.length; index++) {
            suffix.append('-').append(labels[index]);
        }
        return suffix.toString();
    }
}
