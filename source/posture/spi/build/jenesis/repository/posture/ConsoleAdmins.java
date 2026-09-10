package build.jenesis.repository.posture;

import module java.base;

/**
 * How {@code jenreg.ui.admins} is read: one rule, for everything that reads it.
 *
 * <p>It is a comma-separated list of provider-qualified ids, trimmed, with empty entries dropped, and
 * {@link #EVERYONE} is meaningful within it - {@code alice,*} carries the wildcard exactly as a bare {@code *} does,
 * so a rule that only matched the whole value would miss one hidden in a list.
 *
 * <p><b>It lives here because three readers had drifted.</b> The console's authority policy honoured the wildcard,
 * the deployment's super-admin set did not and treated {@code *} as a literal id matching nobody, and the security
 * advisory about the key parsed it a third time with a comment claiming to match the first. The result was one
 * documented key that meant "everyone is an admin" in one console, "nobody is" in another, and about which the
 * advisory asserted the first regardless. A key an operator sets for a security decision cannot mean two things.
 *
 * <p>This module is the shared home because it is {@code java.base}-only and every console already requires it for
 * the advisory that reads the same key. What each reader does with the wildcard is still its own decision - the
 * grant it represents is not the same size everywhere - but they now agree on what was written.
 */
public final class ConsoleAdmins {

    /**
     * The wildcard entry, kept so it can be <em>refused</em> rather than honoured.
     *
     * <p>It used to mean "every authenticated user is an admin" - the single-tenant console's open-console
     * opt-out. It is refused at boot everywhere now, because an administrator is a holder of rights and a
     * wildcard names no holder: there is no principal to grant to, so there is nothing an operator could later
     * read back, revoke, or see in a list. "Everyone this deployment authenticates holds X" is a real and
     * legitimate need, but it belongs to a holder of its own rather than to a magic value inside a list of ids.
     */
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

    /** Whether the configured list carries the wildcard, alone or among named ids - which is a configuration a
     *  console refuses to start on. Named {@code carries} rather than {@code grantsEveryone} because it no longer
     *  grants anything: it is the shape of a value, not a decision about access. */
    public static boolean carriesWildcard(Set<String> ids) {
        return ids.contains(EVERYONE);
    }

    /** The refusal every reader gives, worded once. */
    public static String refusal() {
        return "jenreg.ui.admins=" + EVERYONE + " is refused: an administrator is a holder of rights, and the "
                + "wildcard names no holder - nothing is granted that an operator could read back, revoke or see "
                + "in a list. Name the operators who should hold admin, as provider-qualified ids (github/<id>, "
                + "oidc/<sub>).";
    }
}
