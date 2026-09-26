package build.jenesis.repository.cli;

import module java.base;

import build.jenesis.repository.net.http.ScreenedHttpClient;
import module java.net.http;

/**
 * The plumbing the dispatcher and every command group reuse: constructing the authenticated {@link RepositoryClient}
 * from the stored {@link Session}, reading the value that follows a flag, and the small shared renderings. Factored
 * here so the split groups share one implementation of the login-then-call handshake rather than each copying it.
 */
final class CliSupport {

    private CliSupport() {
    }

    /** The authenticated API client for the stored session, or an error when no session is saved. */
    static RepositoryClient client(Path home) throws Exception {
        Session session = Session.load(home);
        if (session == null) {
            throw new IllegalArgumentException("Not logged in; run 'login <url> --key <key>' first.");
        }
        return new RepositoryClient(session.url(), session.key(),
                ScreenedHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    /** The value following a flag at index {@code i}, or an error naming the flag when it is missing. */
    static String flag(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Missing value for '" + args[i - 1] + "'");
        }
        return args[i];
    }

    static String orDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }
}
