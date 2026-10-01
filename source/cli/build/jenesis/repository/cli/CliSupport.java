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
            throw new IllegalArgumentException("Not logged in; run '" + AuthCommands.LOGIN + "' first.");
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

    /**
     * Take every {@code --set <key>=<value>} out of {@code args}, in order, as the settings a creation carries; what
     * is left is the command's own arguments. A {@code --set} with no {@code =} is refused rather than read as a key
     * set to nothing, which would clear it.
     */
    static Map<String, String> sets(List<String> args) {
        Map<String, String> settings = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); ) {
            if (!args.get(i).equals("--set")) {
                i++;
                continue;
            }
            if (i + 1 >= args.size() || args.get(i + 1).indexOf('=') <= 0) {
                throw new IllegalArgumentException("--set takes <key>=<value>");
            }
            String pair = args.get(i + 1);
            settings.put(pair.substring(0, pair.indexOf('=')), pair.substring(pair.indexOf('=') + 1));
            args.subList(i, i + 2).clear();
        }
        return settings;
    }

    static String orDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }
}
