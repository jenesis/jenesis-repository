package build.jenesis.repository.posture;

import module java.base;

/**
 * How {@code jenrepo.ui.admins} is read, by everything that reads it.
 *
 * <p>A comma-separated list of provider-qualified ids, trimmed, empty entries dropped. {@link #EVERYONE} is meaningful
 * anywhere in it - {@code alice,*} carries the wildcard as a bare {@code *} does.
 *
 * <p>The console's authority policy, the deployment's super-admin set and the security advisory all parse through here,
 * so one key cannot mean "everyone is an admin" to one reader and "nobody" to another. What each reader does with the
 * wildcard is its own decision; what was written is not.
 */
public final class ConsoleAdmins {

    /** The wildcard entry, kept so it can be refused. It would mean "every authenticated user is an admin", and it is
     *  refused at boot everywhere: an administrator is a holder of rights, and a wildcard names no principal an
     *  operator could read back, revoke or see in a list. */
    public static final String EVERYONE = "*";

    private ConsoleAdmins() {
    }

    /** The configured ids, in order, trimmed and without empties; never {@code null}. */
    public static Set<String> parse(String configured) {
        if (configured == null) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String token : configured.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                ids.add(trimmed);
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    /** Whether the configured list carries the wildcard, alone or among named ids - a configuration a console refuses
     *  to start on. It describes the shape of a value, not a decision about access. */
    public static boolean carriesWildcard(Set<String> ids) {
        return ids.contains(EVERYONE);
    }

    /** The refusal every reader gives, worded once. */
    public static String refusal() {
        return "jenrepo.ui.admins=" + EVERYONE + " is refused: an administrator is a holder of rights, and the "
                + "wildcard names no holder - nothing is granted that an operator could read back, revoke or see "
                + "in a list. Name the operators who should hold admin, as provider-qualified ids (github/<id>, "
                + "oidc/<sub>).";
    }
}
