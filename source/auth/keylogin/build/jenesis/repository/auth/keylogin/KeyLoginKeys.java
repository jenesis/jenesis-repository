package build.jenesis.repository.auth.keylogin;

import module java.base;

import build.jenesis.repository.scope.Scopes;
import build.jenesis.repository.store.Documents;
import build.jenesis.repository.server.spi.Authorization;
import build.jenesis.repository.store.Retries;

/**
 * The admin-issued console login keys, stored deployment-wide through the store (never a database) under the
 * sanctioned shared {@code auth/} root as {@code auth/keylogin/keys.properties} - each line
 * {@code <sha-256 hash> = <principal-id> <tenant> [login]}. Only the one-way hash of a key is ever written (via
 * {@link Authorization#hash}, the same hash the repository credentials use), so a leaked store yields no usable key;
 * the plaintext is shown once at issue time and never again. A presented key is resolved by hashing it and looking that
 * hash up, so revocation is immediate - every sign-in re-reads the file. Writes use the store's compare-and-set, so two
 * concurrent issues or revokes never lose one another (the {@link build.jenesis.repository.ui.identity.UserDirectory} pattern).
 * The key-space lives under {@code auth/} - the one deployment-global root that is shared by design (a login key spans
 * tenants), beside the user-to-tenant membership map - rather than as its own root-level space, so it sits inside the
 * sanctioned shared root the storage-scope rule blesses. The {@code tenant} each key was bound to is stored so a revoke
 * can reverse the exact membership grant the issue wrote.
 */
public final class KeyLoginKeys {

    /** Root-relative store path for the issued-key index, under the sanctioned shared {@code auth/} root. */
    static final String FILE = Scopes.space(Scopes.AUTH) + "/keylogin/keys.properties";

    private static final String PREFIX = "jkl_";
    private static final int SECRET_BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Documents root;

    public KeyLoginKeys(Documents root) {
        this.root = root;
    }

    /** One issued key as listed to an admin: its stable id (the hash, the revoke handle), who it signs in as, and the
     *  tenant its membership grant was written to - never the key itself, which exists only in the {@link Issued}
     *  returned once at issue time. */
    public record Entry(String id, String principal, String tenant, String login) {
    }

    /** The outcome of issuing a key: the plaintext {@code key}, shown to the admin exactly once, plus its stored id
     *  and the tenant it was bound to. */
    public record Issued(String id, String key, String principal, String tenant, String login) {
    }

    /** The principal a presented key resolves to. */
    public record Resolved(String principal, String login) {
    }

    /** Mint a fresh login key for {@code principal} bound to {@code tenant}, store only its hash, and return the
     *  plaintext once. The tenant is stored so a later {@link #revoke} can reverse the membership grant the issue
     *  wrote, rather than orphaning it for a same-name reissue to inherit. */
    public Issued issue(String principal, String tenant, String login) throws IOException {
        String id = require(principal);
        String scope = tenant == null ? "" : tenant.trim();
        String key = mint();
        String hash = Authorization.hash(key);
        String trimmedLogin = login == null ? "" : login.trim();
        String value = trimmedLogin.isBlank() ? id + " " + scope : id + " " + scope + " " + trimmedLogin;
        mutate(properties -> properties.setProperty(hash, value));
        return new Issued(hash, key, id, scope, trimmedLogin);
    }

    /** Resolve a presented key to its principal, or empty when no issued key hashes to it. */
    public Optional<Resolved> resolve(String presented) {
        if (presented == null || presented.isBlank()) {
            return Optional.empty();
        }
        String value = root.read(FILE).getProperty(Authorization.hash(presented.trim()));
        if (value == null) {
            return Optional.empty();
        }
        Entry entry = parse("", value);
        return Optional.of(new Resolved(entry.principal(), entry.login()));
    }

    /** Every issued key, by stable id, for an admin listing - never exposing any key material. */
    public List<Entry> list() {
        Properties properties = root.read(FILE);
        List<Entry> entries = new ArrayList<>();
        for (String id : new TreeSet<>(properties.stringPropertyNames())) {
            entries.add(parse(id, properties.getProperty(id)));
        }
        return entries;
    }

    /** The stored entry for one key id, or empty when no such key is issued - the revoke path reads it to reverse the
     *  membership grant the issue wrote. */
    public Optional<Entry> find(String id) {
        String value = root.read(FILE).getProperty(id);
        return value == null ? Optional.empty() : Optional.of(parse(id, value));
    }

    /** Parse a stored value {@code <principal> <tenant> [login]} into an entry; principal and tenant are single
     *  space-free tokens, the login is the remainder (kept for display and may itself contain spaces). */
    private static Entry parse(String id, String value) {
        String[] parts = value.trim().split("\\s+", 3);
        String principal = parts[0];
        String tenant = parts.length > 1 ? parts[1] : "";
        String login = parts.length > 2 ? parts[2] : "";
        return new Entry(id, principal, tenant, login);
    }

    /** Revoke one issued key by its stable id; a no-op when it is already gone. */
    public void revoke(String id) throws IOException {
        mutate(properties -> properties.remove(id));
    }

    private static String mint() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    private static String require(String principal) {
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("Principal id must not be blank.");
        }
        String trimmed = principal.trim();
        // Reject ANY whitespace, not just a literal space: parse() splits the stored value on \s+, so a tab or newline
        // inside a principal would survive this guard (trim() only strips the ends) and then field-split at read time,
        // hijacking the positional tenant binding of the entry. The write-time validation must reject every character
        // the read-time parser treats as a delimiter.
        if (trimmed.codePoints().anyMatch(Character::isWhitespace)
                || trimmed.contains("=") || trimmed.contains(":")) {
            throw new IllegalArgumentException("Principal id must not contain '=', ':' or whitespace.");
        }
        return trimmed;
    }

    /** Apply one edit under the store's compare-and-set so a concurrent issue/revoke never clobbers another. */
    private void mutate(Consumer<Properties> edit) throws IOException {
        Retries.compareAndSet(FILE, () -> {
            Object token = root.version(FILE);
            Properties properties = root.read(FILE);
            edit.accept(properties);
            return root.writeVersioned(FILE, properties, token);
        });
    }
}
