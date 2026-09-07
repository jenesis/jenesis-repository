package build.jenesis.repository.server.spi;

import module java.base;

import build.jenesis.repository.scope.Scopes;

/**
 * Mints a well-formed repository key from the command line, for the one key nobody can issue through the API: the
 * bootstrap key a deployment starts from ({@code JENREG_BOOTSTRAP_KEY}). The server provisions that key at boot with
 * every right on its tenant, so it has to be well-formed - the tenant is read out of it and the checksum is checked
 * before anything else - and it has to be secret, so it comes from {@link Authorization#mint} rather than from a
 * hand-typed string or a snippet in another language that has to keep the format in step.
 *
 * <p>{@code java -Djenesis.execute.module=source+server-spi build/jenesis/Execute.java [tenant]} prints one key for
 * the tenant named, {@code default} when none is, and nothing else; nothing is stored. The tenant is held to the
 * scope-name shape ({@link Scopes#require}), because a key names its tenant in the clear and the server cannot route
 * one whose tenant is not a name it could store under.
 */
public final class MintKey {

    /** The tenant a key is minted for when the command line names none: the one a single-tenant server routes to. */
    static final String DEFAULT_TENANT = "default";

    private MintKey() {
    }

    public static void main(String[] args) {
        try {
            System.out.println(key(args));
        } catch (IllegalArgumentException refused) {
            System.err.println(refused.getMessage());
            System.exit(2);
        }
    }

    /**
     * The key the command line asks for: one optional argument, the tenant. Public so that a program embedding the
     * mint (a test, a provisioning script on the module path) gets the same key {@link #main} prints, without
     * capturing standard output.
     *
     * @throws IllegalArgumentException if more than one argument is given or the tenant is not a scope name
     */
    public static String key(String... args) {
        if (args.length > 1) {
            throw new IllegalArgumentException("usage: MintKey [tenant] - one key for one tenant, "
                    + DEFAULT_TENANT + " when none is named");
        }
        String tenant = args.length == 1 ? args[0] : DEFAULT_TENANT;
        return Authorization.mint(Scopes.require("tenant", tenant));
    }
}
