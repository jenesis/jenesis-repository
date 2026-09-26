package build.jenesis.repository.cli;

import module java.base;

import build.jenesis.repository.net.http.ScreenedHttpClient;
import module java.net.http;

/**
 * The identity and access verbs: {@code login}/{@code logout}/{@code whoami} manage the session stored under
 * {@code ~/.jenesis}, and {@code credentials}/{@code roles}/{@code trusts} administer the tenant's keys, named
 * roles and OIDC trusts over the same {@link RepositoryClient} the console drives.
 */
final class AuthCommands {

    private AuthCommands() {
    }

    static int login(String[] args, Path home) throws Exception {
        String url = null;
        String key = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--key") && i + 1 < args.length) {
                key = args[++i];
            } else if (url == null) {
                url = args[i];
            }
        }
        if (url == null) {
            throw new IllegalArgumentException("Usage: login <url> [--key <key>]");
        }
        if (key == null) {
            Console console = System.console();
            if (console != null) {
                char[] entered = console.readPassword("Repository key (blank for anonymous): ");
                if (entered != null && entered.length > 0) {
                    key = new String(entered);
                }
            }
        }
        Session session = new Session(URI.create(url), key);
        session.save(home);
        System.out.println("Logged in to " + url + ".");
        warnUnlicensed(session);
        return 0;
    }

    /**
     * Say once, at the one moment it is information rather than noise, that the deployment just logged into is not
     * licensed.
     *
     * <p><b>Why here and nowhere else.</b> {@code login} is where a session is established, so it is the only
     * command whose subject is the deployment itself; warning on every command would train people to ignore it,
     * which is worse than not warning at all.
     *
     * <p><b>On stderr, always.</b> {@code --json} guarantees stdout is exactly one JSON value, and a warning
     * printed there corrupts every caller that parses it. That guarantee is the reason this cannot be a println.
     *
     * <p><b>Silence on failure, and silence on absence.</b> Login stores a session; it must still succeed against
     * a server that is unreachable, so a probe that throws is swallowed. And a deployment sending no header is a
     * free one or an older one, not an unlicensed one - so no header means no warning, which is the difference
     * between reporting a state and inventing one.
     */
    private static void warnUnlicensed(Session session) {
        String state;
        try {
            state = new RepositoryClient(session.url(), session.key(),
                    ScreenedHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build())
                    .licenseState().orElse("").split(";")[0].trim();
        } catch (Exception unreachable) {
            return;
        }
        String warning = switch (state) {
            case "evaluation" -> "not licensed - running in evaluation mode. Nothing is switched off; "
                    + "set JENREG_LICENSE_KEY on the deployment to license it.";
            case "expired" -> "the licence on this deployment has EXPIRED. Nothing is switched off, but it is no "
                    + "longer licensed for production - renew it and set the new JENREG_LICENSE_KEY.";
            case "invalid" -> "the licence key on this deployment did not verify and is being ignored. "
                    + "Nothing is switched off; check the key was copied whole.";
            default -> null;
        };
        if (warning != null) {
            System.err.println("warning: " + warning);
        }
    }

    static int logout(String[] args, Path home) throws Exception {
        Session.clear(home);
        System.out.println("Logged out.");
        return 0;
    }

    static int whoami(String[] args, Path home) throws Exception {
        Session session = Session.load(home);
        // The session is read from disk, so unlike every other command there is no server answer for --json to
        // hand back. This is the one place a document is composed locally; the key is masked in both shapes,
        // because a stored credential must not be recoverable from something a caller might log.
        if (session == null) {
            Output.record("application/json", Output.document(new LinkedHashMap<>(Map.of("loggedIn", false))));
            System.out.println("Not logged in.");
            return 1;
        }
        SequencedMap<String, Object> who = new LinkedHashMap<>();
        who.put("loggedIn", true);
        who.put("url", session.url().toString());
        who.put("key", session.key() == null ? null : mask(session.key()));
        Output.record("application/json", Output.document(who));
        System.out.println(session.url()
                + (session.key() == null ? " (anonymous)" : " (key " + mask(session.key()) + ")"));
        return 0;
    }

    /**
     * {@code principals}: what a signed-in person holds, over the same grants a key and a group hold theirs in.
     *
     * <p>The one holder that had no client surface: a key has had {@code credentials} from the start and a group
     * now has {@code groups}, while a person's rights were reachable only through the console and the SCIM
     * connector an identity provider drives. What a person holds through a group is the group's and is not edited
     * here - editing it on the member would be editing a copy.
     */
    static int principals(String[] args, Path home) throws Exception {
        RepositoryClient client = CliSupport.client(home);
        if (args.length == 1) {
            for (RepositoryClient.Principal principal : client.principals()) {
                System.out.printf("%-32s %-16s %s%n", principal.id(),
                        principal.label() == null || principal.label().isEmpty() ? "-" : principal.label(),
                        principal.grants().isEmpty() ? "(holds nothing directly)" : principal.grants());
            }
            return 0;
        }
        switch (args[1]) {
            case "grant" -> {
                if (args.length < 5) {
                    throw new IllegalArgumentException("Usage: principals grant <id> <scope> <token,token,...>");
                }
                client.setPrincipalGrant(args[2], args[3], List.of(args[4].split(",")),
                        args.length > 5 ? args[5] : null);
                System.out.println("Granted " + args[3] + " on " + args[2] + "."
                        + (args.length > 5 ? " Lapses " + args[5] + "." : ""));
            }
            case "revoke-grant" -> {
                if (args.length < 4) {
                    throw new IllegalArgumentException("Usage: principals revoke-grant <id> <scope>");
                }
                client.removePrincipalGrant(args[2], args[3]);
                System.out.println("Removed grant " + args[3] + " on " + args[2] + ".");
            }
            case "remove" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: principals remove <id>");
                }
                client.removePrincipal(args[2]);
                System.out.println("Removed everything granted directly to " + args[2] + ".");
            }
            default -> throw new IllegalArgumentException("Unknown principals action: " + args[1]);
        }
        return 0;
    }

    /**
     * {@code groups}: a named collection of principals holding rights exactly as a person or a key does.
     *
     * <p>This is the instrument that was missing, and its absence had a shape - "everyone in developers may read
     * acme" was inexpressible, so an estate changed access one person at a time. A member holds what the group
     * holds from the next request; the grant re-derives them before it returns.
     */
    static int groups(String[] args, Path home) throws Exception {
        RepositoryClient client = CliSupport.client(home);
        if (args.length == 1) {
            for (RepositoryClient.Group group : client.groups()) {
                System.out.printf("%-24s %-16s %s%n", group.name(),
                        group.label() == null || group.label().isEmpty() ? "-" : group.label(),
                        group.grants().isEmpty() ? "(grants nothing)" : group.grants());
            }
            return 0;
        }
        switch (args[1]) {
            case "members" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: groups members <name>");
                }
                client.groupMembers(args[2]).forEach(System.out::println);
            }
            case "grant" -> {
                if (args.length < 5) {
                    throw new IllegalArgumentException("Usage: groups grant <name> <scope> <token,token,...>");
                }
                client.setGroupGrant(args[2], args[3], List.of(args[4].split(",")),
                        args.length > 5 ? args[5] : null);
                System.out.println("Granted " + args[3] + " to everyone in " + args[2] + "."
                        + (args.length > 5 ? " Lapses " + args[5] + "." : ""));
            }
            case "revoke-grant" -> {
                if (args.length < 4) {
                    throw new IllegalArgumentException("Usage: groups revoke-grant <name> <scope>");
                }
                client.removeGroupGrant(args[2], args[3]);
                System.out.println("Removed grant " + args[3] + " on " + args[2] + ".");
            }
            case "add" -> {
                if (args.length < 4) {
                    throw new IllegalArgumentException("Usage: groups add <name> <provider-qualified id>");
                }
                client.addGroupMember(args[2], args[3]);
                System.out.println("Added " + args[3] + " to " + args[2] + ".");
            }
            case "remove-member" -> {
                if (args.length < 4) {
                    throw new IllegalArgumentException("Usage: groups remove-member <name> <provider-qualified id>");
                }
                client.removeGroupMember(args[2], args[3]);
                System.out.println("Removed " + args[3] + " from " + args[2] + ".");
            }
            case "remove" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: groups remove <name>");
                }
                client.removeGroup(args[2]);
                System.out.println("Removed group " + args[2] + ".");
            }
            default -> throw new IllegalArgumentException("Unknown groups action: " + args[1]);
        }
        return 0;
    }

    static int credentials(String[] args, Path home) throws Exception {
        RepositoryClient client = CliSupport.client(home);
        if (args.length == 1) {
            List<RepositoryClient.Credential> credentials = client.credentials();
            for (RepositoryClient.Credential credential : credentials) {
                System.out.printf("%s  %-16s uses=%d%s%n", credential.id(),
                        credential.label().isEmpty() ? "-" : credential.label(), credential.useCount(),
                        credential.expires().isEmpty() ? "" : "  expires " + credential.expires());
            }
            if (credentials.isEmpty()) {
                System.out.println("No credentials.");
            }
            return 0;
        }
        switch (args[1]) {
            case "mint" -> {
                String label = null;
                for (int i = 2; i < args.length; i++) {
                    if (args[i].equals("--label") && i + 1 < args.length) {
                        label = args[++i];
                    }
                }
                RepositoryClient.Minted minted = client.mint(label);
                System.out.println("Minted credential " + minted.id() + ".");
                System.out.println("Key (shown once): " + minted.key());
                if (!minted.expires().isEmpty()) {
                    System.out.println("Expires: " + minted.expires());
                }
            }
            case "revoke" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: credentials revoke <id>");
                }
                client.revoke(args[2]);
                System.out.println("Revoked " + args[2] + ".");
            }
            case "grant" -> {
                if (args.length < 5) {
                    throw new IllegalArgumentException("Usage: credentials grant <id> <scope> <token,token,...>");
                }
                client.setGrant(args[2], args[3], List.of(args[4].split(",")));
                System.out.println("Granted " + args[3] + " on " + args[2] + ".");
            }
            case "revoke-grant" -> {
                if (args.length < 4) {
                    throw new IllegalArgumentException("Usage: credentials revoke-grant <id> <scope>");
                }
                client.removeGrant(args[2], args[3]);
                System.out.println("Removed grant " + args[3] + " on " + args[2] + ".");
            }
            case "expiry" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: credentials expiry <id> [<expiry>]  (blank never expires)");
                }
                String expiry = args.length > 3 ? args[3] : "";
                client.setExpiry(args[2], expiry);
                System.out.println(expiry.isEmpty()
                        ? "Cleared the expiry of " + args[2] + " (it never expires)."
                        : "Set the expiry of " + args[2] + ".");
            }
            case "rotate" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: credentials rotate <id> [<overlap>]");
                }
                RepositoryClient.Minted minted = client.rotate(args[2], args.length > 3 ? args[3] : null);
                System.out.println("Rotated " + args[2] + " -> " + minted.id() + ".");
                System.out.println("Key (shown once): " + minted.key());
                if (minted.expires() != null && !minted.expires().isEmpty()) {
                    System.out.println("The old key expires: " + minted.expires());
                }
            }
            case "allow-ips" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: credentials allow-ips <id> [<cidr,cidr,...>]  (blank clears)");
                }
                String addresses = args.length > 3 ? args[3] : "";
                client.setAllowedAddresses(args[2], addresses);
                System.out.println(addresses.isEmpty()
                        ? "Cleared the source-IP allowlist of " + args[2] + "."
                        : "Set the source-IP allowlist of " + args[2] + ".");
            }
            default -> throw new IllegalArgumentException("Unknown credentials command '" + args[1] + "'");
        }
        return 0;
    }

    static int roles(String[] args, Path home) throws Exception {
        RepositoryClient client = CliSupport.client(home);
        if (args.length == 1) {
            Map<String, String> roles = client.roles();
            roles.forEach((name, tokens) -> System.out.printf("%-16s %s%n", name, tokens));
            if (roles.isEmpty()) {
                System.out.println("No roles.");
            }
            return 0;
        }
        switch (args[1]) {
            case "set" -> {
                if (args.length < 4) {
                    throw new IllegalArgumentException("Usage: roles set <name> <token,token,...>");
                }
                client.setRole(args[2], args[3]);
                System.out.println("Saved role " + args[2] + ".");
            }
            case "remove" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: roles remove <name>");
                }
                client.removeRole(args[2]);
                System.out.println("Removed role " + args[2] + ".");
            }
            default -> throw new IllegalArgumentException("Unknown roles command '" + args[1] + "'");
        }
        return 0;
    }

    static int trusts(String[] args, Path home) throws Exception {
        RepositoryClient client = CliSupport.client(home);
        if (args.length == 1) {
            List<RepositoryClient.TrustView> trusts = client.trusts();
            for (RepositoryClient.TrustView trust : trusts) {
                System.out.printf("%-16s issuer=%s scope=%s rights=%s%n",
                        trust.name(), trust.issuer(), trust.scope(), trust.rights());
            }
            if (trusts.isEmpty()) {
                System.out.println("No trusts.");
            }
            return 0;
        }
        switch (args[1]) {
            case "set" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: trusts set <name> --issuer I --scope S --rights R "
                            + "[--audience A] [--subject S] [--ttl D]");
                }
                String name = args[2];
                String issuer = null;
                String audience = null;
                String subject = null;
                String scope = null;
                String rights = null;
                String ttl = null;
                for (int i = 3; i < args.length; i++) {
                    switch (args[i]) {
                        case "--issuer" -> issuer = CliSupport.flag(args, ++i);
                        case "--audience" -> audience = CliSupport.flag(args, ++i);
                        case "--subject" -> subject = CliSupport.flag(args, ++i);
                        case "--scope" -> scope = CliSupport.flag(args, ++i);
                        case "--rights" -> rights = CliSupport.flag(args, ++i);
                        case "--ttl" -> ttl = CliSupport.flag(args, ++i);
                        default -> throw new IllegalArgumentException("Unknown trusts flag '" + args[i] + "'");
                    }
                }
                if (issuer == null || scope == null || rights == null) {
                    throw new IllegalArgumentException("trusts set needs --issuer, --scope and --rights.");
                }
                client.setTrust(name, issuer, audience, subject, scope, rights, ttl);
                System.out.println("Saved trust " + name + ".");
            }
            case "remove" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: trusts remove <name>");
                }
                client.removeTrust(args[2]);
                System.out.println("Removed trust " + args[2] + ".");
            }
            default -> throw new IllegalArgumentException("Unknown trusts command '" + args[1] + "'");
        }
        return 0;
    }

    private static String mask(String key) {
        return key.length() <= 6 ? "***" : key.substring(0, 6) + "...";
    }

/**
     * The SCIM bearer token an identity provider presents when it provisions this tenant's members.
     *
     * <p>It sits under this group rather than its own because minting it is the one setup step that was reachable
     * only by clicking; the provisioning protocol itself is driven by the identity provider and is no business of
     * a command line.
     */
    static int scim(String[] args, Path home) throws Exception {
        if (args.length < 2 || !args[1].equals("token")) {
            throw new IllegalArgumentException("Usage: scim token [clear]");
        }
        if (args.length > 2 && args[2].equals("clear")) {
            return print(CliSupport.client(home).clearScimToken());
        }
        if (args.length > 2) {
            throw new IllegalArgumentException("Usage: scim token [clear]");
        }
        return print(CliSupport.client(home).mintScimToken());
    }

/**
     * The deployment's issued login keys - list, issue, revoke.
     *
     * <p>Revoking is the one that had to exist. Issuing a deployment-wide credential was already scriptable through
     * the console's own API; withdrawing one was not, so the tool that can create a credential could not end it.
     */
    static int keylogin(String[] args, Path home) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: keylogin <list|issue|revoke> [...]");
        }
        switch (args[1]) {
            case "list" -> {
                return print(CliSupport.client(home).keyLogins());
            }
            case "revoke" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: keylogin revoke <id>");
                }
                CliSupport.client(home).revokeKeyLogin(args[2]);
                System.out.println("Revoked login key " + args[2] + ".");
                return 0;
            }
            case "issue" -> {
                return issue(args, home);
            }
            default -> throw new IllegalArgumentException("Unknown keylogin command '" + args[1] + "'");
        }
    }

private static int issue(String[] args, Path home) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "Usage: keylogin issue <principal> --tenant <name> [--login <display>] [--role <role>]");
        }
        String principal = args[2], tenant = null, login = null, role = null;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--tenant" -> tenant = CliSupport.flag(args, ++i);
                case "--login" -> login = CliSupport.flag(args, ++i);
                case "--role" -> role = CliSupport.flag(args, ++i);
                default -> throw new IllegalArgumentException("Usage: keylogin issue <principal> --tenant <name> "
                        + "[--login <display>] [--role <role>]");
            }
        }
        if (tenant == null) {
            throw new IllegalArgumentException("keylogin issue needs --tenant <name>");
        }
        return print(CliSupport.client(home).issueKeyLogin(principal, login, tenant, role));
    }
    /** Print a server answer and succeed - the shape every read verb here shares. */
    private static int print(String body) {
        System.out.println(body);
        return 0;
    }
}
